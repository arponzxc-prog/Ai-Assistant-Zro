package com.arponzxc.novaai.devstudio.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.arponzxc.novaai.devstudio.build.BuildAndRunEngine
import com.arponzxc.novaai.devstudio.model.ApkVerificationResult
import com.arponzxc.novaai.devstudio.model.DevProject
import com.arponzxc.novaai.devstudio.model.ProjectType
import com.arponzxc.novaai.devstudio.model.TransferProgressState
import com.arponzxc.novaai.devstudio.security.DevSandboxGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class SharedWorkspaceConfig(
    val treeUriString: String?,
    val rootFolderName: String,
    val resolvedTermuxBasePath: String?,
    val isAuthorized: Boolean,
    val foldersInitialized: Boolean
)

data class ProjectTransferResult(
    val success: Boolean,
    val requiresOverwriteConfirmation: Boolean = false,
    val sharedFolderName: String,
    val sharedTermuxPath: String?,
    val transferredFiles: Int = 0,
    val failedReason: String? = null,
    val recoveredFromInterruptedTransfer: Boolean = false
)

class SharedWorkspaceBridge(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getConfig(): SharedWorkspaceConfig {
        val uriStr = prefs.getString(KEY_TREE_URI, null)?.takeIf { it.isNotBlank() }
        val customBase = prefs.getString(KEY_CUSTOM_TERMUX_BASE, null)?.takeIf { it.isNotBlank() }
        val uri = uriStr?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val hasPersistedPerm = uri != null && hasPersistedUriPermission(uri)

        val derivedPosix = customBase ?: uri?.let { derivePosixPathFromTreeUri(it) }
        return SharedWorkspaceConfig(
            treeUriString = if (hasPersistedPerm) uriStr else null,
            rootFolderName = ROOT_FOLDER_NAME,
            resolvedTermuxBasePath = derivedPosix,
            isAuthorized = hasPersistedPerm,
            foldersInitialized = prefs.getBoolean(KEY_FOLDERS_INIT, false) && hasPersistedPerm
        )
    }

    fun setCustomTermuxBasePath(path: String) {
        val clean = path.trim().trimEnd('/')
        prefs.edit().putString(KEY_CUSTOM_TERMUX_BASE, clean).apply()
    }

    fun authorizeAndInitializeWorkspace(treeUri: Uri): Result<SharedWorkspaceConfig> {
        return runCatching {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            }
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("Selected location is not accessible via Android Storage Access Framework.")
            if (!rootDoc.canWrite()) {
                error("Selected folder does not grant write permission.")
            }

            // Use or create NOVA_AI_Projects/ inside user-selected folder unless user picked NOVA_AI_Projects directly
            val workspaceDoc = if (rootDoc.name.equals(ROOT_FOLDER_NAME, ignoreCase = true)) {
                rootDoc
            } else {
                rootDoc.findFile(ROOT_FOLDER_NAME)?.takeIf { it.isDirectory }
                    ?: rootDoc.createDirectory(ROOT_FOLDER_NAME)
                    ?: error("Could not create $ROOT_FOLDER_NAME directory.")
            }

            REQUIRED_SUBFOLDERS.forEach { sub ->
                val existing = workspaceDoc.findFile(sub)
                if (existing == null || !existing.isDirectory) {
                    workspaceDoc.createDirectory(sub)
                        ?: error("Could not create required subfolder $ROOT_FOLDER_NAME/$sub.")
                }
            }

            val derivedPosix = derivePosixPathFromTreeUri(treeUri)?.let { base ->
                if (rootDoc.name.equals(ROOT_FOLDER_NAME, ignoreCase = true)) base
                else "$base/$ROOT_FOLDER_NAME"
            }

            prefs.edit()
                .putString(KEY_TREE_URI, treeUri.toString())
                .putBoolean(KEY_FOLDERS_INIT, true)
                .apply()

            SharedWorkspaceConfig(
                treeUriString = treeUri.toString(),
                rootFolderName = ROOT_FOLDER_NAME,
                resolvedTermuxBasePath = prefs.getString(KEY_CUSTOM_TERMUX_BASE, null)?.ifBlank { null } ?: derivedPosix,
                isAuthorized = true,
                foldersInitialized = true
            )
        }
    }

    suspend fun transferProjectToSharedWorkspace(
        project: DevProject,
        sourceRoot: File,
        overwriteConfirmed: Boolean,
        onProgress: (TransferProgressState) -> Unit
    ): ProjectTransferResult = withContext(Dispatchers.IO) {
        val config = getConfig()
        val treeUriStr = config.treeUriString
        if (!config.isAuthorized || treeUriStr == null) {
            return@withContext ProjectTransferResult(
                success = false,
                sharedFolderName = project.name,
                sharedTermuxPath = null,
                failedReason = "Shared workspace folder ($ROOT_FOLDER_NAME/) is not authorized yet. Tap 'Authorize Shared Folder' first."
            )
        }

        val treeUri = Uri.parse(treeUriStr)
        val selectedDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext ProjectTransferResult(
                success = false,
                sharedFolderName = project.name,
                sharedTermuxPath = null,
                failedReason = "SAF tree URI is no longer readable."
            )
        val workspaceDoc = if (selectedDoc.name.equals(ROOT_FOLDER_NAME, ignoreCase = true)) {
            selectedDoc
        } else {
            selectedDoc.findFile(ROOT_FOLDER_NAME) ?: selectedDoc.createDirectory(ROOT_FOLDER_NAME)
        } ?: return@withContext ProjectTransferResult(
            success = false,
            sharedFolderName = project.name,
            sharedTermuxPath = null,
            failedReason = "Could not locate or create $ROOT_FOLDER_NAME."
        )

        val categoryFolderName = when (project.type) {
            ProjectType.ANDROID_KOTLIN -> SUB_ANDROID
            ProjectType.HTML_WEB -> SUB_WEBSITES
        }
        val categoryDoc = workspaceDoc.findFile(categoryFolderName)
            ?: workspaceDoc.createDirectory(categoryFolderName)
            ?: return@withContext ProjectTransferResult(
                success = false,
                sharedFolderName = project.name,
                sharedTermuxPath = null,
                failedReason = "Could not open $categoryFolderName."
            )

        val safeFolderName = DevSandboxGuard.sanitizeProjectSlug(project.name)
        val existingDest = categoryDoc.findFile(safeFolderName)
        val lockMarker = existingDest?.findFile(TRANSFER_LOCK_NAME)
        val wasInterrupted = lockMarker != null && lockMarker.exists()

        if (existingDest != null && !overwriteConfirmed && !wasInterrupted) {
            return@withContext ProjectTransferResult(
                success = false,
                requiresOverwriteConfirmation = true,
                sharedFolderName = safeFolderName,
                sharedTermuxPath = config.resolvedTermuxBasePath?.let { "$it/$categoryFolderName/$safeFolderName" },
                failedReason = "Destination folder '$categoryFolderName/$safeFolderName' already exists in shared workspace."
            )
        }

        val destDoc = existingDest ?: categoryDoc.createDirectory(safeFolderName)
            ?: return@withContext ProjectTransferResult(
                success = false,
                sharedFolderName = safeFolderName,
                sharedTermuxPath = null,
                failedReason = "Failed creating destination folder $safeFolderName."
            )

        val sourceFiles = collectTransferableFiles(sourceRoot)
        val total = sourceFiles.size
        if (total == 0) {
            return@withContext ProjectTransferResult(
                success = false,
                sharedFolderName = safeFolderName,
                sharedTermuxPath = null,
                failedReason = "Source workspace has no valid files to transfer."
            )
        }

        // Write recovery lock marker before copying
        val activeLock = destDoc.findFile(TRANSFER_LOCK_NAME)
            ?: destDoc.createFile("text/plain", TRANSFER_LOCK_NAME)
        activeLock?.let { lock ->
            context.contentResolver.openOutputStream(lock.uri, "wt")?.use { out ->
                out.write("transfer_started=${System.currentTimeMillis()}".toByteArray())
            }
        }

        var copiedCount = 0
        for (srcFile in sourceFiles) {
            val relPath = sourceRoot.toPath().relativize(srcFile.toPath()).toString().replace('\\', '/')
            onProgress(
                TransferProgressState(
                    active = true,
                    phase = if (wasInterrupted) "Resuming interrupted transfer..." else "Syncing to $ROOT_FOLDER_NAME",
                    copiedFiles = copiedCount,
                    totalFiles = total,
                    destinationPath = "$ROOT_FOLDER_NAME/$categoryFolderName/$safeFolderName"
                )
            )
            val copiedOk = copyFileToDocumentTreeWithIntegrity(srcFile, relPath, destDoc)
            if (!copiedOk) {
                onProgress(TransferProgressState())
                return@withContext ProjectTransferResult(
                    success = false,
                    sharedFolderName = safeFolderName,
                    sharedTermuxPath = config.resolvedTermuxBasePath?.let { "$it/$categoryFolderName/$safeFolderName" },
                    transferredFiles = copiedCount,
                    failedReason = "Integrity verification failed while copying '$relPath'.",
                    recoveredFromInterruptedTransfer = wasInterrupted
                )
            }
            copiedCount++
        }

        // Clean up lock marker only after all files verified
        destDoc.findFile(TRANSFER_LOCK_NAME)?.delete()
        onProgress(
            TransferProgressState(
                active = false,
                phase = "Synced $copiedCount/$total files",
                copiedFiles = copiedCount,
                totalFiles = total,
                destinationPath = "$ROOT_FOLDER_NAME/$categoryFolderName/$safeFolderName"
            )
        )

        val posixTarget = config.resolvedTermuxBasePath?.let { "$it/$categoryFolderName/$safeFolderName" }
        ProjectTransferResult(
            success = true,
            sharedFolderName = safeFolderName,
            sharedTermuxPath = posixTarget,
            transferredFiles = copiedCount,
            recoveredFromInterruptedTransfer = wasInterrupted
        )
    }

    fun copyApkToSharedBuildOutputs(project: DevProject, verifiedApkFile: File): ApkVerificationResult? {
        if (!verifiedApkFile.exists() || !verifiedApkFile.isFile) return null
        val config = getConfig()
        val treeUriStr = config.treeUriString ?: return null
        val treeUri = runCatching { Uri.parse(treeUriStr) }.getOrNull() ?: return null
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val workspaceDoc = if (rootDoc.name.equals(ROOT_FOLDER_NAME, ignoreCase = true)) rootDoc else rootDoc.findFile(ROOT_FOLDER_NAME)
        val buildOutputsDoc = workspaceDoc?.findFile(SUB_BUILD_OUTPUTS)
            ?: workspaceDoc?.createDirectory(SUB_BUILD_OUTPUTS)
            ?: return null
        val safeName = DevSandboxGuard.sanitizeProjectSlug(project.name)
        val destDocName = "${safeName}_${verifiedApkFile.name}"
        buildOutputsDoc.findFile(destDocName)?.delete()
        val destFileDoc = buildOutputsDoc.createFile("application/vnd.android.package-archive", destDocName)
            ?: return null

        val sourceHash = sha256File(verifiedApkFile)
        context.contentResolver.openOutputStream(destFileDoc.uri, "wt")?.use { out ->
            verifiedApkFile.inputStream().use { input -> input.copyTo(out) }
        } ?: return null

        val copiedHash = context.contentResolver.openInputStream(destFileDoc.uri)?.use { sha256Stream(it) }
        if (copiedHash != sourceHash) return null
        return BuildAndRunEngine.verifyApkWithMetadata(verifiedApkFile, context)
    }

    /**
     * Checks both shared Termux working directory (if readable as a File) and SAF BuildOutputs
     * for a built APK, copies it safely into internal project cache for PackageInstaller & UI metadata.
     */
    fun collectApkFromSharedOrPosixWorkspace(
        project: DevProject,
        internalProjectRoot: File,
        sharedPosixProjectPath: String?
    ): File? {
        if (!sharedPosixProjectPath.isNullOrBlank()) {
            val sharedDir = File(sharedPosixProjectPath)
            val foundPosix = BuildAndRunEngine.findBuiltDebugApk(sharedDir)
            if (foundPosix != null && foundPosix.canRead()) {
                val destInternal = File(internalProjectRoot, "app/build/outputs/apk/debug/${foundPosix.name}")
                if (copyLocalFileVerified(foundPosix, destInternal)) {
                    copyApkToSharedBuildOutputs(project, destInternal)
                    return destInternal
                }
            }
        }

        // Also check SAF tree in case Android Scoped Storage blocked direct File() read
        val config = getConfig()
        val treeUri = config.treeUriString?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val workspaceDoc = if (rootDoc.name.equals(ROOT_FOLDER_NAME, ignoreCase = true)) rootDoc else rootDoc.findFile(ROOT_FOLDER_NAME)
        val safeName = DevSandboxGuard.sanitizeProjectSlug(project.name)
        val sharedProjDoc = workspaceDoc?.findFile(SUB_ANDROID)?.findFile(safeName) ?: return null
        val apkDoc = findApkInDocumentTree(sharedProjDoc) ?: return null

        val destInternal = File(internalProjectRoot, "app/build/outputs/apk/debug/${apkDoc.name ?: "app-debug.apk"}")
        destInternal.parentFile?.mkdirs()
        context.contentResolver.openInputStream(apkDoc.uri)?.use { input ->
            destInternal.outputStream().use { out -> input.copyTo(out) }
        } ?: return null
        if (BuildAndRunEngine.verifyApkIntegrity(destInternal)) {
            copyApkToSharedBuildOutputs(project, destInternal)
            return destInternal
        }
        return null
    }

    private fun copyFileToDocumentTreeWithIntegrity(
        srcFile: File,
        relativePath: String,
        rootDestDoc: DocumentFile
    ): Boolean {
        return try {
            val segments = relativePath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return false
            var currentDir = rootDestDoc
            for (i in 0 until segments.lastIndex) {
                val dirName = segments[i]
                currentDir = currentDir.findFile(dirName)?.takeIf { it.isDirectory }
                    ?: currentDir.createDirectory(dirName)
                    ?: return false
            }
            val fileName = segments.last()
            currentDir.findFile(fileName)?.delete()
            val targetDoc = currentDir.createFile(guessMimeType(fileName), fileName) ?: return false

            val expectedHash = sha256File(srcFile)
            val expectedLen = srcFile.length()

            context.contentResolver.openOutputStream(targetDoc.uri, "wt")?.use { out ->
                srcFile.inputStream().use { input -> input.copyTo(out) }
            } ?: return false

            val actualHash = context.contentResolver.openInputStream(targetDoc.uri)?.use { input ->
                sha256Stream(input)
            }
            actualHash == expectedHash && targetDoc.length() == expectedLen
        } catch (_: Exception) {
            false
        }
    }

    private fun findApkInDocumentTree(dir: DocumentFile): DocumentFile? {
        val buildApkDir = dir.findFile("app")
            ?.findFile("build")
            ?.findFile("outputs")
            ?.findFile("apk")
            ?.findFile("debug")
        if (buildApkDir != null && buildApkDir.isDirectory) {
            buildApkDir.listFiles().firstOrNull {
                it.isFile && it.name.orEmpty().endsWith(".apk", ignoreCase = true)
            }?.let { return it }
        }
        return null
    }

    private fun hasPersistedUriPermission(uri: Uri): Boolean {
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    private fun derivePosixPathFromTreeUri(treeUri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            when {
                docId.startsWith("primary:") -> {
                    val sub = docId.removePrefix("primary:").trim('/')
                    if (sub.isBlank()) "/storage/emulated/0" else "/storage/emulated/0/$sub"
                }
                docId == "primary" -> "/storage/emulated/0"
                docId.startsWith("home:") -> {
                    val sub = docId.removePrefix("home:").trim('/')
                    if (sub.isBlank()) "/storage/emulated/0/Documents" else "/storage/emulated/0/Documents/$sub"
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun guessMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "kt", "kts", "java", "gradle", "properties", "md", "txt", "pro" -> "text/plain"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val PREFS_NAME = "nova_devstudio_shared_prefs"
        private const val KEY_TREE_URI = "shared_workspace_tree_uri"
        private const val KEY_FOLDERS_INIT = "shared_workspace_folders_init"
        private const val KEY_CUSTOM_TERMUX_BASE = "shared_workspace_custom_posix"

        const val ROOT_FOLDER_NAME = "NOVA_AI_Projects"
        const val SUB_ANDROID = "AndroidProjects"
        const val SUB_WEBSITES = "Websites"
        const val SUB_IMPORTED = "ImportedProjects"
        const val SUB_BUILD_OUTPUTS = "BuildOutputs"
        const val SUB_TEMP = "TemporaryFiles"
        const val TRANSFER_LOCK_NAME = ".nova_transfer.lock"

        val REQUIRED_SUBFOLDERS = listOf(
            SUB_ANDROID,
            SUB_WEBSITES,
            SUB_IMPORTED,
            SUB_BUILD_OUTPUTS,
            SUB_TEMP
        )

        fun collectTransferableFiles(sourceRoot: File): List<File> {
            if (!sourceRoot.exists() || !sourceRoot.isDirectory) return emptyList()
            return sourceRoot.walkTopDown()
                .onEnter { dir -> dir.name !in setOf(".nova_backups", ".git", ".gradle", "build") }
                .filter { it.isFile && it.name != TRANSFER_LOCK_NAME }
                .toList()
        }

        /**
         * Pure filesystem verified folder transfer engine (used both by tests and direct POSIX sync).
         * Supports overwrite protection, lock marker recovery, and SHA-256 verification.
         */
        fun transferDirectoryWithVerification(
            sourceRoot: File,
            destRoot: File,
            overwriteConfirmed: Boolean,
            onProgress: ((copied: Int, total: Int) -> Unit)? = null
        ): ProjectTransferResult {
            val files = collectTransferableFiles(sourceRoot)
            if (files.isEmpty()) {
                return ProjectTransferResult(
                    success = false,
                    sharedFolderName = destRoot.name,
                    sharedTermuxPath = destRoot.absolutePath,
                    failedReason = "Source workspace has no transferable files."
                )
            }

            val lockFile = File(destRoot, TRANSFER_LOCK_NAME)
            val wasInterrupted = lockFile.exists()
            if (destRoot.exists() && !overwriteConfirmed && !wasInterrupted) {
                return ProjectTransferResult(
                    success = false,
                    requiresOverwriteConfirmation = true,
                    sharedFolderName = destRoot.name,
                    sharedTermuxPath = destRoot.absolutePath,
                    failedReason = "Destination folder already exists."
                )
            }

            if (!destRoot.exists()) destRoot.mkdirs()
            lockFile.writeText("transfer_started=${System.currentTimeMillis()}")

            var copied = 0
            for (src in files) {
                val rel = sourceRoot.toPath().relativize(src.toPath()).toString().replace('\\', '/')
                val dest = DevSandboxGuard.resolveSafeWorkspacePath(destRoot, rel)
                if (!copyLocalFileVerified(src, dest)) {
                    return ProjectTransferResult(
                        success = false,
                        sharedFolderName = destRoot.name,
                        sharedTermuxPath = destRoot.absolutePath,
                        transferredFiles = copied,
                        failedReason = "SHA-256 verification failed for $rel",
                        recoveredFromInterruptedTransfer = wasInterrupted
                    )
                }
                copied++
                onProgress?.invoke(copied, files.size)
            }

            lockFile.delete()
            return ProjectTransferResult(
                success = true,
                sharedFolderName = destRoot.name,
                sharedTermuxPath = destRoot.absolutePath,
                transferredFiles = copied,
                recoveredFromInterruptedTransfer = wasInterrupted
            )
        }

        fun copyLocalFileVerified(src: File, dest: File): Boolean {
            return try {
                dest.parentFile?.mkdirs()
                val expectedHash = sha256File(src)
                src.inputStream().use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
                val actualHash = sha256File(dest)
                dest.length() == src.length() && expectedHash == actualHash
            } catch (_: Exception) {
                false
            }
        }

        fun sha256File(file: File): String =
            file.inputStream().use { sha256Stream(it) }

        fun sha256Stream(input: java.io.InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(8192)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                digest.update(buf, 0, r)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
