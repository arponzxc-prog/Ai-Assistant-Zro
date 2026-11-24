package com.arponzxc.novaai.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Pure SAF security & boundary validator + Intelligent File operations
 * (subfolder browsing, Copy, Move with confirmation, File Info, Favorites & Recent pruning,
 * and inside-file text search) strictly scoped to user-authorized SAF trees.
 */
object SafBoundaryGuard {
    private val HIDDEN_OR_UNSAFE = Regex("^(?:\\..*|.*(?:\\.\\.|:|/data/data|/data/user).*)$")

    fun isSafeDisplayName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.startsWith(".")) return false
        if (trimmed.contains("..") || trimmed.contains('/') || trimmed.contains('\\')) return false
        return true
    }

    fun isBlockedInternalOrHiddenUri(uriString: String): Boolean {
        val clean = uriString.trim().lowercase(Locale.ROOT)
        if (clean.isBlank()) return true
        if (clean.startsWith("file://")) return true
        if (clean.contains("/data/data/") || clean.contains("/data/user/")) return true
        return false
    }

    fun isUriWithinAuthorizedRoots(uriString: String, authorizedRootUris: List<String>): Boolean {
        if (isBlockedInternalOrHiddenUri(uriString)) return false
        if (authorizedRootUris.isEmpty()) return false
        val targetUri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        val targetAuth = targetUri.authority.orEmpty()
        val targetSegs = targetUri.pathSegments
        return authorizedRootUris.any { rootStr ->
            val rootUri = runCatching { Uri.parse(rootStr) }.getOrNull() ?: return@any false
            if (targetAuth != rootUri.authority.orEmpty()) return@any false
            val treeId = rootUri.pathSegments.getOrNull(1).orEmpty()
            val decodedDocPath = targetUri.lastPathSegment.orEmpty()
            treeId.isNotBlank() && (
                uriString == rootStr ||
                    decodedDocPath == treeId ||
                    decodedDocPath.startsWith("$treeId/") ||
                    targetSegs.contains(treeId)
                )
        }
    }
}

class SafIntelligentFileService(
    private val context: Context,
    private val safFileManager: SafFileManager
) {
    companion object {
        private const val TAG = "SafIntelligentFiles"
        private const val PREF_NAME = "nova_intelligent_file_prefs"
        private const val KEY_FAVORITES = "favorites_entries_json"
        private const val KEY_RECENT = "recent_entries_json"
        private const val MAX_RECENT = 20
        private const val MAX_SEARCH_READ_BYTES = 96 * 1024
        private val TEXT_SEARCH_EXTS = setOf(
            "txt", "md", "markdown", "json", "xml", "html", "htm", "css", "js", "ts",
            "kt", "java", "py", "csv", "log", "yaml", "yml", "gradle", "kts", "sql"
        )
    }

    private val gson = Gson()
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    suspend fun listDirectoryUri(
        dirUriString: String,
        parentLabel: String
    ): StorageActionResult = withContext(Dispatchers.IO) {
        if (SafBoundaryGuard.isBlockedInternalOrHiddenUri(dirUriString)) {
            return@withContext StorageActionResult(
                success = false,
                message = "Access blocked: URI is outside user-authorized SAF storage."
            )
        }
        val uri = runCatching { Uri.parse(dirUriString) }.getOrNull()
            ?: return@withContext StorageActionResult(false, "Invalid folder URI.")

        val doc = DocumentFile.fromTreeUri(context, uri)
            ?: DocumentFile.fromSingleUri(context, uri)
            ?: return@withContext StorageActionResult(false, "Authorized folder could not be opened.")

        if (!doc.exists() || !doc.canRead()) {
            return@withContext StorageActionResult(false, "Folder cannot be read or permission was revoked.")
        }

        val items = doc.listFiles().mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            if (!SafBoundaryGuard.isSafeDisplayName(name)) return@mapNotNull null
            StorageItemEntry(
                name = name,
                uriString = child.uri.toString(),
                isDirectory = child.isDirectory,
                sizeBytes = child.length(),
                lastModifiedMs = child.lastModified(),
                mimeType = child.type,
                parentFolderLabel = doc.name ?: parentLabel
            )
        }.sortedWith(compareBy<StorageItemEntry> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })

        StorageActionResult(
            success = true,
            message = "Listed ${items.size} item(s) in ${doc.name ?: parentLabel}.",
            folderLabel = doc.name ?: parentLabel,
            items = items
        )
    }

    suspend fun inspectFileDetails(uriString: String, parentLabel: String = "Authorized Folder"): FileDetailsInfo? =
        withContext(Dispatchers.IO) {
            if (SafBoundaryGuard.isBlockedInternalOrHiddenUri(uriString)) return@withContext null
            val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext null
            val doc = DocumentFile.fromSingleUri(context, uri)
                ?: DocumentFile.fromTreeUri(context, uri)
                ?: return@withContext null
            if (!doc.exists()) return@withContext null
            val name = doc.name ?: "Unknown"
            val isFav = getFavorites(pruneInaccessible = false).any { it.uriString == uriString }
            FileDetailsInfo(
                name = name,
                uriString = uriString,
                isDirectory = doc.isDirectory,
                mimeType = doc.type ?: SafFileType.resolveMimeType(name, null),
                sizeBytes = doc.length(),
                lastModifiedMs = doc.lastModified(),
                canRead = doc.canRead(),
                canWrite = doc.canWrite(),
                parentFolderLabel = parentLabel,
                isFavorite = isFav
            )
        }

    suspend fun copyFileToFolder(
        sourceUriString: String,
        targetFolderUriString: String
    ): StorageActionResult = withContext(Dispatchers.IO) {
        if (SafBoundaryGuard.isBlockedInternalOrHiddenUri(sourceUriString) ||
            SafBoundaryGuard.isBlockedInternalOrHiddenUri(targetFolderUriString)
        ) {
            return@withContext StorageActionResult(false, "Copy blocked outside authorized SAF folders.")
        }
        val srcUri = runCatching { Uri.parse(sourceUriString) }.getOrNull()
            ?: return@withContext StorageActionResult(false, "Invalid source URI.")
        val dstTreeUri = runCatching { Uri.parse(targetFolderUriString) }.getOrNull()
            ?: return@withContext StorageActionResult(false, "Invalid destination folder URI.")

        val srcDoc = DocumentFile.fromSingleUri(context, srcUri)
            ?: return@withContext StorageActionResult(false, "Source file cannot be accessed.")
        if (srcDoc.isDirectory) {
            return@withContext StorageActionResult(false, "Copying entire directories is disabled for safety; select individual files.")
        }
        val dstDir = DocumentFile.fromTreeUri(context, dstTreeUri)
            ?: return@withContext StorageActionResult(false, "Destination folder cannot be opened.")
        if (!dstDir.canWrite()) {
            return@withContext StorageActionResult(false, "Destination folder \"${dstDir.name}\" does not allow writing.", requiresPermission = true)
        }

        val baseName = (srcDoc.name ?: "copied_file.txt").takeIf { SafBoundaryGuard.isSafeDisplayName(it) } ?: "copied_file.txt"
        val destName = resolveNonConflictingName(dstDir, baseName)
        val mime = srcDoc.type ?: SafFileType.resolveMimeType(destName, null)
        val created = dstDir.createFile(mime, destName)
            ?: return@withContext StorageActionResult(false, "Could not create destination file \"$destName\".")

        val copiedBytes = try {
            context.contentResolver.openInputStream(srcUri)?.use { input ->
                context.contentResolver.openOutputStream(created.uri, "wt")?.use { output ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        output.write(buf, 0, r)
                        total += r
                    }
                    output.flush()
                    total
                }
            } ?: -1L
        } catch (e: Exception) {
            Log.e(TAG, "Copy failed: ${e.message}", e)
            -1L
        }

        if (copiedBytes < 0L) {
            runCatching { created.delete() }
            return@withContext StorageActionResult(false, "Failed copying \"$baseName\" into ${dstDir.name ?: "folder"}.")
        }

        StorageActionResult(
            success = true,
            message = "Copied \"$baseName\" as \"$destName\" inside ${dstDir.name ?: "folder"}.",
            itemUri = created.uri.toString(),
            resolvedName = destName,
            folderLabel = dstDir.name ?: "Folder"
        )
    }

    suspend fun moveFileToFolder(
        sourceUriString: String,
        targetFolderUriString: String,
        confirmed: Boolean
    ): StorageActionResult = withContext(Dispatchers.IO) {
        if (!confirmed) {
            return@withContext StorageActionResult(
                success = false,
                message = "Explicit user confirmation is required before moving a file.",
                requiresConfirmation = true
            )
        }
        val copyRes = copyFileToFolder(sourceUriString, targetFolderUriString)
        if (!copyRes.success) return@withContext copyRes

        val srcUri = runCatching { Uri.parse(sourceUriString) }.getOrNull()
        val srcDoc = srcUri?.let { DocumentFile.fromSingleUri(context, it) }
        val deletedOld = srcDoc?.delete() == true
        if (deletedOld) {
            StorageActionResult(
                success = true,
                message = "Moved \"${copyRes.resolvedName}\" to ${copyRes.folderLabel}.",
                itemUri = copyRes.itemUri,
                resolvedName = copyRes.resolvedName,
                folderLabel = copyRes.folderLabel
            )
        } else {
            StorageActionResult(
                success = false,
                message = "Copied to ${copyRes.folderLabel}, but Android prevented deleting the original source file.",
                itemUri = copyRes.itemUri,
                resolvedName = copyRes.resolvedName,
                folderLabel = copyRes.folderLabel
            )
        }
    }

    suspend fun searchInsideFiles(
        query: String,
        maxFiles: Int = 35
    ): List<ContentSearchMatch> = withContext(Dispatchers.IO) {
        val cleanQ = query.trim()
        if (cleanQ.isBlank()) return@withContext emptyList()
        val folders = safFileManager.getAuthorizedFolders()
        val results = mutableListOf<ContentSearchMatch>()
        var scanned = 0

        for (folder in folders) {
            val dir = DocumentFile.fromTreeUri(context, Uri.parse(folder.uriString)) ?: continue
            for (child in dir.listFiles()) {
                if (scanned >= maxFiles) break
                if (child.isDirectory) continue
                val name = child.name ?: continue
                if (!SafBoundaryGuard.isSafeDisplayName(name)) continue
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext !in TEXT_SEARCH_EXTS) continue
                scanned++
                val text = readLimitedUtf8(child.uri)
                if (text.isBlank()) continue
                val lines = text.lines()
                for ((idx, line) in lines.withIndex()) {
                    if (line.contains(cleanQ, ignoreCase = true)) {
                        results.add(
                            ContentSearchMatch(
                                entry = StorageItemEntry(
                                    name = name,
                                    uriString = child.uri.toString(),
                                    isDirectory = false,
                                    sizeBytes = child.length(),
                                    lastModifiedMs = child.lastModified(),
                                    mimeType = child.type,
                                    parentFolderLabel = dir.name ?: folder.displayName
                                ),
                                lineNumber = idx + 1,
                                excerpt = line.trim().take(140)
                            )
                        )
                        break
                    }
                }
            }
        }
        results
    }

    fun toggleFavorite(entry: StorageItemEntry): Boolean {
        val current = loadList(KEY_FAVORITES).toMutableList()
        val exists = current.any { it.uriString == entry.uriString }
        val updated = if (exists) {
            current.filterNot { it.uriString == entry.uriString }
        } else {
            listOf(entry) + current
        }
        prefs.edit().putString(KEY_FAVORITES, gson.toJson(updated)).apply()
        return !exists
    }

    fun isFavorite(uriString: String): Boolean {
        return loadList(KEY_FAVORITES).any { it.uriString == uriString }
    }

    fun getFavorites(pruneInaccessible: Boolean = true): List<StorageItemEntry> {
        val raw = loadList(KEY_FAVORITES)
        if (!pruneInaccessible) return raw
        val pruned = pruneInaccessibleEntries(raw)
        if (pruned.size != raw.size) {
            prefs.edit().putString(KEY_FAVORITES, gson.toJson(pruned)).apply()
        }
        return pruned
    }

    fun recordRecentFile(entry: StorageItemEntry) {
        if (entry.isDirectory || SafBoundaryGuard.isBlockedInternalOrHiddenUri(entry.uriString)) return
        val updated = (listOf(entry) + loadList(KEY_RECENT).filterNot { it.uriString == entry.uriString })
            .take(MAX_RECENT)
        prefs.edit().putString(KEY_RECENT, gson.toJson(updated)).apply()
    }

    fun getRecentFiles(pruneInaccessible: Boolean = true): List<StorageItemEntry> {
        val raw = loadList(KEY_RECENT)
        if (!pruneInaccessible) return raw
        val pruned = pruneInaccessibleEntries(raw)
        if (pruned.size != raw.size) {
            prefs.edit().putString(KEY_RECENT, gson.toJson(pruned)).apply()
        }
        return pruned
    }

    fun pruneInaccessibleEntries(entries: List<StorageItemEntry>): List<StorageItemEntry> {
        return entries.filter { item ->
            if (SafBoundaryGuard.isBlockedInternalOrHiddenUri(item.uriString)) return@filter false
            val uri = runCatching { Uri.parse(item.uriString) }.getOrNull() ?: return@filter false
            val doc = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull() ?: return@filter false
            doc.exists() && doc.canRead()
        }
    }

    private fun resolveNonConflictingName(dir: DocumentFile, desired: String): String {
        if (dir.findFile(desired) == null) return desired
        val dotIdx = desired.lastIndexOf('.')
        val stem = if (dotIdx > 0) desired.substring(0, dotIdx) else desired
        val ext = if (dotIdx > 0) desired.substring(dotIdx) else ""
        for (i in 1..99) {
            val candidate = "${stem}_copy$i$ext"
            if (dir.findFile(candidate) == null) return candidate
        }
        return "${stem}_${System.currentTimeMillis()}$ext"
    }

    private fun readLimitedUtf8(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                var total = 0
                while (total < MAX_SEARCH_READ_BYTES) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    val len = minOf(r, MAX_SEARCH_READ_BYTES - total)
                    out.write(buf, 0, len)
                    total += len
                }
                String(out.toByteArray(), StandardCharsets.UTF_8)
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun loadList(key: String): List<StorageItemEntry> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<StorageItemEntry>>() {}.type
            gson.fromJson<List<StorageItemEntry>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
