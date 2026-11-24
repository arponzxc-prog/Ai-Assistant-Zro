package com.arponzxc.novaai.devstudio

import android.os.Bundle
import com.arponzxc.novaai.automation.command.CommandParser
import com.arponzxc.novaai.automation.command.JarvisCommand
import com.arponzxc.novaai.devstudio.build.BuildAndRunEngine
import com.arponzxc.novaai.devstudio.builder.AndroidProjectGenerator
import com.arponzxc.novaai.devstudio.builder.WebProjectGenerator
import com.arponzxc.novaai.devstudio.model.DetectedProjectType
import com.arponzxc.novaai.devstudio.model.DevChangeProposal
import com.arponzxc.novaai.devstudio.model.DiffAction
import com.arponzxc.novaai.devstudio.model.ProjectBuildStatus
import com.arponzxc.novaai.devstudio.model.ProjectType
import com.arponzxc.novaai.devstudio.model.SandboxCommandRisk
import com.arponzxc.novaai.devstudio.model.TermuxCommandResult
import com.arponzxc.novaai.devstudio.model.TermuxCommandState
import com.arponzxc.novaai.devstudio.preview.LocalWebPreviewServer
import com.arponzxc.novaai.devstudio.security.DevSandboxGuard
import com.arponzxc.novaai.devstudio.termux.TermuxBridge
import com.arponzxc.novaai.devstudio.workspace.DevWorkspaceManager
import com.arponzxc.novaai.devstudio.workspace.SharedWorkspaceBridge
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DevStudioSuiteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun androidProjectGenerator_createsValidBuildableStructureAndTemplateNotice() {
        val workspace = DevWorkspaceManager(tempFolder.root)
        val plan = AndroidProjectGenerator.generateProjectPlan(
            prompt = "Build a scientific calculator Android app",
            requestedAppName = "SciCalc",
            requestedPackage = "com.novastudio.scicalc"
        )
        assertFalse(plan.generatedByAi)
        assertNotNull(plan.limitationNotice)
        assertTrue(plan.limitationNotice!!.contains("Template Fallback"))

        val files = plan.files.associate { it.relativePath to it.content }
        val project = workspace.createProject(
            name = plan.projectName,
            packageName = plan.packageName,
            type = ProjectType.ANDROID_KOTLIN,
            entryPoint = "app/src/main/java/com/novastudio/scicalc/MainActivity.kt",
            files = files,
            generatedByAi = plan.generatedByAi
        )

        val validation = BuildAndRunEngine.validateProject(project)
        assertTrue("Expected valid project, got: ${validation.summary}", validation.isValid)
        assertEquals(DetectedProjectType.NATIVE_ANDROID, validation.detectedType)
        assertFalse(validation.hasGradleWrapper)
        assertTrue(files.containsKey("settings.gradle.kts"))
        assertTrue(files.containsKey("app/build.gradle.kts"))
        assertTrue(files.containsKey("app/src/main/AndroidManifest.xml"))
        assertTrue(files.containsKey("app/src/main/java/com/novastudio/scicalc/MainActivity.kt"))
    }

    @Test
    fun buildAndRunEngine_detectsWrapperAndRejectsFakeSuccessWithoutExit0OrValidApk() {
        val projDir = tempFolder.newFolder("sample_android")
        File(projDir, "settings.gradle.kts").writeText("rootProject.name = \"demo\"\ninclude(\":app\")")
        File(projDir, "app").mkdirs()
        File(projDir, "app/build.gradle.kts").writeText("plugins { id(\"com.android.application\") }\nandroid { compileSdk = 34 }")
        assertEquals(DetectedProjectType.NATIVE_ANDROID, BuildAndRunEngine.detectProjectType(projDir))

        // Without wrapper
        val noWrapperPlan = BuildAndRunEngine.prepareAndroidBuildPlan(projDir)
        assertTrue(noWrapperPlan.isReadyToDispatch)
        assertFalse(noWrapperPlan.hasGradleWrapper)
        assertEquals("gradle --no-daemon --console=plain --build-cache assembleDebug", noWrapperPlan.command)
        assertTrue(noWrapperPlan.configurationIssues.isNotEmpty())

        // With complete wrapper
        File(projDir, "gradlew").apply {
            writeText("#!/bin/sh\necho gradle")
            setExecutable(true)
        }
        File(projDir, "gradle/wrapper").mkdirs()
        File(projDir, "gradle/wrapper/gradle-wrapper.properties").writeText("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.7-bin.zip")
        File(projDir, "gradle/wrapper/gradle-wrapper.jar").writeBytes(ByteArray(32))
        val withWrapperPlan = BuildAndRunEngine.prepareAndroidBuildPlan(projDir)
        assertTrue(withWrapperPlan.hasGradleWrapper)
        assertEquals("./gradlew --no-daemon --console=plain --build-cache assembleDebug", withWrapperPlan.command)

        // Timed out / unavailable / non-zero must NEVER mark BUILD_SUCCESSFUL or APK_READY
        val timedOut = TermuxCommandResult("e1", "gradle assembleDebug", projDir.absolutePath, TermuxCommandState.TIMED_OUT, "", "", null, 180_000L, "Timed out", SandboxCommandRisk.SAFE_WORKSPACE_BUILD)
        assertEquals(ProjectBuildStatus.BUILD_FAILED, BuildAndRunEngine.evaluateTermuxBuildResult(timedOut, projDir).buildStatus)

        val unavailable = timedOut.copy(state = TermuxCommandState.RESULT_UNAVAILABLE, exitCode = null)
        assertEquals(ProjectBuildStatus.BUILD_FAILED, BuildAndRunEngine.evaluateTermuxBuildResult(unavailable, projDir).buildStatus)

        val zeroNoApk = timedOut.copy(state = TermuxCommandState.COMPLETED, exitCode = 0)
        assertEquals(ProjectBuildStatus.BUILD_FAILED, BuildAndRunEngine.evaluateTermuxBuildResult(zeroNoApk, projDir).buildStatus)

        // Create a real minimal valid APK ZIP (>= 1024B containing AndroidManifest.xml + classes.dex)
        val apkOutDir = File(projDir, "app/build/outputs/apk/debug").apply { mkdirs() }
        val apkFile = File(apkOutDir, "app-debug.apk")
        val rng = java.util.Random(42L)
        val manifestBytes = ByteArray(1024).also { rng.nextBytes(it) }
        val dexBytes = ByteArray(1024).also { rng.nextBytes(it) }
        ZipOutputStream(apkFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(manifestBytes)
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(dexBytes)
            zos.closeEntry()
        }
        val report = BuildAndRunEngine.evaluateTermuxBuildResult(zeroNoApk, projDir)
        assertEquals(report.summary, ProjectBuildStatus.APK_READY, report.buildStatus)
        assertNotNull(report.apkInfo)
        assertTrue(report.apkInfo!!.isValid)
    }

    @Test
    fun webProjectGenerator_validationDetectsMissingAssets_andPreviewServerDetectsFileChanges() {
        val workspace = DevWorkspaceManager(tempFolder.root)
        val plan = WebProjectGenerator.generateProjectPlan("Create a responsive portfolio website")
        assertNotNull(plan.limitationNotice)
        val project = workspace.createProject(
            name = plan.projectName,
            packageName = plan.packageName,
            type = ProjectType.HTML_WEB,
            entryPoint = "index.html",
            files = plan.files.associate { it.relativePath to it.content }
        )
        assertTrue(BuildAndRunEngine.validateProject(project).isValid)

        // Delete CSS file -> validation must detect missing local asset reference
        workspace.deleteEntry(project.id, "style.css")
        val brokenValidation = BuildAndRunEngine.validateProject(project)
        assertFalse("Missing style.css should fail validation", brokenValidation.isValid)
        assertTrue(brokenValidation.diagnostics.any { it.category == "Missing Referenced Asset" })

        // Restore style.css & test live HTTP server + change token polling without restart
        workspace.writeFile(project.id, "style.css", "body { color: white; }", createBackup = false)
        val server = LocalWebPreviewServer()
        try {
            val rootDir = workspace.getProjectRoot(project.id)
            val baseUrl = server.start(rootDir)
            assertTrue(server.isRunning)
            val tokenBefore = server.getDirectoryChangeToken(rootDir)

            val conn = URL("$baseUrl/index.html").openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            assertTrue(conn.contentType.contains("text/html"))

            val cssConn = URL("$baseUrl/style.css").openConnection() as HttpURLConnection
            assertEquals(200, cssConn.responseCode)

            val missingConn = URL("$baseUrl/nonexistent.js").openConnection() as HttpURLConnection
            assertEquals(404, missingConn.responseCode)

            // Modify CSS in workspace and check live reload token updates
            workspace.writeFile(project.id, "style.css", "body { color: #38BDF8; background: #000; }", createBackup = false)
            val tokenAfter = server.getDirectoryChangeToken(rootDir)
            assertNotEquals("Directory token must change after CSS update", tokenBefore, tokenAfter)
            assertTrue(server.isRunning)
        } finally {
            server.stop()
            assertFalse(server.isRunning)
        }
    }

    @Test
    fun sharedWorkspaceBridge_verifiesSha256TransferOverwriteConfirmationAndLockRecovery() {
        val src = tempFolder.newFolder("proj_src")
        File(src, "index.html").writeText("<h1>Nova Test</h1>")
        File(src, "assets").mkdirs()
        File(src, "assets/app.js").writeText("console.log('ok');")

        val dest = File(tempFolder.root, "shared_NOVA_AI_Projects/Websites/proj_src")

        var progressCalls = 0
        val first = SharedWorkspaceBridge.transferDirectoryWithVerification(
            sourceRoot = src,
            destRoot = dest,
            overwriteConfirmed = false
        ) { _, _ -> progressCalls++ }
        assertTrue(first.success)
        assertEquals(2, first.transferredFiles)
        assertEquals(2, progressCalls)
        assertFalse(File(dest, SharedWorkspaceBridge.TRANSFER_LOCK_NAME).exists())
        assertEquals(
            SharedWorkspaceBridge.sha256File(File(src, "index.html")),
            SharedWorkspaceBridge.sha256File(File(dest, "index.html"))
        )

        // Attempting transfer again without overwriteConfirmed must prompt confirmation
        val conflict = SharedWorkspaceBridge.transferDirectoryWithVerification(
            sourceRoot = src,
            destRoot = dest,
            overwriteConfirmed = false
        )
        assertFalse(conflict.success)
        assertTrue(conflict.requiresOverwriteConfirmation)

        // Interrupted transfer recovery (.nova_transfer.lock left behind)
        File(dest, SharedWorkspaceBridge.TRANSFER_LOCK_NAME).writeText("transfer_started=12345")
        val recovered = SharedWorkspaceBridge.transferDirectoryWithVerification(
            sourceRoot = src,
            destRoot = dest,
            overwriteConfirmed = false
        )
        assertTrue(recovered.success)
        assertTrue(recovered.recoveredFromInterruptedTransfer)
        assertFalse(File(dest, SharedWorkspaceBridge.TRANSFER_LOCK_NAME).exists())
    }

    @Test
    fun termuxBridge_parsesProbeOutputSeparatingInstalledFromMissingTools() {
        val probeStdout = """
            __JAVA__
            openjdk version "17.0.10"
            __GRADLE__
            Gradle 8.7
            __PYTHON__
            MISSING
            __NODE__
            v20.11.1
            __NPM__
            10.2.4
            __GIT__
            bash: git: command not found
            __DF__
            /dev/block/dm-0  100G  40G  60G  40% /storage
        """.trimIndent()

        val parsed = TermuxBridge.parseProbeOutput(probeStdout)
        assertEquals("openjdk version \"17.0.10\"", parsed["JAVA"])
        assertEquals("Gradle 8.7", parsed["GRADLE"])
        assertEquals("MISSING", parsed["PYTHON"])
        assertEquals("v20.11.1", parsed["NODE"])
        assertEquals("bash: git: command not found", parsed["GIT"])
    }

    @Test
    fun sandboxGuard_blocksTraversalZipSlipDangerousCommands_andRedactsKeys() {
        val workspaceRoot = tempFolder.newFolder("sandbox")

        var traversalBlocked = false
        try {
            DevSandboxGuard.resolveSafeWorkspacePath(workspaceRoot, "../../etc/passwd")
        } catch (_: SecurityException) {
            traversalBlocked = true
        }
        assertTrue("Path traversal must throw SecurityException", traversalBlocked)

        var zipSlipBlocked = false
        val malZipBytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zos ->
                zos.putNextEntry(ZipEntry("../../evil.sh"))
                zos.write("echo pwned".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()
        val manager = DevWorkspaceManager(tempFolder.root)
        try {
            manager.importProjectZip(ByteArrayInputStream(malZipBytes), "BadZip")
        } catch (_: SecurityException) {
            zipSlipBlocked = true
        }
        assertTrue("Zip Slip entry must be rejected", zipSlipBlocked)

        assertEquals(SandboxCommandRisk.BLOCKED_DANGEROUS, DevSandboxGuard.classifyCommand("rm -rf /"))
        assertEquals(SandboxCommandRisk.BLOCKED_DANGEROUS, DevSandboxGuard.classifyCommand("su -c id"))
        assertEquals(SandboxCommandRisk.REQUIRES_CONFIRMATION, DevSandboxGuard.classifyCommand("rm temp.txt"))
        assertEquals(SandboxCommandRisk.SAFE_WORKSPACE_BUILD, DevSandboxGuard.classifyCommand("gradle assembleDebug"))
        assertEquals(SandboxCommandRisk.SAFE_READ, DevSandboxGuard.classifyCommand("gradle -v"))

        val rawLog = "Error calling https://api.example.com?key=AIzaSyD1234567890ABCDEFGHIJKLMNOP with bearer sk-proj-99887766554433221100"
        val cleanLog = DevSandboxGuard.redactSensitiveData(rawLog)
        assertFalse(cleanLog.contains("AIzaSyD1234567890"))
        assertFalse(cleanLog.contains("sk-proj-998877665544"))
        assertTrue(cleanLog.contains("REDACTED"))
    }

    @Test
    fun workspaceManager_persistsExtendedMetadataDiffBackupAndRevert() {
        val manager = DevWorkspaceManager(tempFolder.root)
        val project = manager.createProject(
            name = "BackupDemo",
            packageName = "com.novastudio.backupdemo",
            type = ProjectType.ANDROID_KOTLIN,
            entryPoint = "app/src/main/java/com/novastudio/backupdemo/MainActivity.kt",
            files = mapOf("index.html" to "<h1>Original</h1>"),
            generatedByAi = true
        )
        manager.updateProjectStatus(
            projectId = project.id,
            buildStatus = ProjectBuildStatus.APK_READY,
            sharedWorkingDir = "/storage/emulated/0/NOVA_AI_Projects/AndroidProjects/backupdemo",
            lastApkPackage = "com.novastudio.backupdemo",
            lastApkVersion = "1.2.0"
        )
        val reloaded = manager.getProject(project.id)
        assertNotNull(reloaded)
        assertEquals(ProjectBuildStatus.APK_READY, reloaded!!.buildStatus)
        assertEquals("/storage/emulated/0/NOVA_AI_Projects/AndroidProjects/backupdemo", reloaded.sharedWorkingDir)
        assertEquals("com.novastudio.backupdemo", reloaded.lastApkPackage)
        assertEquals("1.2.0", reloaded.lastApkVersion)
        assertTrue(reloaded.generatedByAi)

        val diff = manager.computeDiffEntry(
            relativePath = "index.html",
            beforeContent = "<h1>Original</h1>",
            afterContent = "<h1>Updated</h1>",
            action = DiffAction.MODIFY
        )
        val proposal = DevChangeProposal(
            id = "prop_1",
            projectId = project.id,
            title = "Update title",
            rationale = "Test backup and revert",
            diffs = listOf(diff)
        )
        assertEquals(1, manager.applyChangeProposal(project.id, proposal))
        assertEquals("<h1>Updated</h1>", manager.readFile(project.id, "index.html"))
        assertTrue(manager.revertLastBackup(project.id, "index.html"))
        assertEquals("<h1>Original</h1>", manager.readFile(project.id, "index.html"))
    }

    @Test
    fun buildAndRunEngine_parsesKotlinCompilerErrorsAndCategorizesThem() {
        val sampleGradleError = """
            > Task :app:compileDebugKotlin FAILED
            e: file:///workspace/app/src/main/java/com/novastudio/app/MainActivity.kt:24:13 Unresolved reference: FloatingActionButton
            FAILURE: Build failed with an exception.
        """.trimIndent()

        val diags = BuildAndRunEngine.parseGradleCompilerOutput(sampleGradleError)
        assertEquals(1, diags.size)
        assertEquals(24, diags[0].line)
        assertTrue(diags[0].category.contains("Unresolved Reference"))
        assertTrue(diags[0].file.endsWith("MainActivity.kt"))
    }

    @Test
    fun buildAndRunEngine_parsesKotlinWarningsWithoutTreatingThemAsErrors() {
        val output = """
            w: file:///workspace/app/src/main/java/com/novastudio/app/Util.kt:10:5 'getDrawable(Int): Drawable?' is deprecated. Deprecated in Java
            w: file:///workspace/app/src/main/java/com/novastudio/app/Util.kt:22:9 Variable 'temp' is never used
            e: file:///workspace/app/src/main/java/com/novastudio/app/MainActivity.kt:24:13 Unresolved reference: Foo
        """.trimIndent()
        val diags = BuildAndRunEngine.parseGradleCompilerOutput(output)
        assertEquals(3, diags.size)
        assertEquals(2, diags.count { it.severity == "WARNING" })
        assertEquals(1, diags.count { it.severity == "ERROR" })
        assertTrue(diags.any { it.category == "Deprecated API" })
        assertTrue(diags.any { it.category == "Unused Code" })
    }

    @Test
    fun buildAndRunEngine_formatsDurations() {
        assertEquals("45s", BuildAndRunEngine.formatDuration(45_000L))
        assertEquals("3m 12s", BuildAndRunEngine.formatDuration(192_000L))
    }

    @Test
    fun commandParser_recognizesAllEightBengaliAndEnglishDevStudioVoiceCommands() {
        // 1. Open Dev Studio
        assertTrue(CommandParser.parse("Hey Nova open dev studio").command is JarvisCommand.OpenDevStudio)

        // 2. Create Android App
        val createAndroid = CommandParser.parse("নোভা একটি calculator android app তৈরি করো")
        assertTrue(createAndroid.command is JarvisCommand.GenerateDevProject)
        assertEquals("ANDROID_KOTLIN", (createAndroid.command as JarvisCommand.GenerateDevProject).projectType)

        // 3. Create HTML Website
        val createWeb = CommandParser.parse("একটি HTML Website তৈরি করো")
        assertTrue(createWeb.command is JarvisCommand.GenerateDevProject)
        assertEquals("HTML_WEB", (createWeb.command as JarvisCommand.GenerateDevProject).projectType)

        // 4. Build My App / Generate APK
        assertTrue(CommandParser.parse("আমার App Build করো").command is JarvisCommand.BuildActiveDevProject)
        assertTrue(CommandParser.parse("আমার APK তৈরি করো").command is JarvisCommand.BuildActiveDevProject)
        assertTrue(CommandParser.parse("build my app").command is JarvisCommand.BuildActiveDevProject)

        // 5. Run / Preview Website
        assertTrue(CommandParser.parse("আমার Website Run করো").command is JarvisCommand.OpenWebLivePreview)
        assertTrue(CommandParser.parse("Nova preview website").command is JarvisCommand.OpenWebLivePreview)

        // 6. Check Java / Toolchain in Termux
        assertTrue(CommandParser.parse("Termux-এ Java Version Check করো").command is JarvisCommand.CheckTermuxEnvironment)
        assertTrue(CommandParser.parse("Check Termux status").command is JarvisCommand.CheckTermuxEnvironment)

        // 7. Analyze Build Errors
        assertTrue(CommandParser.parse("Build Error Analyse করো").command is JarvisCommand.AnalyzeBuildErrors)
        assertTrue(CommandParser.parse("analyze build error").command is JarvisCommand.AnalyzeBuildErrors)

        // 8. Close Active Project
        assertTrue(CommandParser.parse("আমার Project বন্ধ করো").command is JarvisCommand.CloseDevProject)
        assertTrue(CommandParser.parse("close my project").command is JarvisCommand.CloseDevProject)
    }
}
