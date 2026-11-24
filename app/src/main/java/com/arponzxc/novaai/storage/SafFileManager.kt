package com.arponzxc.novaai.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.arponzxc.novaai.data.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

class SafFileManager(
    private val context: Context,
    private val preferences: AppPreferences
) {
    companion object {
        private const val TAG = "SafFileManager"
        private const val MAX_PREVIEW_BYTES = 128 * 1024
        private val INVALID_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|\\r\\n\\t]")
    }

    private val gson = Gson()

    fun persistTreePermission(treeUri: Uri, aliasHint: String = ""): AuthorizedStorageFolder? {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        } catch (e: SecurityException) {
            Log.w(TAG, "Full RW persist failed, trying read-only fallback: ${e.message}")
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (inner: SecurityException) {
                Log.e(TAG, "Failed to persist tree URI permission: ${inner.message}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error taking persistable URI permission: ${e.message}", e)
            return null
        }

        val docTree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val inferredName = docTree.name?.trim().takeUnless { it.isNullOrBlank() }
            ?: inferDisplayNameFromUri(treeUri)
        val rootAlias = normalizeFolderAlias(aliasHint.ifBlank { inferredName })
        val metaMap = loadMetaMap()
        metaMap[treeUri.toString()] = StoredFolderMeta(
            displayName = inferredName,
            rootAlias = rootAlias,
            grantedAtMs = System.currentTimeMillis()
        )
        saveMetaMap(metaMap)

        return AuthorizedStorageFolder(
            uriString = treeUri.toString(),
            displayName = inferredName,
            rootAlias = rootAlias,
            canRead = docTree.canRead(),
            canWrite = docTree.canWrite(),
            grantedAtMs = System.currentTimeMillis()
        )
    }

    fun revokeFolderPermission(uriString: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }
        val metaMap = loadMetaMap()
        metaMap.remove(uriString)
        saveMetaMap(metaMap)
        return true
    }

    fun getAuthorizedFolders(): List<AuthorizedStorageFolder> {
        val persisted = context.contentResolver.persistedUriPermissions
        val metaMap = loadMetaMap()
        val result = mutableListOf<AuthorizedStorageFolder>()
        val activeUris = mutableSetOf<String>()

        for (perm in persisted) {
            if (!perm.isReadPermission && !perm.isWritePermission) continue
            val uriStr = perm.uri.toString()
            val doc = runCatching { DocumentFile.fromTreeUri(context, perm.uri) }.getOrNull() ?: continue
            if (!doc.exists() || !doc.canRead()) continue
            activeUris.add(uriStr)
            val meta = metaMap[uriStr]
            val displayName = meta?.displayName?.takeIf { it.isNotBlank() }
                ?: doc.name?.takeIf { it.isNotBlank() }
                ?: inferDisplayNameFromUri(perm.uri)
            val alias = meta?.rootAlias?.takeIf { it.isNotBlank() }
                ?: normalizeFolderAlias(displayName)
            result.add(
                AuthorizedStorageFolder(
                    uriString = uriStr,
                    displayName = displayName,
                    rootAlias = alias,
                    canRead = doc.canRead(),
                    canWrite = doc.canWrite() && perm.isWritePermission,
                    grantedAtMs = meta?.grantedAtMs ?: perm.persistedTime
                )
            )
        }

        // Prune stale metadata entries that were revoked externally
        val staleKeys = metaMap.keys.filter { it !in activeUris }
        if (staleKeys.isNotEmpty()) {
            staleKeys.forEach { metaMap.remove(it) }
            saveMetaMap(metaMap)
        }

        return result.sortedByDescending { it.grantedAtMs }
    }

    fun resolveFolderDocument(folderHint: String): Pair<AuthorizedStorageFolder, DocumentFile>? {
        val folders = getAuthorizedFolders()
        if (folders.isEmpty()) return null

        val cleanHint = folderHint.trim()
        if (cleanHint.isBlank()) {
            val firstWritable = folders.firstOrNull { it.canWrite } ?: folders.first()
            val doc = DocumentFile.fromTreeUri(context, Uri.parse(firstWritable.uriString)) ?: return null
            return firstWritable to doc
        }

        val normalizedHint = normalizeFolderAlias(cleanHint)
        val segments = cleanHint.replace('\\', '/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // Match first segment or whole hint against authorized roots
        val matchedRoot = folders.firstOrNull { folder ->
            val alias = folder.rootAlias.lowercase(Locale.ROOT)
            val disp = folder.displayName.lowercase(Locale.ROOT)
            alias == normalizedHint ||
                disp == cleanHint.lowercase(Locale.ROOT) ||
                (segments.isNotEmpty() && (
                    alias == normalizeFolderAlias(segments.first()) ||
                        disp.equals(segments.first(), ignoreCase = true)
                    )) ||
                disp.contains(cleanHint, ignoreCase = true) ||
                cleanHint.contains(disp, ignoreCase = true)
        }

        if (matchedRoot != null) {
            var currentDoc = DocumentFile.fromTreeUri(context, Uri.parse(matchedRoot.uriString)) ?: return null
            if (segments.size > 1) {
                for (subSegment in segments.drop(1)) {
                    val child = findChildCaseInsensitive(currentDoc, subSegment)
                    if (child != null && child.isDirectory) {
                        currentDoc = child
                    }
                }
            }
            return matchedRoot to currentDoc
        }

        // Also check if a subfolder directly inside an authorized folder matches the hint
        for (folder in folders) {
            val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(folder.uriString)) ?: continue
            val directChild = findChildCaseInsensitive(rootDoc, cleanHint)
            if (directChild != null && directChild.isDirectory) {
                return folder to directChild
            }
        }

        // When user didn't pick the requested folder (e.g. asked for Download or Documents but only authorized another root)
        if (cleanHint.lowercase(Locale.ROOT) in setOf("download", "downloads", "documents", "document", "music", "pictures", "dcim")) {
            return null
        }

        val fallback = folders.firstOrNull { it.canWrite } ?: folders.first()
        val fallbackDoc = DocumentFile.fromTreeUri(context, Uri.parse(fallback.uriString)) ?: return null
        return fallback to fallbackDoc
    }

    suspend fun createFolder(
        folderName: String,
        parentFolderHint: String = ""
    ): StorageActionResult = withContext(Dispatchers.IO) {
        val sanitized = sanitizeName(folderName)
        if (sanitized.isBlank()) {
            return@withContext StorageActionResult(
                success = false,
                message = "Please specify a valid folder name."
            )
        }

        val resolved = resolveFolderDocument(parentFolderHint)
            ?: return@withContext StorageActionResult(
                success = false,
                message = if (parentFolderHint.isNotBlank()) {
                    "Storage access to \"$parentFolderHint\" has not been granted yet. Please select that folder via Android Storage Access."
                } else {
                    "No storage folder has been authorized yet. Please grant folder access first."
                },
                requiresPermission = true,
                folderLabel = parentFolderHint
            )

        val (folderMeta, rootDoc) = resolved
        if (!rootDoc.canWrite()) {
            return@withContext StorageActionResult(
                success = false,
                message = "Write permission is required for \"${folderMeta.displayName}\".",
                requiresPermission = true,
                folderLabel = folderMeta.displayName
            )
        }

        // Support nested path creation like Projects/WebApp
        val segments = sanitized.replace('\\', '/').split('/').map { sanitizeName(it) }.filter { it.isNotBlank() }
        var current = rootDoc
        var createdCount = 0
        for (segment in segments) {
            val existing = findChildCaseInsensitive(current, segment)
            if (existing != null) {
                if (!existing.isDirectory) {
                    return@withContext StorageActionResult(
                        success = false,
                        message = "A file named \"$segment\" already exists in ${folderMeta.displayName}.",
                        alreadyExists = true,
                        resolvedName = existing.name,
                        folderLabel = folderMeta.displayName
                    )
                }
                current = existing
            } else {
                val created = current.createDirectory(segment)
                    ?: return@withContext StorageActionResult(
                        success = false,
                        message = "Android could not create folder \"$segment\" inside ${folderMeta.displayName}."
                    )
                current = created
                createdCount++
            }
        }

        val finalName = current.name ?: segments.last()
        val msg = if (createdCount == 0) {
            "Folder \"$finalName\" already exists inside ${folderMeta.displayName}."
        } else {
            "Created folder \"$finalName\" inside ${folderMeta.displayName}."
        }

        StorageActionResult(
            success = true,
            message = msg,
            itemUri = current.uri.toString(),
            resolvedName = finalName,
            folderLabel = folderMeta.displayName,
            alreadyExists = createdCount == 0
        )
    }

    suspend fun createOrWriteFile(
        rawFileName: String,
        content: String,
        explicitType: String? = null,
        folderHint: String = "",
        overwriteConfirmed: Boolean = false
    ): StorageActionResult = withContext(Dispatchers.IO) {
        val fileType = SafFileType.fromFileNameAndType(rawFileName, explicitType)
        val finalName = ensureExtension(sanitizeName(rawFileName), fileType)
        if (finalName.isBlank()) {
            return@withContext StorageActionResult(
                success = false,
                message = "Please specify a valid file name."
            )
        }

        val resolved = resolveFolderDocument(folderHint)
            ?: return@withContext StorageActionResult(
                success = false,
                message = if (folderHint.isNotBlank()) {
                    "Storage permission for \"$folderHint\" is needed to create \"$finalName\"."
                } else {
                    "Please grant access to a storage folder before creating \"$finalName\"."
                },
                requiresPermission = true,
                resolvedName = finalName,
                folderLabel = folderHint
            )

        val (folderMeta, targetDir) = resolved
        if (!targetDir.canWrite()) {
            return@withContext StorageActionResult(
                success = false,
                message = "Write permission is required for \"${folderMeta.displayName}\".",
                requiresPermission = true,
                resolvedName = finalName,
                folderLabel = folderMeta.displayName
            )
        }

        val existing = findChildCaseInsensitive(targetDir, finalName)
        if (existing != null) {
            if (existing.isDirectory) {
                return@withContext StorageActionResult(
                    success = false,
                    message = "A folder named \"$finalName\" already exists in ${folderMeta.displayName}.",
                    alreadyExists = true,
                    resolvedName = finalName,
                    folderLabel = folderMeta.displayName
                )
            }
            if (!overwriteConfirmed) {
                return@withContext StorageActionResult(
                    success = false,
                    message = "File \"$finalName\" already exists in ${folderMeta.displayName}. Please confirm if you want to overwrite it.",
                    itemUri = existing.uri.toString(),
                    resolvedName = existing.name ?: finalName,
                    folderLabel = folderMeta.displayName,
                    requiresConfirmation = true,
                    alreadyExists = true
                )
            }
        }

        val mimeType = SafFileType.resolveMimeType(finalName, explicitType)
        val targetDoc = existing ?: targetDir.createFile(mimeType, finalName)
            ?: return@withContext StorageActionResult(
                success = false,
                message = "Could not create \"$finalName\" inside ${folderMeta.displayName}."
            )

        val textToWrite = if (content.isNotBlank()) {
            content
        } else {
            SafFileType.defaultContentTemplate(finalName, fileType)
        }

        val wrote = writeTextToDocumentUri(targetDoc.uri, textToWrite)
        if (!wrote) {
            return@withContext StorageActionResult(
                success = false,
                message = "Created \"$finalName\" but writing its contents failed."
            )
        }

        StorageActionResult(
            success = true,
            message = if (existing != null) {
                "Updated existing file \"$finalName\" inside ${folderMeta.displayName}."
            } else {
                "Created file \"$finalName\" inside ${folderMeta.displayName}."
            },
            itemUri = targetDoc.uri.toString(),
            resolvedName = targetDoc.name ?: finalName,
            folderLabel = folderMeta.displayName
        )
    }

    suspend fun writeDocumentUri(uri: Uri, content: String, fileNameHint: String = "", explicitType: String? = null): StorageActionResult =
        withContext(Dispatchers.IO) {
            val doc = DocumentFile.fromSingleUri(context, uri)
            val name = doc?.name ?: fileNameHint.ifBlank { "document.txt" }
            val fileType = SafFileType.fromFileNameAndType(name, explicitType)
            val textToWrite = if (content.isNotBlank()) content else SafFileType.defaultContentTemplate(name, fileType)
            val ok = writeTextToDocumentUri(uri, textToWrite)
            StorageActionResult(
                success = ok,
                message = if (ok) "Saved file \"$name\" successfully." else "Failed to write file \"$name\".",
                itemUri = uri.toString(),
                resolvedName = name
            )
        }

    suspend fun listAndFindItems(
        query: String = "",
        folderHint: String = "",
        limit: Int = 60
    ): StorageActionResult = withContext(Dispatchers.IO) {
        val folders = getAuthorizedFolders()
        if (folders.isEmpty()) {
            return@withContext StorageActionResult(
                success = false,
                message = "No storage folders are authorized yet. Please grant folder access first.",
                requiresPermission = true
            )
        }

        val targetDirs: List<Pair<AuthorizedStorageFolder, DocumentFile>> = if (folderHint.isNotBlank()) {
            val resolved = resolveFolderDocument(folderHint)
                ?: return@withContext StorageActionResult(
                    success = false,
                    message = "Folder \"$folderHint\" is not authorized yet.",
                    requiresPermission = true,
                    folderLabel = folderHint
                )
            listOf(resolved)
        } else {
            folders.mapNotNull { meta ->
                DocumentFile.fromTreeUri(context, Uri.parse(meta.uriString))?.let { meta to it }
            }
        }

        val entries = mutableListOf<StorageItemEntry>()
        val qLower = query.trim().lowercase(Locale.ROOT)
        for ((meta, dir) in targetDirs) {
            val children = dir.listFiles()
            for (child in children) {
                val childName = child.name ?: continue
                if (qLower.isNotBlank() && !childName.lowercase(Locale.ROOT).contains(qLower)) continue
                entries.add(
                    StorageItemEntry(
                        name = childName,
                        uriString = child.uri.toString(),
                        isDirectory = child.isDirectory,
                        sizeBytes = child.length(),
                        lastModifiedMs = child.lastModified(),
                        mimeType = child.type,
                        parentFolderLabel = dir.name ?: meta.displayName
                    )
                )
                if (entries.size >= limit) break
            }
            if (entries.size >= limit) break
        }

        val sorted = entries.sortedWith(compareBy<StorageItemEntry> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
        val summary = if (sorted.isEmpty()) {
            if (qLower.isBlank()) "No files or folders found in authorized storage."
            else "No items matching \"$query\" were found in authorized folders."
        } else {
            "Found ${sorted.size} item(s): ${sorted.take(12).joinToString(", ") { if (it.isDirectory) "[Folder] ${it.name}" else it.name }}"
        }

        StorageActionResult(
            success = true,
            message = summary,
            folderLabel = targetDirs.firstOrNull()?.first?.displayName,
            items = sorted
        )
    }

    suspend fun checkExists(itemName: String, folderHint: String = ""): StorageActionResult =
        withContext(Dispatchers.IO) {
            val clean = sanitizeName(itemName)
            val resolved = resolveFolderDocument(folderHint)
                ?: return@withContext StorageActionResult(
                    success = false,
                    message = "Folder access is required before checking \"$clean\".",
                    requiresPermission = true,
                    folderLabel = folderHint
                )
            val (meta, dir) = resolved
            val found = findChildCaseInsensitive(dir, clean)
            if (found != null) {
                StorageActionResult(
                    success = true,
                    message = "${if (found.isDirectory) "Folder" else "File"} \"${found.name ?: clean}\" exists inside ${dir.name ?: meta.displayName}.",
                    itemUri = found.uri.toString(),
                    resolvedName = found.name ?: clean,
                    folderLabel = dir.name ?: meta.displayName,
                    alreadyExists = true
                )
            } else {
                StorageActionResult(
                    success = true,
                    message = "\"$clean\" does not exist inside ${dir.name ?: meta.displayName}.",
                    resolvedName = clean,
                    folderLabel = dir.name ?: meta.displayName,
                    alreadyExists = false
                )
            }
        }

    suspend fun renameItem(
        currentName: String,
        newName: String,
        folderHint: String = ""
    ): StorageActionResult = withContext(Dispatchers.IO) {
        val cleanOld = sanitizeName(currentName)
        val cleanNew = sanitizeName(newName)
        if (cleanOld.isBlank() || cleanNew.isBlank()) {
            return@withContext StorageActionResult(
                success = false,
                message = "Both current name and new name are required to rename an item."
            )
        }

        val resolved = resolveFolderDocument(folderHint)
            ?: return@withContext StorageActionResult(
                success = false,
                message = "Folder access is required to rename \"$cleanOld\".",
                requiresPermission = true,
                folderLabel = folderHint
            )
        val (meta, dir) = resolved
        val target = findChildCaseInsensitive(dir, cleanOld)
            ?: return@withContext StorageActionResult(
                success = false,
                message = "Could not find \"$cleanOld\" in ${dir.name ?: meta.displayName}."
            )
        val conflict = findChildCaseInsensitive(dir, cleanNew)
        if (conflict != null && conflict.uri != target.uri) {
            return@withContext StorageActionResult(
                success = false,
                message = "An item named \"$cleanNew\" already exists in ${dir.name ?: meta.displayName}.",
                alreadyExists = true
            )
        }

        val renamed = target.renameTo(cleanNew)
        StorageActionResult(
            success = renamed,
            message = if (renamed) {
                "Renamed \"$cleanOld\" to \"$cleanNew\" inside ${dir.name ?: meta.displayName}."
            } else {
                "Android denied renaming \"$cleanOld\" to \"$cleanNew\"."
            },
            itemUri = target.uri.toString(),
            resolvedName = cleanNew,
            folderLabel = dir.name ?: meta.displayName
        )
    }

    suspend fun deleteItem(
        targetName: String,
        folderHint: String = "",
        itemUriString: String = "",
        confirmed: Boolean = false
    ): StorageActionResult = withContext(Dispatchers.IO) {
        if (!confirmed) {
            return@withContext StorageActionResult(
                success = false,
                message = "Explicit user confirmation is required before deleting \"$targetName\".",
                requiresConfirmation = true,
                resolvedName = targetName,
                folderLabel = folderHint
            )
        }

        if (itemUriString.isNotBlank()) {
            val uri = runCatching { Uri.parse(itemUriString) }.getOrNull()
            val doc = uri?.let { DocumentFile.fromSingleUri(context, it) ?: DocumentFile.fromTreeUri(context, it) }
            if (doc != null && doc.exists()) {
                val actualName = doc.name ?: targetName
                val deleted = doc.delete()
                return@withContext StorageActionResult(
                    success = deleted,
                    message = if (deleted) "Deleted \"$actualName\"." else "Could not delete \"$actualName\".",
                    resolvedName = actualName
                )
            }
        }

        val cleanTarget = sanitizeName(targetName)
        val resolved = resolveFolderDocument(folderHint)
            ?: return@withContext StorageActionResult(
                success = false,
                message = "Folder access is required to locate and delete \"$cleanTarget\".",
                requiresPermission = true,
                folderLabel = folderHint
            )
        val (meta, dir) = resolved
        val target = findChildCaseInsensitive(dir, cleanTarget)
            ?: return@withContext StorageActionResult(
                success = false,
                message = "Could not find \"$cleanTarget\" in ${dir.name ?: meta.displayName}."
            )

        val actualName = target.name ?: cleanTarget
        val deleted = target.delete()
        StorageActionResult(
            success = deleted,
            message = if (deleted) {
                "Deleted \"$actualName\" from ${dir.name ?: meta.displayName}."
            } else {
                "Could not delete \"$actualName\" from ${dir.name ?: meta.displayName}."
            },
            resolvedName = actualName,
            folderLabel = dir.name ?: meta.displayName
        )
    }

    suspend fun readTextPreview(uriString: String): String = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext ""
        return@withContext try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                var total = 0
                while (total < MAX_PREVIEW_BYTES) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    val toWrite = minOf(read, MAX_PREVIEW_BYTES - total)
                    out.write(buf, 0, toWrite)
                    total += toWrite
                }
                String(out.toByteArray(), StandardCharsets.UTF_8)
            } ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading file preview: ${e.message}")
            ""
        }
    }

    private fun writeTextToDocumentUri(uri: Uri, text: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(text.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            } ?: context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                out.write(text.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing text to SAF URI $uri: ${e.message}", e)
            false
        }
    }

    private fun findChildCaseInsensitive(dir: DocumentFile, name: String): DocumentFile? {
        val exact = dir.findFile(name)
        if (exact != null) return exact
        return dir.listFiles().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun sanitizeName(raw: String): String {
        return raw.trim()
            .replace(INVALID_FILENAME_CHARS, "_")
            .trim('.', ' ')
    }

    private fun ensureExtension(cleanName: String, fileType: SafFileType): String {
        if (cleanName.isBlank()) return ""
        if (cleanName.contains('.')) return cleanName
        return if (fileType.extension.isNotBlank()) "$cleanName.${fileType.extension}" else cleanName
    }

    private fun normalizeFolderAlias(raw: String): String {
        val lower = raw.trim().lowercase(Locale.ROOT)
        return when {
            lower.contains("download") -> "Downloads"
            lower.contains("document") -> "Documents"
            lower.contains("picture") || lower.contains("photo") -> "Pictures"
            lower.contains("music") -> "Music"
            lower.contains("dcim") || lower.contains("camera") -> "DCIM"
            else -> raw.trim().ifBlank { "Folder" }
        }
    }

    private fun inferDisplayNameFromUri(uri: Uri): String {
        val last = uri.lastPathSegment?.substringAfterLast(':')?.trim().orEmpty()
        return last.ifBlank { "Authorized Folder" }
    }

    private data class StoredFolderMeta(
        val displayName: String,
        val rootAlias: String,
        val grantedAtMs: Long
    )

    private fun loadMetaMap(): MutableMap<String, StoredFolderMeta> {
        val raw = preferences.authorizedFolderMetaJson
        if (raw.isBlank()) return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, StoredFolderMeta>>() {}.type
            gson.fromJson(raw, type) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun saveMetaMap(map: Map<String, StoredFolderMeta>) {
        preferences.authorizedFolderMetaJson = gson.toJson(map)
    }
}
