package com.arponzxc.novaai.devstudio.workspace

import com.arponzxc.novaai.devstudio.model.CodeDiffEntry
import com.arponzxc.novaai.devstudio.model.DevChangeProposal
import com.arponzxc.novaai.devstudio.model.DevFileNode
import com.arponzxc.novaai.devstudio.model.DevProject
import com.arponzxc.novaai.devstudio.model.DiffAction
import com.arponzxc.novaai.devstudio.model.ProjectBuildStatus
import com.arponzxc.novaai.devstudio.model.ProjectRunStatus
import com.arponzxc.novaai.devstudio.model.ProjectType
import com.arponzxc.novaai.devstudio.security.DevSandboxGuard
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DevWorkspaceManager(private val baseFilesDir: File) {

    val workspaceRoot: File = File(baseFilesDir, WORKSPACE_DIR_NAME).apply {
        if (!exists()) mkdirs()
    }
    private val indexFile: File = File(workspaceRoot, INDEX_FILE_NAME)

    @Synchronized
    fun listProjects(): List<DevProject> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val array = JSONArray(indexFile.readText())
            val list = mutableListOf<DevProject>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                parseProject(obj)?.let { list += it }
            }
            list.sortedByDescending { it.updatedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getProject(projectId: String): DevProject? = listProjects().firstOrNull { it.id == projectId }

    @Synchronized
    fun createProject(
        name: String,
        packageName: String,
        type: ProjectType,
        entryPoint: String,
        description: String = "",
        files: Map<String, String> = emptyMap(),
        generatedByAi: Boolean = false
    ): DevProject {
        val slug = DevSandboxGuard.sanitizeProjectSlug(name)
        val uniqueId = "${slug}_${UUID.randomUUID().toString().take(6)}"
        val projectDir = DevSandboxGuard.resolveSafeWorkspacePath(workspaceRoot, uniqueId)
        if (!projectDir.exists()) projectDir.mkdirs()

        files.forEach { (relPath, content) ->
            val target = DevSandboxGuard.resolveSafeWorkspacePath(projectDir, relPath)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }

        val now = System.currentTimeMillis()
        val hasWrapper = com.arponzxc.novaai.devstudio.build.BuildAndRunEngine.hasGradleWrapper(projectDir)
        val project = DevProject(
            id = uniqueId,
            name = name.trim().ifBlank { "Untitled Project" },
            packageName = packageName.trim(),
            type = type,
            rootPath = projectDir.absolutePath,
            entryPoint = entryPoint,
            description = description.trim(),
            buildStatus = ProjectBuildStatus.NOT_BUILT,
            runStatus = if (type == ProjectType.HTML_WEB) ProjectRunStatus.READY_TO_RUN else ProjectRunStatus.NOT_BUILT,
            hasGradleWrapper = hasWrapper,
            generatedByAi = generatedByAi,
            createdAt = now,
            updatedAt = now
        )

        val current = listProjects().toMutableList()
        current.add(0, project)
        persistProjects(current)
        return project
    }

    @Synchronized
    fun updateProject(updated: DevProject): DevProject {
        val next = updated.copy(updatedAt = System.currentTimeMillis())
        val list = listProjects().toMutableList()
        val idx = list.indexOfFirst { it.id == next.id }
        if (idx >= 0) list[idx] = next else list.add(0, next)
        persistProjects(list)
        return next
    }

    @Synchronized
    fun updateProjectStatus(
        projectId: String,
        buildStatus: ProjectBuildStatus? = null,
        runStatus: ProjectRunStatus? = null,
        lastErrorSummary: String? = null,
        clearErrorSummary: Boolean = false,
        lastApkPath: String? = null,
        lastApkPackage: String? = null,
        lastApkVersion: String? = null,
        sharedWorkingDir: String? = null,
        hasGradleWrapper: Boolean? = null
    ): DevProject? {
        val existing = getProject(projectId) ?: return null
        val updated = existing.copy(
            buildStatus = buildStatus ?: existing.buildStatus,
            runStatus = runStatus ?: existing.runStatus,
            lastErrorSummary = if (clearErrorSummary) null else (lastErrorSummary ?: existing.lastErrorSummary),
            lastApkPath = lastApkPath ?: existing.lastApkPath,
            lastApkPackage = lastApkPackage ?: existing.lastApkPackage,
            lastApkVersion = lastApkVersion ?: existing.lastApkVersion,
            sharedWorkingDir = sharedWorkingDir ?: existing.sharedWorkingDir,
            hasGradleWrapper = hasGradleWrapper ?: existing.hasGradleWrapper,
            updatedAt = System.currentTimeMillis()
        )
        return updateProject(updated)
    }

    @Synchronized
    fun deleteProject(projectId: String): Boolean {
        val existing = getProject(projectId) ?: return false
        val root = File(existing.rootPath)
        val resolved = DevSandboxGuard.resolveSafeWorkspacePath(workspaceRoot, root.name)
        val deleted = if (resolved.exists()) resolved.deleteRecursively() else true
        val remaining = listProjects().filterNot { it.id == projectId }
        persistProjects(remaining)
        return deleted
    }

    fun getProjectRoot(projectId: String): File {
        val project = getProject(projectId) ?: throw IllegalArgumentException("Project not found: $projectId")
        return DevSandboxGuard.resolveSafeWorkspacePath(workspaceRoot, File(project.rootPath).name)
    }

    fun listFileTree(projectId: String, searchQuery: String = ""): List<DevFileNode> {
        val root = getProjectRoot(projectId)
        if (!root.exists()) return emptyList()
        val query = searchQuery.trim().lowercase(Locale.ROOT)
        val results = mutableListOf<DevFileNode>()

        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles()?.sortedWith(
                compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) })
            ) ?: return
            for (child in children) {
                if (child.name == BACKUP_DIR_NAME || child.name == ".git" || child.name == ".gradle") continue
                val rel = root.toPath().relativize(child.toPath()).toString().replace('\\', '/')
                val matches = query.isBlank() || rel.lowercase(Locale.ROOT).contains(query)
                if (matches) {
                    results += DevFileNode(
                        relativePath = rel,
                        name = child.name,
                        isDirectory = child.isDirectory,
                        sizeBytes = if (child.isDirectory) 0L else child.length(),
                        lastModified = child.lastModified(),
                        language = if (child.isDirectory) "folder" else detectLanguage(child.name),
                        depth = depth
                    )
                }
                if (child.isDirectory) {
                    walk(child, depth + 1)
                }
            }
        }
        walk(root, 0)
        return results
    }

    fun readFile(projectId: String, relativePath: String): String {
        val root = getProjectRoot(projectId)
        val file = DevSandboxGuard.resolveSafeWorkspacePath(root, relativePath)
        if (!file.exists() || file.isDirectory) {
            throw IllegalArgumentException("File does not exist: $relativePath")
        }
        return file.readText()
    }

    fun writeFile(
        projectId: String,
        relativePath: String,
        content: String,
        createBackup: Boolean = true
    ): File {
        val root = getProjectRoot(projectId)
        val file = DevSandboxGuard.resolveSafeWorkspacePath(root, relativePath)
        if (createBackup && file.exists() && file.isFile) {
            saveBackupSnapshot(root, relativePath, file.readText())
        }
        file.parentFile?.mkdirs()
        file.writeText(content)
        getProject(projectId)?.let { updateProject(it) }
        return file
    }

    fun createFile(projectId: String, relativePath: String, initialContent: String = ""): File {
        val root = getProjectRoot(projectId)
        val file = DevSandboxGuard.resolveSafeWorkspacePath(root, relativePath)
        if (file.exists()) throw IllegalStateException("File already exists: $relativePath")
        file.parentFile?.mkdirs()
        file.writeText(initialContent)
        getProject(projectId)?.let { updateProject(it) }
        return file
    }

    fun createDirectory(projectId: String, relativePath: String): File {
        val root = getProjectRoot(projectId)
        val dir = DevSandboxGuard.resolveSafeWorkspacePath(root, relativePath)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Could not create folder: $relativePath")
        }
        return dir
    }

    fun renameEntry(projectId: String, relativePath: String, newName: String): File {
        val cleanNewName = newName.trim()
        require(cleanNewName.isNotBlank() && !cleanNewName.contains('/') && !cleanNewName.contains('\\')) {
            "Invalid target name: $newName"
        }
        val root = getProjectRoot(projectId)
        val source = DevSandboxGuard.resolveSafeWorkspacePath(root, relativePath)
        if (!source.exists()) throw IllegalArgumentException("Source not found: $relativePath")
        val parentRel = File(relativePath).parent?.replace('\\', '/').orEmpty()
        val destRel = if (parentRel.isBlank()) cleanNewName else "$parentRel/$cleanNewName"
        val dest = DevSandboxGuard.resolveSafeWorkspacePath(root, destRel)
        if (dest.exists()) throw IllegalStateException("Destination already exists: $destRel")
        if (!source.renameTo(dest)) {
            throw IllegalStateException("Rename failed for $relativePath")
        }
        getProject(projectId)?.let { updateProject(it) }
        return dest
    }

    fun copyEntry(projectId: String, sourceRelPath: String, targetRelPath: String): File {
        val root = getProjectRoot(projectId)
        val source = DevSandboxGuard.resolveSafeWorkspacePath(root, sourceRelPath)
        val dest = DevSandboxGuard.resolveSafeWorkspacePath(root, targetRelPath)
        if (!source.exists()) throw IllegalArgumentException("Source not found: $sourceRelPath")
        if (dest.exists()) throw IllegalStateException("Destination already exists: $targetRelPath")
        dest.parentFile?.mkdirs()
        source.copyRecursively(dest, overwrite = false)
        getProject(projectId)?.let { updateProject(it) }
        return dest
    }

    fun moveEntry(projectId: String, sourceRelPath: String, targetRelPath: String): File {
        val copied = copyEntry(projectId, sourceRelPath, targetRelPath)
        deleteEntry(projectId, sourceRelPath)
        return copied
    }

    fun deleteEntry(projectId: String, relativePath: String): Boolean {
        val root = getProjectRoot(projectId)
        val target = DevSandboxGuard.resolveSafeWorkspacePath(root, relativePath)
        if (target == root.canonicalFile) {
            throw SecurityException("Cannot delete project root via deleteEntry; use deleteProject.")
        }
        if (target.isFile) {
            saveBackupSnapshot(root, relativePath, target.readText())
        }
        val ok = target.deleteRecursively()
        getProject(projectId)?.let { updateProject(it) }
        return ok
    }

    fun computeDiffEntry(
        relativePath: String,
        beforeContent: String,
        afterContent: String,
        action: DiffAction
    ): CodeDiffEntry {
        val beforeLines = if (beforeContent.isEmpty()) emptyList() else beforeContent.lines()
        val afterLines = if (afterContent.isEmpty()) emptyList() else afterContent.lines()
        val beforeSet = beforeLines.toSet()
        val afterSet = afterLines.toSet()
        val removed = beforeLines.count { it !in afterSet }
        val added = afterLines.count { it !in beforeSet }

        val diffBuilder = StringBuilder()
        diffBuilder.append("--- a/").append(relativePath).append('\n')
        diffBuilder.append("+++ b/").append(relativePath).append('\n')
        beforeLines.filter { it !in afterSet }.take(40).forEach {
            diffBuilder.append("- ").append(it).append('\n')
        }
        afterLines.filter { it !in beforeSet }.take(40).forEach {
            diffBuilder.append("+ ").append(it).append('\n')
        }

        val summary = when (action) {
            DiffAction.CREATE -> "Create $relativePath (+${afterLines.size} lines)"
            DiffAction.DELETE -> "Delete $relativePath (-${beforeLines.size} lines)"
            DiffAction.MODIFY -> "Modify $relativePath (+$added / -$removed lines)"
        }

        return CodeDiffEntry(
            relativePath = relativePath,
            action = action,
            beforeContent = beforeContent,
            afterContent = afterContent,
            summary = summary,
            unifiedDiff = diffBuilder.toString().trimEnd(),
            addedLines = added,
            removedLines = removed
        )
    }

    fun applyChangeProposal(projectId: String, proposal: DevChangeProposal): Int {
        val root = getProjectRoot(projectId)
        var applied = 0
        proposal.diffs.forEach { diff ->
            val target = DevSandboxGuard.resolveSafeWorkspacePath(root, diff.relativePath)
            when (diff.action) {
                DiffAction.CREATE, DiffAction.MODIFY -> {
                    if (target.exists() && target.isFile) {
                        saveBackupSnapshot(root, diff.relativePath, target.readText())
                    }
                    target.parentFile?.mkdirs()
                    target.writeText(diff.afterContent)
                    applied++
                }
                DiffAction.DELETE -> {
                    if (target.exists() && target.isFile) {
                        saveBackupSnapshot(root, diff.relativePath, target.readText())
                        target.delete()
                        applied++
                    }
                }
            }
        }
        getProject(projectId)?.let { updateProject(it) }
        return applied
    }

    fun listBackupsForFile(projectId: String, relativePath: String): List<File> {
        val root = getProjectRoot(projectId)
        val backupRoot = File(root, BACKUP_DIR_NAME)
        if (!backupRoot.exists()) return emptyList()
        val sanitizedKey = relativePath.replace('/', '_').replace('\\', '_')
        return backupRoot.listFiles()
            ?.filter { it.isFile && it.name.endsWith("__$sanitizedKey") }
            ?.sortedByDescending { it.name }
            .orEmpty()
    }

    fun revertLastBackup(projectId: String, relativePath: String): Boolean {
        val backups = listBackupsForFile(projectId, relativePath)
        val latest = backups.firstOrNull() ?: return false
        val root = getProjectRoot(projectId)
        val target = DevSandboxGuard.resolveSafeWorkspacePath(root, relativePath)
        target.parentFile?.mkdirs()
        target.writeText(latest.readText())
        latest.delete()
        getProject(projectId)?.let { updateProject(it) }
        return true
    }

    fun importProjectZip(
        zipStream: InputStream,
        suggestedName: String
    ): DevProject {
        val tempId = "import_${UUID.randomUUID().toString().take(8)}"
        val extractDir = DevSandboxGuard.resolveSafeWorkspacePath(workspaceRoot, tempId)
        extractDir.mkdirs()

        var entryCount = 0
        var totalBytes = 0L
        val buffer = ByteArray(8192)

        try {
            ZipInputStream(BufferedInputStream(zipStream)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    entryCount++
                    if (entryCount > DevSandboxGuard.MAX_ZIP_ENTRIES) {
                        throw SecurityException("ZIP exceeds maximum allowed entries (${DevSandboxGuard.MAX_ZIP_ENTRIES}).")
                    }
                    val target = DevSandboxGuard.validateZipEntryTarget(extractDir, entry.name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            while (true) {
                                val read = zis.read(buffer)
                                if (read <= 0) break
                                totalBytes += read
                                if (totalBytes > DevSandboxGuard.MAX_ZIP_UNCOMPRESSED_BYTES) {
                                    throw SecurityException("ZIP exceeds uncompressed size limit (50 MB).")
                                }
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            extractDir.deleteRecursively()
            throw e
        }

        // Unwrap single top-level directory if archive contained a wrapper folder
        unwrapSingleRootFolderIfNeeded(extractDir)

        val isAndroid = File(extractDir, "app/build.gradle.kts").exists() ||
            File(extractDir, "app/build.gradle").exists() ||
            File(extractDir, "AndroidManifest.xml").exists() ||
            File(extractDir, "app/src/main/AndroidManifest.xml").exists()
        val type = if (isAndroid) ProjectType.ANDROID_KOTLIN else ProjectType.HTML_WEB
        val entryPoint = when {
            type == ProjectType.ANDROID_KOTLIN && File(extractDir, "app/src/main/AndroidManifest.xml").exists() ->
                "app/src/main/AndroidManifest.xml"
            File(extractDir, "index.html").exists() -> "index.html"
            else -> extractDir.walkTopDown().firstOrNull { it.isFile }?.let {
                extractDir.toPath().relativize(it.toPath()).toString().replace('\\', '/')
            } ?: "index.html"
        }

        val slug = DevSandboxGuard.sanitizeProjectSlug(suggestedName)
        val projectId = "${slug}_${UUID.randomUUID().toString().take(6)}"
        val finalDir = DevSandboxGuard.resolveSafeWorkspacePath(workspaceRoot, projectId)
        if (!extractDir.renameTo(finalDir)) {
            extractDir.copyRecursively(finalDir, overwrite = true)
            extractDir.deleteRecursively()
        }

        val pkgName = detectPackageName(finalDir) ?: "com.novastudio.${DevSandboxGuard.sanitizeJavaPackageSegment(suggestedName)}"
        val now = System.currentTimeMillis()
        val project = DevProject(
            id = projectId,
            name = suggestedName.trim().ifBlank { "Imported Project" },
            packageName = pkgName,
            type = type,
            rootPath = finalDir.absolutePath,
            entryPoint = entryPoint,
            description = "Imported from ZIP archive",
            buildStatus = ProjectBuildStatus.NOT_BUILT,
            runStatus = if (type == ProjectType.HTML_WEB) ProjectRunStatus.READY_TO_RUN else ProjectRunStatus.NOT_BUILT,
            createdAt = now,
            updatedAt = now
        )
        val current = listProjects().toMutableList()
        current.add(0, project)
        persistProjects(current)
        return project
    }

    fun exportProjectToZip(projectId: String, outputStream: OutputStream): Int {
        val root = getProjectRoot(projectId)
        var count = 0
        ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
            root.walkTopDown().forEach { file ->
                val rel = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                if (rel.isBlank() || rel.startsWith(BACKUP_DIR_NAME) || rel.startsWith("build/") || rel.startsWith(".gradle")) {
                    return@forEach
                }
                if (file.isFile) {
                    zos.putNextEntry(ZipEntry(rel))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    count++
                }
            }
        }
        return count
    }

    private fun unwrapSingleRootFolderIfNeeded(dir: File) {
        val items = dir.listFiles() ?: return
        if (items.size == 1 && items[0].isDirectory) {
            val inner = items[0]
            val movedTemp = File(dir.parentFile, "${dir.name}_unwrap")
            inner.copyRecursively(movedTemp, overwrite = true)
            dir.deleteRecursively()
            movedTemp.renameTo(dir)
        }
    }

    private fun detectPackageName(root: File): String? {
        val gradle = File(root, "app/build.gradle.kts").takeIf { it.exists() }
            ?: File(root, "app/build.gradle").takeIf { it.exists() }
        if (gradle != null) {
            val match = Regex("applicationId\\s*[=]?\\s*[\"']([a-zA-Z0-9_.]+)[\"']").find(gradle.readText())
            if (match != null) return match.groupValues[1]
        }
        val manifest = File(root, "app/src/main/AndroidManifest.xml").takeIf { it.exists() }
        if (manifest != null) {
            val match = Regex("package\\s*=\\s*[\"']([a-zA-Z0-9_.]+)[\"']").find(manifest.readText())
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun saveBackupSnapshot(projectRoot: File, relativePath: String, content: String) {
        val backupDir = File(projectRoot, BACKUP_DIR_NAME).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val key = relativePath.replace('/', '_').replace('\\', '_')
        File(backupDir, "${stamp}__$key").writeText(content)

        // Keep max 20 snapshots per file
        val matches = backupDir.listFiles()
            ?.filter { it.name.endsWith("__$key") }
            ?.sortedByDescending { it.name }
            .orEmpty()
        matches.drop(20).forEach { it.delete() }
    }

    fun computeProjectFingerprint(projectId: String): Long {
        val root = runCatching { getProjectRoot(projectId) }.getOrNull() ?: return 0L
        if (!root.exists()) return 0L
        var count = 0L
        var latest = 0L
        root.walkTopDown()
            .onEnter { it.name !in setOf(BACKUP_DIR_NAME, ".git", ".gradle", "build") }
            .filter { it.isFile }
            .forEach { f ->
                count++
                val ts = f.lastModified()
                if (ts > latest) latest = ts
            }
        return latest xor (count shl 16)
    }

    private fun persistProjects(projects: List<DevProject>) {
        val array = JSONArray()
        projects.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("packageName", p.packageName)
                put("type", p.type.name)
                put("rootPath", p.rootPath)
                put("entryPoint", p.entryPoint)
                put("description", p.description)
                put("buildStatus", p.buildStatus.name)
                put("runStatus", p.runStatus.name)
                put("lastErrorSummary", p.lastErrorSummary ?: "")
                put("lastApkPath", p.lastApkPath ?: "")
                put("lastApkPackage", p.lastApkPackage ?: "")
                put("lastApkVersion", p.lastApkVersion ?: "")
                put("sharedWorkingDir", p.sharedWorkingDir ?: "")
                put("hasGradleWrapper", p.hasGradleWrapper)
                put("generatedByAi", p.generatedByAi)
                put("createdAt", p.createdAt)
                put("updatedAt", p.updatedAt)
            })
        }
        indexFile.writeText(array.toString(2))
    }

    private fun parseProject(obj: JSONObject): DevProject? {
        val id = obj.optString("id", "").ifBlank { return null }
        val type = runCatching { ProjectType.valueOf(obj.optString("type")) }.getOrDefault(ProjectType.HTML_WEB)
        val buildStatus = runCatching { ProjectBuildStatus.valueOf(obj.optString("buildStatus")) }
            .getOrDefault(ProjectBuildStatus.NOT_BUILT)
        val runStatus = runCatching { ProjectRunStatus.valueOf(obj.optString("runStatus")) }
            .getOrDefault(ProjectRunStatus.NOT_BUILT)
        return DevProject(
            id = id,
            name = obj.optString("name", id),
            packageName = obj.optString("packageName", ""),
            type = type,
            rootPath = obj.optString("rootPath", File(workspaceRoot, id).absolutePath),
            entryPoint = obj.optString("entryPoint", "index.html"),
            description = obj.optString("description", ""),
            buildStatus = buildStatus,
            runStatus = runStatus,
            lastErrorSummary = obj.optString("lastErrorSummary", "").ifBlank { null },
            lastApkPath = obj.optString("lastApkPath", "").ifBlank { null },
            lastApkPackage = obj.optString("lastApkPackage", "").ifBlank { null },
            lastApkVersion = obj.optString("lastApkVersion", "").ifBlank { null },
            sharedWorkingDir = obj.optString("sharedWorkingDir", "").ifBlank { null },
            hasGradleWrapper = obj.optBoolean("hasGradleWrapper", false),
            generatedByAi = obj.optBoolean("generatedByAi", false),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
        )
    }

    private fun detectLanguage(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "xml" -> "xml"
            "html", "htm" -> "html"
            "css" -> "css"
            "js", "mjs" -> "javascript"
            "json" -> "json"
            "md" -> "markdown"
            "properties", "gradle" -> "gradle"
            "svg" -> "svg"
            else -> "text"
        }
    }

    companion object {
        private const val WORKSPACE_DIR_NAME = "nova_dev_workspace"
        private const val INDEX_FILE_NAME = "projects_index.json"
        private const val BACKUP_DIR_NAME = ".nova_backups"
    }
}
