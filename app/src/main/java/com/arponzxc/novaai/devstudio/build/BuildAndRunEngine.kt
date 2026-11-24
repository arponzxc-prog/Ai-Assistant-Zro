package com.arponzxc.novaai.devstudio.build

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.arponzxc.novaai.devstudio.model.ApkVerificationResult
import com.arponzxc.novaai.devstudio.model.BuildErrorDiagnostic
import com.arponzxc.novaai.devstudio.model.BuildValidationResult
import com.arponzxc.novaai.devstudio.model.DetectedProjectType
import com.arponzxc.novaai.devstudio.model.DevProject
import com.arponzxc.novaai.devstudio.model.ProjectBuildStatus
import com.arponzxc.novaai.devstudio.model.ProjectType
import com.arponzxc.novaai.devstudio.model.TermuxCommandResult
import com.arponzxc.novaai.devstudio.model.TermuxCommandState
import java.io.File
import java.util.zip.ZipFile

/**
 * Plan describing how an Android project should be built inside Termux.
 */
data class AndroidBuildPlan(
    val command: String,
    val hasGradleWrapper: Boolean,
    val isReadyToDispatch: Boolean,
    val configurationIssues: List<String>
)

/**
 * Honest report of a Termux build: success is only reported when Termux finished
 * with exit code 0 AND a structurally valid APK exists.
 */
data class TermuxBuildReport(
    val buildStatus: ProjectBuildStatus,
    val summary: String,
    val apkInfo: ApkVerificationResult? = null,
    val diagnostics: List<BuildErrorDiagnostic> = emptyList(),
    val durationMs: Long = 0L
) {
    val errorCount: Int get() = diagnostics.count { it.severity == "ERROR" }
    val warningCount: Int get() = diagnostics.count { it.severity == "WARNING" }
}

object BuildAndRunEngine {

    /** Maximum time the Gradle build is allowed to run inside Termux (20 minutes). */
    const val BUILD_TIMEOUT_MS = 1_200_000L

    private const val MIN_APK_BYTES = 1024L
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val SEVERITY_ERROR = "ERROR"
    private const val SEVERITY_WARNING = "WARNING"
    private const val MAX_DIAGNOSTICS = 100
    private const val MAX_HTML_FILES = 300
    private const val MAX_HTML_BYTES = 1_000_000L

    private val kotlinErrorRegex = Regex("""^e:\s+(?:file://)?(.+?):(\d+):(\d+)\s+(.+)$""")
    private val kotlinWarningRegex = Regex("""^w:\s+(?:file://)?(.+?):(\d+):(\d+)\s+(.+)$""")
    private val javaErrorRegex = Regex("""^(.+\.(?:java|xml)):(\d+): error: (.+)$""")
    private val aaptErrorRegex = Regex("""^ERROR:(.+?):(\d+): AAPT: error: (.+)$""")
    private val htmlRefRegex = Regex("""(?:src|href)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------------------------------
    // Project detection / wrapper
    // ------------------------------------------------------------------------------------------

    private fun gradleFile(dir: File, baseName: String): File? {
        val kts = File(dir, "$baseName.gradle.kts")
        if (kts.isFile) return kts
        val groovy = File(dir, "$baseName.gradle")
        return if (groovy.isFile) groovy else null
    }

    private fun hasSettingsGradle(dir: File): Boolean = gradleFile(dir, "settings") != null

    private fun hasAppModule(dir: File): Boolean = gradleFile(File(dir, "app"), "build") != null

    fun detectProjectType(dir: File): DetectedProjectType {
        if (!dir.isDirectory) return DetectedProjectType.INVALID
        if (hasSettingsGradle(dir) && hasAppModule(dir)) return DetectedProjectType.NATIVE_ANDROID
        if (File(dir, "index.html").isFile) return DetectedProjectType.HTML_WEBSITE
        if (hasSettingsGradle(dir) || gradleFile(dir, "build") != null) return DetectedProjectType.IMPORTED_PROJECT
        return DetectedProjectType.INVALID
    }

    /** A wrapper counts as complete only if gradlew, its properties and its jar all exist. */
    fun hasGradleWrapper(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return File(dir, "gradlew").isFile &&
            File(dir, "gradle/wrapper/gradle-wrapper.properties").isFile &&
            File(dir, "gradle/wrapper/gradle-wrapper.jar").isFile
    }

    // ------------------------------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------------------------------

    fun validateProject(project: DevProject): BuildValidationResult {
        val root = File(project.rootPath)
        if (!root.isDirectory) {
            val diag = BuildErrorDiagnostic(
                severity = SEVERITY_ERROR,
                category = "Missing Project Folder",
                file = project.rootPath,
                line = 0,
                message = "Project folder does not exist: ${project.rootPath}",
                suggestedFix = "Re-create or re-import the project."
            )
            return BuildValidationResult(
                isValid = false,
                detectedType = DetectedProjectType.INVALID,
                hasGradleWrapper = false,
                diagnostics = listOf(diag),
                summary = diag.message
            )
        }
        return when (project.type) {
            ProjectType.ANDROID_KOTLIN -> validateAndroid(root, project)
            ProjectType.HTML_WEB -> validateWeb(root, project)
        }
    }

    private fun validateAndroid(root: File, project: DevProject): BuildValidationResult {
        val diags = mutableListOf<BuildErrorDiagnostic>()
        val wrapper = hasGradleWrapper(root)

        if (!hasSettingsGradle(root)) {
            diags += BuildErrorDiagnostic(
                SEVERITY_ERROR, "Missing Gradle Settings", "settings.gradle.kts", 0,
                "settings.gradle(.kts) was not found in the project root.",
                "Create settings.gradle.kts with rootProject.name and include(\":app\")."
            )
        }
        if (!hasAppModule(root)) {
            diags += BuildErrorDiagnostic(
                SEVERITY_ERROR, "Missing App Module", "app/build.gradle.kts", 0,
                "app/build.gradle(.kts) was not found.",
                "Add an app module with the com.android.application plugin."
            )
        }
        val manifest = File(root, "app/src/main/AndroidManifest.xml")
        if (!manifest.isFile) {
            diags += BuildErrorDiagnostic(
                SEVERITY_ERROR, "Missing Manifest", "app/src/main/AndroidManifest.xml", 0,
                "AndroidManifest.xml was not found.",
                "Create app/src/main/AndroidManifest.xml with a <manifest> and <application> element."
            )
        } else {
            val text = runCatching { manifest.readText() }.getOrDefault("")
            if (!text.contains("<manifest")) {
                diags += BuildErrorDiagnostic(
                    SEVERITY_ERROR, "Invalid Manifest", "app/src/main/AndroidManifest.xml", 1,
                    "AndroidManifest.xml has no <manifest> element.",
                    "Restore a valid <manifest> root element."
                )
            }
        }
        val entry = project.entryPoint.trim()
        if (entry.isNotEmpty() && !File(root, entry).isFile) {
            diags += BuildErrorDiagnostic(
                SEVERITY_WARNING, "Missing Entry Point", entry, 0,
                "Entry point file '$entry' was not found.",
                "Check the entry point path or create the file."
            )
        }
        if (!wrapper) {
            diags += BuildErrorDiagnostic(
                SEVERITY_WARNING, "Gradle Wrapper Missing", "gradlew", 0,
                "Gradle wrapper is missing or incomplete; system Gradle in Termux will be used.",
                "Install Gradle in Termux (pkg install gradle) or add the Gradle wrapper files."
            )
        }
        return finishValidation(
            detected = detectProjectType(root),
            wrapper = wrapper,
            diags = diags,
            okMessage = "Android project structure looks valid (settings, app module and manifest found)."
        )
    }

    private fun validateWeb(root: File, project: DevProject): BuildValidationResult {
        val diags = mutableListOf<BuildErrorDiagnostic>()
        val entry = project.entryPoint.trim().ifBlank { "index.html" }
        if (!File(root, entry).isFile) {
            diags += BuildErrorDiagnostic(
                SEVERITY_ERROR, "Missing Entry Point", entry, 0,
                "Entry file '$entry' was not found.",
                "Create $entry as the main page of the website."
            )
        }

        val seen = HashSet<String>()
        val htmlFiles = root.walkTopDown()
            .onEnter { dir -> dir == root || (!dir.name.startsWith(".") && dir.name != "node_modules") }
            .filter { it.isFile && (it.extension.equals("html", true) || it.extension.equals("htm", true)) }
            .take(MAX_HTML_FILES)
            .toList()

        for (html in htmlFiles) {
            if (html.length() > MAX_HTML_BYTES) continue
            val text = runCatching { html.readText() }.getOrNull() ?: continue
            val relHtml = html.relativeTo(root).path.replace(File.separatorChar, '/')
            for (match in htmlRefRegex.findAll(text)) {
                val ref = match.groupValues[1].trim()
                if (!isCheckableLocalRef(ref)) continue
                val path = ref.substringBefore('#').substringBefore('?')
                val target = if (path.startsWith("/")) {
                    File(root, path.trimStart('/'))
                } else {
                    File(html.parentFile ?: root, path)
                }
                if (target.exists()) continue
                if (!seen.add("$relHtml|$ref")) continue
                val lineNo = text.substring(0, match.range.first).count { it == '\n' } + 1
                diags += BuildErrorDiagnostic(
                    SEVERITY_ERROR, "Missing Referenced Asset", relHtml, lineNo,
                    "Referenced file '$ref' was not found.",
                    "Create '${path.trimStart('/')}' or fix the path in $relHtml."
                )
            }
        }
        return finishValidation(
            detected = DetectedProjectType.HTML_WEBSITE,
            wrapper = false,
            diags = diags,
            okMessage = "HTML website is valid (entry file and all local assets found)."
        )
    }

    private fun isCheckableLocalRef(ref: String): Boolean {
        val r = ref.trim()
        if (r.isEmpty() || r.startsWith("#")) return false
        val lower = r.lowercase()
        val skipPrefixes = listOf("http:", "https:", "//", "data:", "mailto:", "tel:", "javascript:", "blob:", "about:")
        if (skipPrefixes.any { lower.startsWith(it) }) return false
        if (r.contains("\${") || r.contains("{{") || r.contains("<%")) return false
        val path = r.substringBefore('#').substringBefore('?')
        if (path.isEmpty()) return false
        // Only plain files with an extension are checked; routes like "/about" are ignored.
        return path.substringAfterLast('/').contains('.')
    }

    private fun finishValidation(
        detected: DetectedProjectType,
        wrapper: Boolean,
        diags: List<BuildErrorDiagnostic>,
        okMessage: String
    ): BuildValidationResult {
        val errors = diags.filter { it.severity == SEVERITY_ERROR }
        val warnings = diags.filter { it.severity == SEVERITY_WARNING }
        val isValid = errors.isEmpty()
        val summary = if (isValid) {
            if (warnings.isEmpty()) okMessage else "$okMessage ${warnings.size} warning(s)."
        } else {
            "${errors.size} validation error(s): " + errors.take(3).joinToString("; ") { it.message }
        }
        return BuildValidationResult(
            isValid = isValid,
            detectedType = detected,
            hasGradleWrapper = wrapper,
            diagnostics = diags,
            summary = summary
        )
    }

    // ------------------------------------------------------------------------------------------
    // Build plan
    // ------------------------------------------------------------------------------------------

    fun prepareAndroidBuildPlan(projectDir: File): AndroidBuildPlan {
        val issues = mutableListOf<String>()
        val hasSettings = hasSettingsGradle(projectDir)
        val hasApp = hasAppModule(projectDir)
        if (!hasSettings) issues += "settings.gradle(.kts) was not found in the project root."
        if (!hasApp) issues += "app/build.gradle(.kts) was not found."
        val ready = hasSettings && hasApp

        val wrapper = hasGradleWrapper(projectDir)
        val command = if (wrapper) {
            "./gradlew --no-daemon --console=plain --build-cache assembleDebug"
        } else {
            issues += "Gradle wrapper is missing or incomplete; using system Gradle from Termux (pkg install gradle)."
            "gradle --no-daemon --console=plain --build-cache assembleDebug"
        }
        return AndroidBuildPlan(
            command = command,
            hasGradleWrapper = wrapper,
            isReadyToDispatch = ready,
            configurationIssues = issues
        )
    }

    // ------------------------------------------------------------------------------------------
    // APK discovery and verification
    // ------------------------------------------------------------------------------------------

    fun findBuiltDebugApk(root: File): File? {
        if (!root.isDirectory) return null
        val debugDirs = mutableListOf(
            File(root, "app/build/outputs/apk/debug"),
            File(root, "build/outputs/apk/debug")
        )
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.forEach { module ->
            debugDirs += File(module, "build/outputs/apk/debug")
        }
        return debugDirs
            .distinct()
            .flatMap { dir ->
                dir.listFiles { f -> f.isFile && f.extension.equals("apk", ignoreCase = true) }
                    ?.toList()
                    .orEmpty()
            }
            .maxByOrNull { it.lastModified() }
    }

    /** Returns null when the file is a plausible APK, otherwise a human readable reason. */
    private fun integrityFailure(file: File): String? {
        if (!file.exists() || !file.isFile) return "APK file not found."
        if (file.length() < MIN_APK_BYTES) return "APK file is too small (${file.length()} bytes)."
        return try {
            ZipFile(file).use { zip ->
                when {
                    zip.getEntry("AndroidManifest.xml") == null -> "APK is missing AndroidManifest.xml."
                    zip.entries().asSequence().none { it.name.startsWith("classes") && it.name.endsWith(".dex") } ->
                        "APK contains no classes.dex."
                    else -> null
                }
            }
        } catch (e: Exception) {
            "File is not a valid APK archive: ${e.message}"
        }
    }

    fun verifyApkIntegrity(file: File): Boolean = integrityFailure(file) == null

    @Suppress("DEPRECATION")
    fun verifyApkWithMetadata(file: File, context: Context?): ApkVerificationResult {
        val size = if (file.exists()) file.length() else 0L
        val failure = integrityFailure(file)
        if (failure != null) {
            return ApkVerificationResult(
                isValid = false,
                apkPath = file.absolutePath,
                fileName = file.name,
                sizeBytes = size,
                packageName = null,
                versionName = null,
                versionCode = null,
                failureReason = failure
            )
        }
        var pkg: String? = null
        var versionName: String? = null
        var versionCode: Long? = null
        if (context != null) {
            val info = runCatching {
                context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            }.getOrNull()
            if (info == null) {
                return ApkVerificationResult(
                    isValid = false,
                    apkPath = file.absolutePath,
                    fileName = file.name,
                    sizeBytes = size,
                    packageName = null,
                    versionName = null,
                    versionCode = null,
                    failureReason = "Android could not read the package information from this APK."
                )
            }
            pkg = info.packageName
            versionName = info.versionName
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
        }
        return ApkVerificationResult(
            isValid = true,
            apkPath = file.absolutePath,
            fileName = file.name,
            sizeBytes = size,
            packageName = pkg,
            versionName = versionName,
            versionCode = versionCode,
            failureReason = null
        )
    }

    // ------------------------------------------------------------------------------------------
    // Termux build result evaluation (never reports fake success)
    // ------------------------------------------------------------------------------------------

    fun evaluateTermuxBuildResult(
        result: TermuxCommandResult,
        projectRootToSearchApk: File,
        context: Context? = null
    ): TermuxBuildReport {
        val diagnostics = parseGradleCompilerOutput(result.stdout + "\n" + result.stderr)
        val took = result.executionTimeMs

        fun failed(message: String) = TermuxBuildReport(
            buildStatus = ProjectBuildStatus.BUILD_FAILED,
            summary = message,
            apkInfo = null,
            diagnostics = diagnostics,
            durationMs = took
        )

        return when (result.state) {
            TermuxCommandState.TIMED_OUT ->
                failed(result.errorMessage ?: "Build timed out before Gradle finished.")
            TermuxCommandState.CANCELLED ->
                TermuxBuildReport(ProjectBuildStatus.CANCELLED, "Build was cancelled.", null, diagnostics, took)
            TermuxCommandState.PERMISSION_REQUIRED ->
                failed(result.errorMessage ?: "Termux RUN_COMMAND permission is required.")
            TermuxCommandState.RESULT_UNAVAILABLE ->
                failed(
                    result.errorMessage
                        ?: "No result came back from Termux, so the build cannot be confirmed. " +
                        "Make sure allow-external-apps = true is set in ~/.termux/termux.properties."
                )
            TermuxCommandState.FAILED ->
                failed(result.errorMessage ?: "Termux command failed (exit code ${result.exitCode ?: "unknown"}).")
            TermuxCommandState.PENDING, TermuxCommandState.DISPATCHED, TermuxCommandState.RUNNING ->
                failed("Build has not finished yet, so it cannot be marked successful.")
            TermuxCommandState.COMPLETED -> evaluateCompleted(result, projectRootToSearchApk, context, diagnostics)
        }
    }

    private fun evaluateCompleted(
        result: TermuxCommandResult,
        root: File,
        context: Context?,
        diagnostics: List<BuildErrorDiagnostic>
    ): TermuxBuildReport {
        val exit = result.exitCode
        if (exit != 0) {
            val errorCount = diagnostics.count { it.severity == SEVERITY_ERROR }
            val first = diagnostics.firstOrNull { it.severity == SEVERITY_ERROR }
            val detail = if (first != null) {
                " $errorCount compiler error(s). First: ${first.file.substringAfterLast('/')}:${first.line} ${first.message}"
            } else {
                ""
            }
            return TermuxBuildReport(
                buildStatus = ProjectBuildStatus.BUILD_FAILED,
                summary = "Gradle build failed (exit code ${exit ?: "unknown"}) after ${formatDuration(result.executionTimeMs)}.$detail",
                apkInfo = null,
                diagnostics = diagnostics,
                durationMs = result.executionTimeMs
            )
        }

        val apk = findBuiltDebugApk(root)
        if (apk == null) {
            return TermuxBuildReport(
                buildStatus = ProjectBuildStatus.BUILD_FAILED,
                summary = "Build exited with code 0 but no debug APK was found under app/build/outputs/apk/debug.",
                apkInfo = null,
                diagnostics = diagnostics,
                durationMs = result.executionTimeMs
            )
        }
        val info = verifyApkWithMetadata(apk, context)
        return if (info.isValid) {
            val warnings = diagnostics.count { it.severity == SEVERITY_WARNING }
            val warnNote = if (warnings > 0) ", $warnings warning(s)" else ""
            TermuxBuildReport(
                buildStatus = ProjectBuildStatus.APK_READY,
                summary = "APK ready: ${info.fileName} (${info.sizeBytes / 1024} KB) built in ${formatDuration(result.executionTimeMs)}$warnNote",
                apkInfo = info,
                diagnostics = diagnostics,
                durationMs = result.executionTimeMs
            )
        } else {
            TermuxBuildReport(
                buildStatus = ProjectBuildStatus.BUILD_FAILED,
                summary = "Build finished but the APK is invalid: ${info.failureReason ?: "unknown reason"}",
                apkInfo = info,
                diagnostics = diagnostics,
                durationMs = result.executionTimeMs
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // Compiler output parsing
    // ------------------------------------------------------------------------------------------

    fun parseGradleCompilerOutput(output: String): List<BuildErrorDiagnostic> {
        val results = mutableListOf<BuildErrorDiagnostic>()
        val seen = HashSet<String>()

        fun add(file: String, line: Int, message: String, isKotlin: Boolean) {
            val key = "$file|$line|$message"
            if (!seen.add(key)) return
            val category = categorize(message, isKotlin)
            results += BuildErrorDiagnostic(
                severity = SEVERITY_ERROR,
                category = category,
                file = file,
                line = line,
                message = message,
                suggestedFix = suggestFix(category)
            )
        }

        for (rawLine in output.lineSequence()) {
            if (results.size >= MAX_DIAGNOSTICS) break
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val warningMatch = kotlinWarningRegex.matchEntire(line)
            if (warningMatch != null) {
                val file = warningMatch.groupValues[1]
                val lineNo = warningMatch.groupValues[2].toIntOrNull() ?: 0
                val message = warningMatch.groupValues[4].trim()
                if (seen.add("W|$file|$lineNo|$message")) {
                    results += BuildErrorDiagnostic(
                        severity = SEVERITY_WARNING,
                        category = categorizeWarning(message),
                        file = file,
                        line = lineNo,
                        message = message,
                        suggestedFix = suggestWarningFix(message)
                    )
                }
                continue
            }

            val kotlinMatch = kotlinErrorRegex.matchEntire(line)
            if (kotlinMatch != null) {
                add(kotlinMatch.groupValues[1], kotlinMatch.groupValues[2].toIntOrNull() ?: 0, kotlinMatch.groupValues[4].trim(), true)
                continue
            }
            val aaptMatch = aaptErrorRegex.matchEntire(line)
            if (aaptMatch != null) {
                add(aaptMatch.groupValues[1].trim(), aaptMatch.groupValues[2].toIntOrNull() ?: 0, aaptMatch.groupValues[3].trim(), false)
                continue
            }
            val javaMatch = javaErrorRegex.matchEntire(line)
            if (javaMatch != null) {
                add(javaMatch.groupValues[1], javaMatch.groupValues[2].toIntOrNull() ?: 0, javaMatch.groupValues[3].trim(), false)
            }
        }
        return results
    }

    private fun categorizeWarning(message: String): String {
        val m = message.lowercase()
        return when {
            m.contains("deprecated") -> "Deprecated API"
            m.contains("unused") || m.contains("never used") -> "Unused Code"
            m.contains("unchecked") -> "Unchecked Cast"
            m.contains("always true") || m.contains("always false") || m.contains("condition") -> "Redundant Condition"
            else -> "Kotlin Warning"
        }
    }

    private fun suggestWarningFix(message: String): String {
        val m = message.lowercase()
        return when {
            m.contains("deprecated") -> "Switch to the replacement API named in the warning."
            m.contains("unused") || m.contains("never used") -> "Remove the unused variable, parameter or import."
            m.contains("unchecked") -> "Add a safe type check before the cast or suppress it deliberately."
            else -> "Not blocking the build; review when convenient."
        }
    }

    /** "3m 12s" / "45s" for build summaries. */
    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    private fun categorize(message: String, isKotlin: Boolean): String {
        val m = message.lowercase()
        return when {
            m.contains("unresolved reference") -> "Unresolved Reference"
            m.contains("type mismatch") -> "Type Mismatch"
            m.contains("cannot infer") || m.contains("not enough information to infer") -> "Type Inference Error"
            m.contains("overload resolution ambiguity") -> "Overload Ambiguity"
            m.contains("none of the following") || m.contains("too many arguments") ||
                m.contains("no value passed") || m.contains("cannot be called") -> "Wrong Arguments"
            m.contains("expecting") || m.contains("unexpected tokens") || m.contains("syntax") -> "Syntax Error"
            m.contains("redeclaration") || m.contains("conflicting declarations") -> "Duplicate Declaration"
            m.contains("resource") && m.contains("not found") -> "Missing Android Resource"
            m.contains("aapt") || m.contains("resource") -> "Android Resource Error"
            isKotlin -> "Kotlin Compiler Error"
            else -> "Java Compiler Error"
        }
    }

    private fun suggestFix(category: String): String = when (category) {
        "Unresolved Reference" -> "Add the missing import or dependency, or fix the misspelled name."
        "Type Mismatch" -> "Convert the value to the expected type or change the declared type."
        "Type Inference Error" -> "Specify the type explicitly (for example in a lambda parameter or generic call)."
        "Overload Ambiguity" -> "Pass explicit argument types so only one overload matches."
        "Wrong Arguments" -> "Check the function signature and the arguments being passed."
        "Syntax Error" -> "Fix the syntax near this line (missing bracket, comma or keyword)."
        "Duplicate Declaration" -> "Rename or remove the duplicated declaration."
        "Missing Android Resource" -> "Create the missing resource file or correct the resource name."
        "Android Resource Error" -> "Check the XML resource for typos and invalid attributes."
        else -> "Read the compiler message and fix the code at this location."
    }

    // ------------------------------------------------------------------------------------------
    // Install / share / launch
    // ------------------------------------------------------------------------------------------

    private fun apkUri(context: Context, apk: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)

    /**
     * Returns the system package-installer intent when the app may request installs;
     * otherwise falls back to a share sheet so the user still gets the APK out.
     */
    fun createApkInstallOrShareIntent(context: Context, apk: File): Intent {
        val canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
        if (!canInstall) return createApkShareIntent(context, apk)
        val uri = apkUri(context, apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createApkShareIntent(context: Context, apk: File): Intent {
        val uri = apkUri(context, apk)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = APK_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share APK").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun launchInstalledApp(context: Context, packageName: String): Result<String> = runCatching {
        require(packageName.isNotBlank()) { "No package name is known for this project yet." }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalStateException("App '$packageName' is not installed on this device yet. Install the APK first.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        "Launched $packageName"
    }
}
