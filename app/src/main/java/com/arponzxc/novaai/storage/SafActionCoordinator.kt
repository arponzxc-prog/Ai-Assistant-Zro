package com.arponzxc.novaai.storage

import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.arponzxc.novaai.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class StorageActivityEntry(
    val action: String,
    val detail: String,
    val success: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Coordinates SAF storage state, pending folder-permission / overwrite / delete prompts,
 * and audit logging across Service, Voice, Chat, and Compose UI screens.
 */
object SafActionCoordinator {

    private val gson = Gson()
    @Volatile
    private var appPrefs: AppPreferences? = null

    private val _authorizedFolders = MutableStateFlow<List<AuthorizedStorageFolder>>(emptyList())
    val authorizedFolders: StateFlow<List<AuthorizedStorageFolder>> = _authorizedFolders.asStateFlow()

    private val _pendingPrompt = MutableStateFlow<StorageUiPrompt?>(null)
    val pendingPrompt: StateFlow<StorageUiPrompt?> = _pendingPrompt.asStateFlow()

    private val _recentHistory = MutableStateFlow<List<StorageActivityEntry>>(emptyList())
    val recentHistory: StateFlow<List<StorageActivityEntry>> = _recentHistory.asStateFlow()

    private val _actionMessages = MutableSharedFlow<StorageActionResult>(extraBufferCapacity = 16)
    val actionMessages: SharedFlow<StorageActionResult> = _actionMessages.asSharedFlow()

    fun initialize(safFileManager: SafFileManager, preferences: AppPreferences) {
        appPrefs = preferences
        refreshFolders(safFileManager)
        loadHistory(preferences)
    }

    fun refreshFolders(safFileManager: SafFileManager) {
        _authorizedFolders.value = safFileManager.getAuthorizedFolders()
    }

    fun requestFolderPermissionPrompt(
        reason: String,
        suggestedFolder: String,
        pendingAction: PendingStorageAction? = null
    ) {
        _pendingPrompt.value = StorageUiPrompt.RequestFolderAccess(
            reason = reason,
            suggestedFolder = suggestedFolder,
            pendingAction = pendingAction
        )
    }

    fun requestOverwriteConfirmation(
        fileName: String,
        folderLabel: String,
        action: PendingStorageAction.CreateOrWriteFile
    ) {
        _pendingPrompt.value = StorageUiPrompt.ConfirmOverwrite(
            fileName = fileName,
            folderLabel = folderLabel,
            pendingAction = action
        )
    }

    fun requestDeleteConfirmation(
        itemName: String,
        isDirectory: Boolean,
        folderLabel: String,
        action: PendingStorageAction.DeleteItem
    ) {
        _pendingPrompt.value = StorageUiPrompt.ConfirmDelete(
            itemName = itemName,
            isDirectory = isDirectory,
            folderLabel = folderLabel,
            pendingAction = action
        )
    }

    fun requestMoveConfirmation(
        itemName: String,
        targetFolderLabel: String,
        action: PendingStorageAction.MoveItem
    ) {
        _pendingPrompt.value = StorageUiPrompt.ConfirmMove(
            itemName = itemName,
            targetFolderLabel = targetFolderLabel,
            pendingAction = action
        )
    }

    fun dismissPrompt() {
        _pendingPrompt.value = null
    }

    fun hasPendingConfirmation(): Boolean {
        return _pendingPrompt.value is StorageUiPrompt.ConfirmOverwrite ||
            _pendingPrompt.value is StorageUiPrompt.ConfirmDelete ||
            _pendingPrompt.value is StorageUiPrompt.ConfirmMove
    }

    fun hasPendingFolderRequest(): Boolean {
        return _pendingPrompt.value is StorageUiPrompt.RequestFolderAccess
    }

    suspend fun onFolderAccessGranted(
        safFileManager: SafFileManager,
        treeUri: Uri,
        aliasHint: String = ""
    ): StorageActionResult? {
        val currentPrompt = _pendingPrompt.value
        val resolvedAlias = aliasHint.ifBlank {
            (currentPrompt as? StorageUiPrompt.RequestFolderAccess)?.suggestedFolder.orEmpty()
        }
        val granted = safFileManager.persistTreePermission(treeUri, resolvedAlias)
        refreshFolders(safFileManager)

        if (granted == null) {
            val err = StorageActionResult(false, "Could not obtain persistent permission for the selected folder.")
            recordAction("Grant Folder Access", err.message, false)
            _actionMessages.emit(err)
            return err
        }

        recordAction("Grant Folder Access", "Authorized \"${granted.displayName}\"", true)
        val pendingAction = (currentPrompt as? StorageUiPrompt.RequestFolderAccess)?.pendingAction
        _pendingPrompt.value = null

        if (pendingAction != null) {
            return executePendingAction(safFileManager, pendingAction)
        }
        val ok = StorageActionResult(
            success = true,
            message = "Folder access granted for \"${granted.displayName}\".",
            folderLabel = granted.displayName
        )
        _actionMessages.emit(ok)
        return ok
    }

    suspend fun onSingleFileCreatedViaPicker(
        safFileManager: SafFileManager,
        fileUri: Uri,
        content: String,
        fileNameHint: String = "",
        explicitType: String? = null
    ): StorageActionResult {
        val result = safFileManager.writeDocumentUri(fileUri, content, fileNameHint, explicitType)
        _pendingPrompt.value = null
        recordAction("Create File", result.message, result.success)
        _actionMessages.emit(result)
        return result
    }

    suspend fun confirmCurrentPrompt(safFileManager: SafFileManager, confirmed: Boolean): StorageActionResult? {
        val prompt = _pendingPrompt.value ?: return null
        if (!confirmed) {
            _pendingPrompt.value = null
            val cancelled = StorageActionResult(false, "Action cancelled by user.")
            recordAction("Cancelled", cancelled.message, false)
            _actionMessages.emit(cancelled)
            return cancelled
        }

        return when (prompt) {
            is StorageUiPrompt.ConfirmOverwrite -> {
                _pendingPrompt.value = null
                val res = safFileManager.createOrWriteFile(
                    rawFileName = prompt.pendingAction.fileName,
                    content = prompt.pendingAction.content,
                    explicitType = prompt.pendingAction.fileType,
                    folderHint = prompt.pendingAction.folderHint,
                    overwriteConfirmed = true
                )
                recordAction("Overwrite File", res.message, res.success)
                _actionMessages.emit(res)
                res
            }
            is StorageUiPrompt.ConfirmDelete -> {
                _pendingPrompt.value = null
                val res = safFileManager.deleteItem(
                    targetName = prompt.pendingAction.targetName,
                    folderHint = prompt.pendingAction.folderHint,
                    itemUriString = prompt.pendingAction.itemUri,
                    confirmed = true
                )
                recordAction("Delete Item", res.message, res.success)
                _actionMessages.emit(res)
                res
            }
            is StorageUiPrompt.ConfirmMove -> {
                _pendingPrompt.value = null
                val intelligent = SafIntelligentFileService(com.arponzxc.novaai.JarvisApp.instance.applicationContext, safFileManager)
                val res = intelligent.moveFileToFolder(
                    sourceUriString = prompt.pendingAction.sourceUri,
                    targetFolderUriString = prompt.pendingAction.targetFolderUri,
                    confirmed = true
                )
                recordAction("Move Item", res.message, res.success)
                _actionMessages.emit(res)
                res
            }
            is StorageUiPrompt.RequestFolderAccess -> {
                // Handled by UI launcher via onFolderAccessGranted
                null
            }
        }
    }

    suspend fun executePendingAction(
        safFileManager: SafFileManager,
        action: PendingStorageAction
    ): StorageActionResult {
        val res = when (action) {
            is PendingStorageAction.CreateFolder ->
                safFileManager.createFolder(action.folderName, action.parentFolderHint)
            is PendingStorageAction.CreateOrWriteFile -> {
                val r = safFileManager.createOrWriteFile(
                    rawFileName = action.fileName,
                    content = action.content,
                    explicitType = action.fileType,
                    folderHint = action.folderHint,
                    overwriteConfirmed = action.overwriteConfirmed
                )
                if (r.requiresConfirmation && r.alreadyExists) {
                    requestOverwriteConfirmation(
                        fileName = r.resolvedName ?: action.fileName,
                        folderLabel = r.folderLabel ?: "Authorized Folder",
                        action = action
                    )
                }
                r
            }
            is PendingStorageAction.RenameItem ->
                safFileManager.renameItem(action.currentName, action.newName, action.folderHint)
            is PendingStorageAction.DeleteItem -> {
                val r = safFileManager.deleteItem(
                    targetName = action.targetName,
                    folderHint = action.folderHint,
                    itemUriString = action.itemUri,
                    confirmed = false
                )
                if (r.requiresConfirmation) {
                    requestDeleteConfirmation(
                        itemName = action.targetName,
                        isDirectory = action.isDirectory,
                        folderLabel = action.folderHint.ifBlank { "Authorized Folder" },
                        action = action
                    )
                }
                r
            }
            is PendingStorageAction.MoveItem -> {
                requestMoveConfirmation(
                    itemName = action.itemName,
                    targetFolderLabel = action.targetFolderLabel,
                    action = action
                )
                StorageActionResult(
                    success = false,
                    message = "Confirm moving \"${action.itemName}\" to ${action.targetFolderLabel}.",
                    requiresConfirmation = true,
                    resolvedName = action.itemName,
                    folderLabel = action.targetFolderLabel
                )
            }
        }
        if (res.requiresPermission) {
            requestFolderPermissionPrompt(
                reason = res.message,
                suggestedFolder = res.folderLabel.orEmpty(),
                pendingAction = action
            )
        }
        recordAction(action::class.simpleName ?: "Storage", res.message, res.success)
        _actionMessages.emit(res)
        return res
    }

    fun recordAction(action: String, detail: String, success: Boolean) {
        val updated = (listOf(StorageActivityEntry(action, detail, success)) + _recentHistory.value)
            .take(50)
        _recentHistory.value = updated
        appPrefs?.let { prefs ->
            prefs.storageActionHistoryJson = gson.toJson(updated)
        }
    }

    private fun loadHistory(preferences: AppPreferences) {
        val raw = preferences.storageActionHistoryJson
        if (raw.isBlank()) return
        try {
            val type = object : TypeToken<List<StorageActivityEntry>>() {}.type
            _recentHistory.value = gson.fromJson(raw, type) ?: emptyList()
        } catch (_: Exception) {
            _recentHistory.value = emptyList()
        }
    }
}
