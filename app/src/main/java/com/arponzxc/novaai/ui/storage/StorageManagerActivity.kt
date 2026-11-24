@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.arponzxc.novaai.ui.storage

import androidx.compose.foundation.layout.FlowRow
import com.arponzxc.novaai.ui.theme.NovaUi
import com.arponzxc.novaai.ui.theme.NovaPageTheme
import com.arponzxc.novaai.ui.theme.NovaPageHeader
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.R
import com.arponzxc.novaai.storage.AuthorizedStorageFolder
import com.arponzxc.novaai.storage.PendingStorageAction
import com.arponzxc.novaai.storage.SafActionCoordinator
import com.arponzxc.novaai.storage.SafFileType
import com.arponzxc.novaai.storage.StorageActivityEntry
import com.arponzxc.novaai.storage.StorageItemEntry
import com.arponzxc.novaai.storage.StorageUiPrompt
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StorageManagerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTO_REQUEST_FOLDER = "extra_auto_request_folder"
        const val EXTRA_SUGGESTED_FOLDER = "extra_suggested_folder"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.arponzxc.novaai.util.ThemeManager.applyTheme(this)
        val autoRequest = intent?.getBooleanExtra(EXTRA_AUTO_REQUEST_FOLDER, false) ?: false
        val suggestedFolder = intent?.getStringExtra(EXTRA_SUGGESTED_FOLDER).orEmpty()

        setContent {
            
            NovaPageTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    StorageManagerScreen(
                        initialSuggestedFolder = suggestedFolder,
                        autoLaunchPicker = autoRequest,
                        onBack = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageManagerScreen(
    initialSuggestedFolder: String,
    autoLaunchPicker: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val safManager = remember { JarvisApp.instance.safFileManager }
    val scope = rememberCoroutineScope()

    val folders by SafActionCoordinator.authorizedFolders.collectAsState()
    val pendingPrompt by SafActionCoordinator.pendingPrompt.collectAsState()
    val history by SafActionCoordinator.recentHistory.collectAsState()

    var selectedFolder by remember { mutableStateOf<AuthorizedStorageFolder?>(null) }
    var items by remember { mutableStateOf<List<StorageItemEntry>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<StorageItemEntry?>(null) }
    var previewItem by remember { mutableStateOf<Pair<StorageItemEntry, String>?>(null) }
    var singleFilePendingContent by remember { mutableStateOf("") }
    var singleFilePendingName by remember { mutableStateOf("nova_note.txt") }
    var singleFilePendingType by remember { mutableStateOf("txt") }

    suspend fun reloadFiles() {
        SafActionCoordinator.refreshFolders(safManager)
        val folderHint = selectedFolder?.displayName.orEmpty()
        val res = safManager.listAndFindItems(query = searchQuery, folderHint = folderHint)
        items = res.items
    }

    val openTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val res = SafActionCoordinator.onFolderAccessGranted(safManager, uri, initialSuggestedFolder)
                Toast.makeText(context, res?.message ?: "Folder authorized.", Toast.LENGTH_SHORT).show()
                reloadFiles()
            }
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val res = SafActionCoordinator.onSingleFileCreatedViaPicker(
                    safFileManager = safManager,
                    fileUri = uri,
                    content = singleFilePendingContent,
                    fileNameHint = singleFilePendingName,
                    explicitType = singleFilePendingType
                )
                Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                reloadFiles()
            }
        }
    }

    LaunchedEffect(Unit) {
        reloadFiles()
        if (autoLaunchPicker && (pendingPrompt is StorageUiPrompt.RequestFolderAccess || folders.isEmpty())) {
            openTreeLauncher.launch(null)
        }
    }

    LaunchedEffect(selectedFolder, searchQuery) {
        reloadFiles()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                NovaPageHeader(
                    title = stringResource(R.string.saf_manager_title),
                    subtitle = stringResource(R.string.saf_manager_subtitle),
                    onBack = onBack,
                    backTestTag = "storage_back_button",
                    actions = {
                        IconButton(
                            onClick = { scope.launch { reloadFiles() } },
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("storage_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Refresh folders",
                                tint = NovaUi.accent
                            )
                        }
                    }
                )
            }

            // SAF Security Explanation Banner
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Security,
                                contentDescription = "Storage privacy notice",
                                tint = NovaUi.accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Android Storage Access Framework (SAF)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = NovaUi.textPrimary
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "NOVA AI only reads or creates files inside folders you explicitly authorize. Overwriting or deleting existing files always requires your confirmation.",
                            fontSize = 12.sp,
                            color = NovaUi.textSecondary,
                            lineHeight = 17.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { openTreeLauncher.launch(null) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("grant_folder_access_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NovaUi.accent,
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Authorize Folder", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = {
                                    singleFilePendingName = "nova_export.txt"
                                    singleFilePendingType = "txt"
                                    singleFilePendingContent = "Created by NOVA AI\n"
                                    createDocumentLauncher.launch("nova_export.txt")
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("create_single_doc_button")
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Save Single File", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // Pending Prompt Card (Folder permission / Overwrite / Delete)
            pendingPrompt?.let { prompt ->
                item {
                    PendingPromptCard(
                        prompt = prompt,
                        onGrantFolder = { openTreeLauncher.launch(null) },
                        onConfirm = { confirmed ->
                            scope.launch {
                                val res = SafActionCoordinator.confirmCurrentPrompt(safManager, confirmed)
                                if (res != null) {
                                    Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                                }
                                reloadFiles()
                            }
                        },
                        onDismiss = { SafActionCoordinator.dismissPrompt() }
                    )
                }
            }

            // Authorized Folders Section
            item {
                Text(
                    text = "AUTHORIZED FOLDERS (${folders.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = NovaUi.textSecondary,
                    letterSpacing = 0.8.sp
                )
            }

            if (folders.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "No folders authorized yet",
                                color = NovaUi.textPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Tap \"Authorize Folder\" above and pick Downloads, Documents, or any custom folder so NOVA AI can create files & folders by voice or text.",
                                fontSize = 12.sp,
                                color = NovaUi.textSecondary
                            )
                        }
                    }
                }
            } else {
                items(folders, key = { it.uriString }) { folder ->
                    val isSelected = selectedFolder?.uriString == folder.uriString
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) NovaUi.accentSoft else NovaUi.cardSolid
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedFolder = if (isSelected) null else folder
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Authorized",
                                tint = NovaUi.success,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = folder.displayName,
                                    color = NovaUi.textPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = if (folder.canWrite) "Read & Write • Persistent SAF Access" else "Read Only",
                                    color = NovaUi.textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            TextButton(
                                onClick = {
                                    safManager.revokeFolderPermission(folder.uriString)
                                    if (selectedFolder?.uriString == folder.uriString) selectedFolder = null
                                    scope.launch { reloadFiles() }
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("revoke_folder_${folder.displayName}")
                            ) {
                                Text("Revoke", color = NovaUi.error, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Quick Actions + Search Bar when authorized folders exist
            if (folders.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showCreateFolderDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("new_folder_button")
                        ) {
                            Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("New Folder", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { showCreateFileDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("new_file_button")
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("New File", fontSize = 13.sp)
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Find files or folders in authorized storage") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("storage_search_input")
                    )
                }

                item {
                    Text(
                        text = "FILES & FOLDERS (${items.size})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = NovaUi.textSecondary,
                        letterSpacing = 0.8.sp
                    )
                }

                if (items.isEmpty()) {
                    item {
                        Text(
                            text = "No matching items in this folder.",
                            fontSize = 13.sp,
                            color = NovaUi.textSecondary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    items(items, key = { it.uriString }) { entry ->
                        StorageEntryRow(
                            entry = entry,
                            onPreview = {
                                if (!entry.isDirectory) {
                                    scope.launch {
                                        val content = safManager.readTextPreview(entry.uriString)
                                        previewItem = entry to content
                                    }
                                }
                            },
                            onRename = { renameTarget = entry },
                            onDelete = {
                                SafActionCoordinator.requestDeleteConfirmation(
                                    itemName = entry.name,
                                    isDirectory = entry.isDirectory,
                                    folderLabel = entry.parentFolderLabel,
                                    action = PendingStorageAction.DeleteItem(
                                        targetName = entry.name,
                                        folderHint = entry.parentFolderLabel,
                                        isDirectory = entry.isDirectory,
                                        itemUri = entry.uriString
                                    )
                                )
                            }
                        )
                    }
                }
            }

            // Recent Storage Activity Log
            if (history.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = NovaUi.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "RECENT STORAGE ACTIVITY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = NovaUi.textSecondary,
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                items(history.take(8)) { logItem ->
                    HistoryRow(logItem)
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            defaultParent = selectedFolder?.displayName.orEmpty(),
            onDismiss = { showCreateFolderDialog = false },
            onCreate = { name, parent ->
                showCreateFolderDialog = false
                scope.launch {
                    val res = SafActionCoordinator.executePendingAction(
                        safManager,
                        PendingStorageAction.CreateFolder(name, parent)
                    )
                    Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                    reloadFiles()
                }
            }
        )
    }

    if (showCreateFileDialog) {
        CreateFileDialog(
            defaultFolder = selectedFolder?.displayName.orEmpty(),
            onDismiss = { showCreateFileDialog = false },
            onCreate = { name, type, content, folder ->
                showCreateFileDialog = false
                scope.launch {
                    val res = SafActionCoordinator.executePendingAction(
                        safManager,
                        PendingStorageAction.CreateOrWriteFile(name, content, type, folder)
                    )
                    Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                    reloadFiles()
                }
            }
        )
    }

    renameTarget?.let { target ->
        var newName by remember(target) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename ${if (target.isDirectory) "Folder" else "File"}") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val updated = newName.trim()
                    renameTarget = null
                    scope.launch {
                        val res = SafActionCoordinator.executePendingAction(
                            safManager,
                            PendingStorageAction.RenameItem(target.name, updated, target.parentFolderLabel)
                        )
                        Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                        reloadFiles()
                    }
                }) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    previewItem?.let { (entry, text) ->
        AlertDialog(
            onDismissRequest = { previewItem = null },
            title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Folder: ${entry.parentFolderLabel} • ${entry.sizeBytes} bytes",
                        fontSize = 11.sp,
                        color = NovaUi.textSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = text.ifBlank { "(Empty file or binary content)" },
                        fontSize = 12.sp,
                        maxLines = 16
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { previewItem = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun PendingPromptCard(
    prompt: StorageUiPrompt,
    onGrantFolder: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NovaUi.warnBg)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = "Action requires confirmation",
                    tint = NovaUi.warn
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (prompt) {
                        is StorageUiPrompt.RequestFolderAccess -> "Storage Permission Needed"
                        is StorageUiPrompt.ConfirmOverwrite -> "File Already Exists — Confirm Overwrite"
                        is StorageUiPrompt.ConfirmDelete -> "Confirm Deletion"
                        is StorageUiPrompt.ConfirmMove -> "Confirm Move"
                    },
                    color = NovaUi.warn,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (prompt) {
                    is StorageUiPrompt.RequestFolderAccess -> prompt.reason
                    is StorageUiPrompt.ConfirmOverwrite ->
                        "\"${prompt.fileName}\" already exists in ${prompt.folderLabel}. Overwriting will replace its contents."
                    is StorageUiPrompt.ConfirmDelete ->
                        "Are you sure you want to delete ${if (prompt.isDirectory) "folder" else "file"} \"${prompt.itemName}\" from ${prompt.folderLabel}? This cannot be undone."
                    is StorageUiPrompt.ConfirmMove ->
                        "Move \"${prompt.itemName}\" to folder \"${prompt.targetFolderLabel}\"?"
                },
                color = NovaUi.textPrimary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when (prompt) {
                    is StorageUiPrompt.RequestFolderAccess -> {
                        Button(
                            onClick = onGrantFolder,
                            modifier = Modifier.height(48.dp).testTag("confirm_grant_folder_button")
                        ) {
                            Text("Select Folder")
                        }
                        TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                            Text("Cancel")
                        }
                    }
                    is StorageUiPrompt.ConfirmOverwrite,
                    is StorageUiPrompt.ConfirmDelete,
                    is StorageUiPrompt.ConfirmMove -> {
                        Button(
                            onClick = { onConfirm(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = NovaUi.error),
                            modifier = Modifier.height(48.dp).testTag("confirm_storage_action_button")
                        ) {
                            Text(
                                when (prompt) {
                                    is StorageUiPrompt.ConfirmDelete -> "Confirm Delete"
                                    is StorageUiPrompt.ConfirmMove -> "Confirm Move"
                                    else -> "Confirm Overwrite"
                                }
                            )
                        }
                        OutlinedButton(
                            onClick = { onConfirm(false) },
                            modifier = Modifier.height(48.dp).testTag("cancel_storage_action_button")
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageEntryRow(
    entry: StorageItemEntry,
    onPreview: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPreview() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(NovaUi.border, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description,
                    contentDescription = if (entry.isDirectory) "Folder" else "File",
                    tint = if (entry.isDirectory) NovaUi.warn else NovaUi.accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    color = NovaUi.textPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (entry.isDirectory) "${entry.parentFolderLabel} • Folder"
                    else "${entry.parentFolderLabel} • ${entry.sizeBytes} B",
                    color = NovaUi.textSecondary,
                    fontSize = 11.sp
                )
            }
            IconButton(
                onClick = onRename,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DriveFileRenameOutline,
                    contentDescription = "Rename ${entry.name}",
                    tint = NovaUi.textSecondary
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete ${entry.name}",
                    tint = NovaUi.error
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: StorageActivityEntry) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (entry.success) "✓ ${entry.action}" else "• ${entry.action}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (entry.success) NovaUi.success else NovaUi.warn
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = timeFmt.format(Date(entry.timestampMs)),
                fontSize = 11.sp,
                color = NovaUi.textMuted
            )
        }
        Text(text = entry.detail, fontSize = 12.sp, color = NovaUi.textSecondary)
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = NovaUi.border)
    }
}

@Composable
private fun CreateFolderDialog(
    defaultParent: String,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    var parentHint by remember { mutableStateOf(defaultParent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder name (e.g. Projects or AI)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("create_folder_name_input")
                )
                OutlinedTextField(
                    value = parentHint,
                    onValueChange = { parentHint = it },
                    label = { Text("Target authorized folder (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (folderName.isNotBlank()) onCreate(folderName.trim(), parentHint.trim()) },
                modifier = Modifier.testTag("confirm_create_folder_button")
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CreateFileDialog(
    defaultFolder: String,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String) -> Unit
) {
    var fileName by remember { mutableStateOf("Notes.txt") }
    var selectedType by remember { mutableStateOf(SafFileType.TXT) }
    var content by remember { mutableStateOf("") }
    var folderHint by remember { mutableStateOf(defaultFolder) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New File") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf(SafFileType.TXT, SafFileType.JSON, SafFileType.HTML, SafFileType.CSS, SafFileType.JS).forEach { ft ->
                        FilterChip(
                            selected = selectedType == ft,
                            onClick = {
                                selectedType = ft
                                val base = fileName.substringBeforeLast('.').ifBlank { "nova_file" }
                                fileName = "$base.${ft.extension}"
                            },
                            label = { Text(".${ft.extension}", fontSize = 11.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("File name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("create_file_name_input")
                )
                OutlinedTextField(
                    value = folderHint,
                    onValueChange = { folderHint = it },
                    label = { Text("Target authorized folder (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content / Code (leave blank for starter template)") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().testTag("create_file_content_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (fileName.isNotBlank()) {
                        onCreate(fileName.trim(), selectedType.extension, content, folderHint.trim())
                    }
                },
                modifier = Modifier.testTag("confirm_create_file_button")
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
