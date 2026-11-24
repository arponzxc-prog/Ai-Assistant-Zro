package com.arponzxc.novaai.storage

enum class SafFileType(
    val extension: String,
    val mimeType: String,
    val displayLabel: String
) {
    TXT("txt", "text/plain", "Text File (.txt)"),
    MD("md", "text/markdown", "Markdown (.md)"),
    JSON("json", "application/json", "JSON File (.json)"),
    HTML("html", "text/html", "HTML File (.html)"),
    CSS("css", "text/css", "CSS File (.css)"),
    JS("js", "application/javascript", "JavaScript File (.js)"),
    PDF("pdf", "application/pdf", "PDF Document (.pdf)"),
    CUSTOM("", "text/plain", "Custom File");

    companion object {
        fun fromFileNameAndType(fileName: String?, explicitType: String?): SafFileType {
            val typeLower = explicitType?.trim()?.lowercase().orEmpty()
            when {
                typeLower == "txt" || typeLower == "text" || typeLower.contains("text/plain") -> return TXT
                typeLower == "md" || typeLower == "markdown" -> return MD
                typeLower == "json" || typeLower.contains("json") -> return JSON
                typeLower == "html" || typeLower == "htm" || typeLower.contains("html") -> return HTML
                typeLower == "css" || typeLower.contains("css") -> return CSS
                typeLower == "js" || typeLower == "javascript" || typeLower.contains("javascript") -> return JS
                typeLower == "pdf" || typeLower.contains("pdf") -> return PDF
            }

            val ext = fileName?.trim()?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return when (ext) {
                "txt", "log", "csv" -> TXT
                "md", "markdown" -> MD
                "json" -> JSON
                "html", "htm" -> HTML
                "css" -> CSS
                "js", "mjs" -> JS
                "pdf" -> PDF
                "" -> TXT
                else -> CUSTOM
            }
        }

        fun resolveMimeType(fileName: String, explicitType: String?): String {
            val resolvedType = fromFileNameAndType(fileName, explicitType)
            if (resolvedType != CUSTOM) return resolvedType.mimeType
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "xml" -> "application/xml"
                "yaml", "yml" -> "text/plain"
                "kt", "java", "py", "ts", "sh", "sql" -> "text/plain"
                else -> "application/octet-stream"
            }
        }

        fun defaultContentTemplate(fileName: String, fileType: SafFileType): String {
            return when (fileType) {
                JSON -> "{\n  \"name\": \"${fileName.substringBeforeLast('.')}\",\n  \"createdBy\": \"NOVA AI\"\n}\n"
                HTML -> """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>${fileName.substringBeforeLast('.')}</title>
</head>
<body>
  <h1>${fileName.substringBeforeLast('.')}</h1>
  <p>Created by NOVA AI</p>
</body>
</html>
"""
                CSS -> """/* Stylesheet created by NOVA AI */
:root {
  --bg-color: #0b1120;
  --text-color: #f8fafc;
}

body {
  margin: 0;
  font-family: system-ui, -apple-system, sans-serif;
  background-color: var(--bg-color);
  color: var(--text-color);
}
"""
                JS -> """// JavaScript file created by NOVA AI
export function main() {
  console.log("NOVA AI workspace ready.");
}
"""
                MD -> "# ${fileName.substringBeforeLast('.')}\n\nCreated by NOVA AI.\n"
                TXT, PDF, CUSTOM -> ""
            }
        }
    }
}

data class AuthorizedStorageFolder(
    val uriString: String,
    val displayName: String,
    val rootAlias: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val grantedAtMs: Long = 0L
)

data class StorageItemEntry(
    val name: String,
    val uriString: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val mimeType: String?,
    val parentFolderLabel: String
)

data class FileDetailsInfo(
    val name: String,
    val uriString: String,
    val isDirectory: Boolean,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val canRead: Boolean,
    val canWrite: Boolean,
    val parentFolderLabel: String,
    val isFavorite: Boolean
)

data class ContentSearchMatch(
    val entry: StorageItemEntry,
    val lineNumber: Int,
    val excerpt: String
)

data class OrganizationSuggestion(
    val id: String,
    val sourceUri: String,
    val currentName: String,
    val suggestedAction: String, // "RENAME" or "MOVE"
    val targetNameOrFolder: String,
    val reason: String
)

sealed class PendingStorageAction {
    data class CreateFolder(
        val folderName: String,
        val parentFolderHint: String = ""
    ) : PendingStorageAction()

    data class CreateOrWriteFile(
        val fileName: String,
        val content: String,
        val fileType: String? = null,
        val folderHint: String = "",
        val overwriteConfirmed: Boolean = false
    ) : PendingStorageAction()

    data class RenameItem(
        val currentName: String,
        val newName: String,
        val folderHint: String = ""
    ) : PendingStorageAction()

    data class DeleteItem(
        val targetName: String,
        val folderHint: String = "",
        val isDirectory: Boolean = false,
        val itemUri: String = ""
    ) : PendingStorageAction()

    data class MoveItem(
        val sourceUri: String,
        val itemName: String,
        val targetFolderUri: String,
        val targetFolderLabel: String
    ) : PendingStorageAction()
}

sealed class StorageUiPrompt {
    data class RequestFolderAccess(
        val reason: String,
        val suggestedFolder: String,
        val pendingAction: PendingStorageAction? = null
    ) : StorageUiPrompt()

    data class ConfirmOverwrite(
        val fileName: String,
        val folderLabel: String,
        val pendingAction: PendingStorageAction.CreateOrWriteFile
    ) : StorageUiPrompt()

    data class ConfirmDelete(
        val itemName: String,
        val isDirectory: Boolean,
        val folderLabel: String,
        val pendingAction: PendingStorageAction.DeleteItem
    ) : StorageUiPrompt()

    data class ConfirmMove(
        val itemName: String,
        val targetFolderLabel: String,
        val pendingAction: PendingStorageAction.MoveItem
    ) : StorageUiPrompt()
}

data class StorageActionResult(
    val success: Boolean,
    val message: String,
    val itemUri: String? = null,
    val resolvedName: String? = null,
    val folderLabel: String? = null,
    val requiresPermission: Boolean = false,
    val requiresConfirmation: Boolean = false,
    val alreadyExists: Boolean = false,
    val items: List<StorageItemEntry> = emptyList()
)
