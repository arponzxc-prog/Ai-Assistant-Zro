package com.arponzxc.novaai.ui.storage

import com.arponzxc.novaai.ui.theme.NovaUi
import com.arponzxc.novaai.ui.theme.NovaPageTheme
import com.arponzxc.novaai.ui.theme.NovaPageHeader
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.storage.AuthorizedStorageFolder
import com.arponzxc.novaai.storage.ContentSearchMatch
import com.arponzxc.novaai.storage.FileAiAnalysisResult
import com.arponzxc.novaai.storage.FileDetailsInfo
import com.arponzxc.novaai.storage.PendingStorageAction
import com.arponzxc.novaai.storage.SafActionCoordinator
import com.arponzxc.novaai.storage.SafBoundaryGuard
import com.arponzxc.novaai.storage.SafFileAiAnalyzer
import com.arponzxc.novaai.storage.SafIntelligentFileService
import com.arponzxc.novaai.storage.StorageItemEntry
import com.arponzxc.novaai.storage.StorageUiPrompt
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class FileManagerActivity : ComponentActivity() {

    private val safFileManager by lazy { JarvisApp.instance.safFileManager }

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                lifecycleScope.launch {
                    val res = SafActionCoordinator.onFolderAccessGranted(safFileManager, uri)
                    Toast.makeText(
                        this@FileManagerActivity,
                        res?.message ?: "Authorized folder access.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                SafActionCoordinator.dismissPrompt()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.arponzxc.novaai.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        SafActionCoordinator.refreshFolders(safFileManager)

        setContent {
            NovaPageTheme {
                IntelligentFileManagerScreen(
                    onBack = { finish() },
                    onGrantFolder = { folderPickerLauncher.launch(null) }
                )
            }
        }
    }
}

private data class FolderBreadcrumb(
    val uriString: String,
    val label: String
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IntelligentFileManagerScreen(
    onBack: () -> Unit,
    onGrantFolder: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val safFileManager = remember { JarvisApp.instance.safFileManager }
    val intelligentService = remember { SafIntelligentFileService(context, safFileManager) }

    val authorizedFolders by SafActionCoordinator.authorizedFolders.collectAsState()
    val pendingPrompt by SafActionCoordinator.pendingPrompt.collectAsState()
    val recentHistory by SafActionCoordinator.recentHistory.collectAsState()

    var activeTab by remember { mutableIntStateOf(0) } // 0=Browse, 1=Favorites&Recent, 2=AI Assistant, 3=Activity
    var breadcrumbs by remember { mutableStateOf<List<FolderBreadcrumb>>(emptyList()) }
    var currentItems by remember { mutableStateOf<List<StorageItemEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var searchContentMode by remember { mutableStateOf(false) }
    var contentMatches by remember { mutableStateOf<List<ContentSearchMatch>>(emptyList()) }

    var favorites by remember { mutableStateOf(intelligentService.getFavorites()) }
    var recentFiles by remember { mutableStateOf(intelligentService.getRecentFiles()) }

    var selectedAiFile by remember { mutableStateOf<StorageItemEntry?>(null) }
    var aiQuestion by remember { mutableStateOf("") }
    var aiLoading by remember { mutableStateOf(false) }
    var aiResult by remember { mutableStateOf<FileAiAnalysisResult?>(null) }

    var detailsDialogInfo by remember { mutableStateOf<FileDetailsInfo?>(null) }
    var renameTarget by remember { mutableStateOf<StorageItemEntry?>(null) }
    var copyOrMoveTarget by remember { mutableStateOf<Pair<StorageItemEntry, Boolean>?>(null) } // Boolean: true = Move, false = Copy
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }

    fun reloadCurrentDirectory() {
        val tail = breadcrumbs.lastOrNull()
        scope.launch {
            isLoading = true
            currentItems = if (tail == null) {
                safFileManager.listAndFindItems(searchQuery.takeIf { !searchContentMode }.orEmpty()).items
            } else {
                intelligentService.listDirectoryUri(tail.uriString, tail.label).items.let { list ->
                    if (searchQuery.isNotBlank() && !searchContentMode) {
                        list.filter { it.name.contains(searchQuery, ignoreCase = true) }
                    } else list
                }
            }
            favorites = intelligentService.getFavorites()
            recentFiles = intelligentService.getRecentFiles()
            isLoading = false
        }
    }

    LaunchedEffect(authorizedFolders, breadcrumbs) {
        reloadCurrentDirectory()
    }

    Surface(
        color = NovaUi.bg,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Header
                NovaPageHeader(
                    title = "Intelligent AI File Manager",
                    subtitle = "${authorizedFolders.size} SAF folder(s) • Zero hidden/app-internal access",
                    onBack = onBack,
                    backTestTag = "file_manager_back_button",
                    actions = {
                        Button(
                            onClick = onGrantFolder,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("grant_saf_folder_button")
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Grant", fontSize = 12.sp)
                        }
                    }
                )

                TabRow(
                    selectedTabIndex = activeTab,
                    containerColor = NovaUi.cardSolid,
                    modifier = Modifier.padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(selected = activeTab == 0, onClick = { activeTab = 0 }, text = { Text("Browse", fontSize = 12.sp) })
                    Tab(selected = activeTab == 1, onClick = { activeTab = 1 }, text = { Text("Star/Recent", fontSize = 12.sp) })
                    Tab(selected = activeTab == 2, onClick = { activeTab = 2 }, text = { Text("AI Docs", fontSize = 12.sp) })
                    Tab(selected = activeTab == 3, onClick = { activeTab = 3 }, text = { Text("Activity", fontSize = 12.sp) })
                }

                when (activeTab) {
                    0 -> {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Breadcrumb bar
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = breadcrumbs.isEmpty(),
                                    onClick = { breadcrumbs = emptyList() },
                                    label = { Text("All Roots", fontSize = 11.sp) }
                                )
                                breadcrumbs.forEachIndexed { idx, crumb ->
                                    FilterChip(
                                        selected = idx == breadcrumbs.lastIndex,
                                        onClick = { breadcrumbs = breadcrumbs.take(idx + 1) },
                                        label = { Text(crumb.label, fontSize = 11.sp) }
                                    )
                                }
                            }

                            if (breadcrumbs.isEmpty() && authorizedFolders.isNotEmpty()) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    authorizedFolders.forEach { folder ->
                                        OutlinedButton(onClick = {
                                            breadcrumbs = listOf(FolderBreadcrumb(folder.uriString, folder.displayName))
                                        }) {
                                            Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(folder.displayName, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }

                            // Search Bar + Filename vs Content Toggle
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = {
                                        Text(
                                            if (searchContentMode) "Search inside text/code files…" else "Filter filenames…",
                                            fontSize = 12.5.sp
                                        )
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("file_search_input")
                                )
                                Button(
                                    onClick = {
                                        if (searchContentMode) {
                                            scope.launch {
                                                isLoading = true
                                                contentMatches = intelligentService.searchInsideFiles(searchQuery)
                                                isLoading = false
                                            }
                                        } else {
                                            contentMatches = emptyList()
                                            reloadCurrentDirectory()
                                        }
                                    },
                                    modifier = Modifier.height(52.dp)
                                ) {
                                    Icon(Icons.Rounded.Search, contentDescription = "Search")
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                FilterChip(
                                    selected = !searchContentMode,
                                    onClick = { searchContentMode = false; contentMatches = emptyList() },
                                    label = { Text("Filename Search", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = searchContentMode,
                                    onClick = { searchContentMode = true },
                                    label = { Text("AI Content Search", fontSize = 11.sp) }
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                IconButton(onClick = { showCreateFolderDialog = true }) {
                                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = "New Folder", tint = NovaUi.accent)
                                }
                                IconButton(onClick = { showCreateFileDialog = true }) {
                                    Icon(Icons.Rounded.NoteAdd, contentDescription = "New File", tint = NovaUi.accent)
                                }
                            }

                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                            } else if (searchContentMode && contentMatches.isNotEmpty()) {
                                Text("Content Matches (${contentMatches.size})", fontWeight = FontWeight.Bold, color = NovaUi.accent, fontSize = 12.5.sp)
                                contentMatches.forEach { match ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedAiFile = match.entry
                                                intelligentService.recordRecentFile(match.entry)
                                                activeTab = 2
                                            }
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text("${match.entry.name} (Line ${match.lineNumber})", color = NovaUi.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text(match.excerpt, color = NovaUi.textSecondary, fontSize = 11.5.sp)
                                        }
                                    }
                                }
                            } else if (currentItems.isEmpty()) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = if (authorizedFolders.isEmpty()) {
                                                "No SAF folder authorized yet. Grant folder access above to browse and manage files securely."
                                            } else {
                                                "No files or folders found here."
                                            },
                                            color = NovaUi.textSecondary,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            } else {
                                currentItems.forEach { entry ->
                                    val isFav = favorites.any { it.uriString == entry.uriString }
                                    FileItemCard(
                                        entry = entry,
                                        isFavorite = isFav,
                                        onOpen = {
                                            if (entry.isDirectory) {
                                                breadcrumbs = breadcrumbs + FolderBreadcrumb(entry.uriString, entry.name)
                                            } else {
                                                selectedAiFile = entry
                                                intelligentService.recordRecentFile(entry)
                                                recentFiles = intelligentService.getRecentFiles()
                                                activeTab = 2
                                            }
                                        },
                                        onToggleFav = {
                                            intelligentService.toggleFavorite(entry)
                                            favorites = intelligentService.getFavorites()
                                        },
                                        onInfo = {
                                            scope.launch {
                                                detailsDialogInfo = intelligentService.inspectFileDetails(entry.uriString, entry.parentFolderLabel)
                                            }
                                        },
                                        onRename = { renameTarget = entry },
                                        onCopy = { copyOrMoveTarget = entry to false },
                                        onMove = { copyOrMoveTarget = entry to true },
                                        onDelete = {
                                            scope.launch {
                                                SafActionCoordinator.executePendingAction(
                                                    safFileManager,
                                                    PendingStorageAction.DeleteItem(
                                                        targetName = entry.name,
                                                        folderHint = entry.parentFolderLabel,
                                                        isDirectory = entry.isDirectory,
                                                        itemUri = entry.uriString
                                                    )
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    1 -> {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Favorites (${favorites.size})", fontWeight = FontWeight.Bold, color = NovaUi.accent, fontSize = 13.sp)
                            if (favorites.isEmpty()) {
                                Text("Star any file to keep quick access here.", color = NovaUi.textMuted, fontSize = 12.sp)
                            } else {
                                favorites.forEach { item ->
                                    SimpleSavedFileRow(
                                        entry = item,
                                        onSelect = {
                                            selectedAiFile = item
                                            activeTab = 2
                                        }
                                    )
                                }
                            }

                            HorizontalDivider(color = NovaUi.border)
                            Text("Recent Files (${recentFiles.size})", fontWeight = FontWeight.Bold, color = NovaUi.accent2, fontSize = 13.sp)
                            if (recentFiles.isEmpty()) {
                                Text("Recently opened files appear here automatically.", color = NovaUi.textMuted, fontSize = 12.sp)
                            } else {
                                recentFiles.forEach { item ->
                                    SimpleSavedFileRow(
                                        entry = item,
                                        onSelect = {
                                            selectedAiFile = item
                                            activeTab = 2
                                        }
                                    )
                                }
                            }
                        }
                    }

                    2 -> {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val activeDoc = selectedAiFile
                            if (activeDoc == null) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("AI Document & Organization Assistant", fontWeight = FontWeight.Bold, color = NovaUi.textPrimary, fontSize = 15.sp)
                                        Text(
                                            "Select a text, code, or PDF file from the Browse tab to summarize, explain code, or ask questions. Or generate smart organization suggestions for your current folder.",
                                            color = NovaUi.textSecondary,
                                            fontSize = 12.5.sp
                                        )
                                        Button(
                                            onClick = {
                                                val folderName = breadcrumbs.lastOrNull()?.label ?: "Authorized Folder"
                                                scope.launch {
                                                    aiLoading = true
                                                    aiResult = SafFileAiAnalyzer.suggestFolderOrganization(context, folderName, currentItems)
                                                    aiLoading = false
                                                }
                                            },
                                            enabled = currentItems.isNotEmpty() && !aiLoading
                                        ) {
                                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Suggest Organization for Current Folder")
                                        }
                                    }
                                }
                            } else {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Selected: ${activeDoc.name}", fontWeight = FontWeight.Bold, color = NovaUi.textPrimary, fontSize = 14.sp)
                                        Text("Folder: ${activeDoc.parentFolderLabel} • ${formatSize(activeDoc.sizeBytes)}", fontSize = 11.5.sp, color = NovaUi.textSecondary)
                                        OutlinedTextField(
                                            value = aiQuestion,
                                            onValueChange = { aiQuestion = it },
                                            placeholder = { Text("Ask a question or leave blank for Summary / Code Explanation…", fontSize = 12.sp) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        aiLoading = true
                                                        aiResult = SafFileAiAnalyzer.analyzeDocument(context, safFileManager, activeDoc, aiQuestion)
                                                        aiLoading = false
                                                    }
                                                },
                                                enabled = !aiLoading,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(if (aiQuestion.isBlank()) "Analyze File" else "Ask AI")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    val folderName = breadcrumbs.lastOrNull()?.label ?: activeDoc.parentFolderLabel
                                                    scope.launch {
                                                        aiLoading = true
                                                        aiResult = SafFileAiAnalyzer.suggestFolderOrganization(context, folderName, currentItems)
                                                        aiLoading = false
                                                    }
                                                },
                                                enabled = currentItems.isNotEmpty() && !aiLoading
                                            ) {
                                                Text("Organize")
                                            }
                                        }
                                    }
                                }
                            }

                            if (aiLoading) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                            }

                            aiResult?.let { res ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (res.success) NovaUi.cardSolid else NovaUi.errorBg
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = if (res.success) res.title else "AI Notice",
                                            color = if (res.success) NovaUi.accent else NovaUi.error,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = if (res.success) res.content else res.errorMessage,
                                            color = NovaUi.textPrimary,
                                            fontSize = 13.sp,
                                            lineHeight = 19.sp
                                        )
                                        if (res.suggestions.isNotEmpty()) {
                                            HorizontalDivider(color = NovaUi.border)
                                            res.suggestions.forEach { sug ->
                                                Card(
                                                    colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Text(
                                                            "${sug.suggestedAction}: ${sug.currentName} -> ${sug.targetNameOrFolder}",
                                                            color = NovaUi.textPrimary,
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontSize = 12.5.sp
                                                        )
                                                        Text(sug.reason, color = NovaUi.textSecondary, fontSize = 11.sp)
                                                        if (sug.suggestedAction == "RENAME") {
                                                            OutlinedButton(onClick = {
                                                                scope.launch {
                                                                    val r = SafActionCoordinator.executePendingAction(
                                                                        safFileManager,
                                                                        PendingStorageAction.RenameItem(
                                                                            currentName = sug.currentName,
                                                                            newName = sug.targetNameOrFolder,
                                                                            folderHint = breadcrumbs.lastOrNull()?.label.orEmpty()
                                                                        )
                                                                    )
                                                                    Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
                                                                    reloadCurrentDirectory()
                                                                }
                                                            }) {
                                                                Text("Approve Rename", fontSize = 11.5.sp)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (recentHistory.isEmpty()) {
                                Text("No SAF storage actions logged yet.", color = NovaUi.textMuted, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                            } else {
                                recentHistory.forEach { log ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(
                                                "${log.action} • ${if (log.success) "Success" else "Notice"}",
                                                color = if (log.success) NovaUi.success else NovaUi.warn,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.5.sp
                                            )
                                            Text(log.detail, color = NovaUi.textSecondary, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Coordinator Confirmation Prompts (Delete / Overwrite / Move)
    when (val prompt = pendingPrompt) {
        is StorageUiPrompt.ConfirmDelete -> {
            AlertDialog(
                onDismissRequest = { SafActionCoordinator.dismissPrompt() },
                title = { Text("Confirm Delete") },
                text = { Text("Permanently delete \"${prompt.itemName}\" from ${prompt.folderLabel}? This cannot be undone.") },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                SafActionCoordinator.confirmCurrentPrompt(safFileManager, true)
                                reloadCurrentDirectory()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NovaUi.error)
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        scope.launch { SafActionCoordinator.confirmCurrentPrompt(safFileManager, false) }
                    }) { Text("Cancel") }
                }
            )
        }
        is StorageUiPrompt.ConfirmMove -> {
            AlertDialog(
                onDismissRequest = { SafActionCoordinator.dismissPrompt() },
                title = { Text("Confirm Move") },
                text = { Text("Move \"${prompt.itemName}\" into ${prompt.targetFolderLabel}? The original file will be removed after verified copy.") },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            SafActionCoordinator.confirmCurrentPrompt(safFileManager, true)
                            reloadCurrentDirectory()
                        }
                    }) { Text("Confirm Move") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        scope.launch { SafActionCoordinator.confirmCurrentPrompt(safFileManager, false) }
                    }) { Text("Cancel") }
                }
            )
        }
        is StorageUiPrompt.ConfirmOverwrite -> {
            AlertDialog(
                onDismissRequest = { SafActionCoordinator.dismissPrompt() },
                title = { Text("Overwrite Existing File?") },
                text = { Text("\"${prompt.fileName}\" already exists in ${prompt.folderLabel}. Replace its contents?") },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            SafActionCoordinator.confirmCurrentPrompt(safFileManager, true)
                            reloadCurrentDirectory()
                        }
                    }) { Text("Overwrite") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        scope.launch { SafActionCoordinator.confirmCurrentPrompt(safFileManager, false) }
                    }) { Text("Cancel") }
                }
            )
        }
        else -> Unit
    }

    // File Details Dialog
    detailsDialogInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { detailsDialogInfo = null },
            title = { Text(info.name, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Type: ${if (info.isDirectory) "Folder" else info.mimeType}", fontSize = 12.5.sp)
                    Text("Size: ${formatSize(info.sizeBytes)}", fontSize = 12.5.sp)
                    Text("Modified: ${DateFormat.getDateTimeInstance().format(Date(info.lastModifiedMs))}", fontSize = 12.5.sp)
                    Text("Permissions: ${if (info.canRead) "Read" else "-"} / ${if (info.canWrite) "Write" else "Read-Only"}", fontSize = 12.5.sp)
                    Text("Folder: ${info.parentFolderLabel}", fontSize = 12.5.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { detailsDialogInfo = null }) { Text("Close") }
            }
        )
    }

    // Rename Dialog
    renameTarget?.let { target ->
        var newName by remember { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Item") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = SafBoundaryGuard.isSafeDisplayName(newName),
                    onClick = {
                        scope.launch {
                            val r = safFileManager.renameItem(target.name, newName.trim(), target.parentFolderLabel)
                            Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
                            renameTarget = null
                            reloadCurrentDirectory()
                        }
                    }
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }

    // Copy / Move Destination Picker
    copyOrMoveTarget?.let { (entry, isMove) ->
        AlertDialog(
            onDismissRequest = { copyOrMoveTarget = null },
            title = { Text("${if (isMove) "Move" else "Copy"} \"${entry.name}\"") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Choose destination authorized SAF folder:", fontSize = 12.5.sp, color = NovaUi.textSecondary)
                    authorizedFolders.forEach { dest ->
                        OutlinedButton(
                            onClick = {
                                copyOrMoveTarget = null
                                scope.launch {
                                    if (isMove) {
                                        SafActionCoordinator.executePendingAction(
                                            safFileManager,
                                            PendingStorageAction.MoveItem(
                                                sourceUri = entry.uriString,
                                                itemName = entry.name,
                                                targetFolderUri = dest.uriString,
                                                targetFolderLabel = dest.displayName
                                            )
                                        )
                                    } else {
                                        val res = intelligentService.copyFileToFolder(entry.uriString, dest.uriString)
                                        SafActionCoordinator.recordAction("Copy Item", res.message, res.success)
                                        Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                                        reloadCurrentDirectory()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(dest.displayName)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { copyOrMoveTarget = null }) { Text("Cancel") } }
        )
    }

    if (showCreateFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create New Folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = SafBoundaryGuard.isSafeDisplayName(folderName),
                    onClick = {
                        scope.launch {
                            val r = SafActionCoordinator.executePendingAction(
                                safFileManager,
                                PendingStorageAction.CreateFolder(
                                    folderName = folderName.trim(),
                                    parentFolderHint = breadcrumbs.lastOrNull()?.label.orEmpty()
                                )
                            )
                            Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
                            showCreateFolderDialog = false
                            reloadCurrentDirectory()
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") } }
        )
    }

    if (showCreateFileDialog) {
        var fileName by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFileDialog = false },
            title = { Text("Create New File") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text("File Name (e.g. notes.txt, data.json)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("Initial Content (optional)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = SafBoundaryGuard.isSafeDisplayName(fileName),
                    onClick = {
                        scope.launch {
                            val r = SafActionCoordinator.executePendingAction(
                                safFileManager,
                                PendingStorageAction.CreateOrWriteFile(
                                    fileName = fileName.trim(),
                                    content = content,
                                    folderHint = breadcrumbs.lastOrNull()?.label.orEmpty()
                                )
                            )
                            Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
                            showCreateFileDialog = false
                            reloadCurrentDirectory()
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateFileDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun FileItemCard(
    entry: StorageItemEntry,
    isFavorite: Boolean,
    onOpen: () -> Unit,
    onToggleFav: () -> Unit,
    onInfo: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description,
                    contentDescription = null,
                    tint = if (entry.isDirectory) NovaUi.warn else NovaUi.accent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = NovaUi.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (entry.isDirectory) "Folder • ${entry.parentFolderLabel}"
                        else "${formatSize(entry.sizeBytes)} • ${entry.parentFolderLabel}",
                        fontSize = 11.sp,
                        color = NovaUi.textSecondary
                    )
                }
                IconButton(onClick = onToggleFav) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) NovaUi.warn else NovaUi.textMuted
                    )
                }
                IconButton(onClick = onInfo) {
                    Icon(Icons.Rounded.Info, contentDescription = "Details", tint = NovaUi.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onRename) {
                    Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = "Rename", tint = NovaUi.textSecondary, modifier = Modifier.size(18.dp))
                }
                if (!entry.isDirectory) {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", tint = NovaUi.textSecondary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onMove) {
                        Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = "Move", tint = NovaUi.textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = NovaUi.error, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun SimpleSavedFileRow(entry: StorageItemEntry, onSelect: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Icon(Icons.Rounded.Description, contentDescription = null, tint = NovaUi.accent)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, color = NovaUi.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                Text("${entry.parentFolderLabel} • ${formatSize(entry.sizeBytes)}", color = NovaUi.textSecondary, fontSize = 11.sp)
            }
            Text("AI Docs ->", color = NovaUi.accent, fontSize = 11.5.sp)
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1.0) return "$bytes B"
    val mb = kb / 1024.0
    if (mb < 1.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    return String.format(java.util.Locale.US, "%.2f MB", mb)
}
