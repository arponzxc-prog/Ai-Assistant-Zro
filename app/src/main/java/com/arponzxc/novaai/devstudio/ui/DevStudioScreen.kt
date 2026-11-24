package com.arponzxc.novaai.devstudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Surface
import com.arponzxc.novaai.ui.theme.NovaPageHeader
import com.arponzxc.novaai.ui.theme.NovaUi
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arponzxc.novaai.devstudio.model.ConfirmationActionType
import com.arponzxc.novaai.devstudio.model.DevStudioTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevStudioScreen(
    viewModel: DevStudioViewModel,
    onCloseActivity: () -> Unit,
    onLaunchZipImport: () -> Unit,
    onLaunchZipExport: () -> Unit,
    onAuthorizeSharedFolder: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    BackHandler(enabled = state.isPreviewFullscreen || state.activeTab != DevStudioTab.DASHBOARD) {
        when {
            state.isPreviewFullscreen -> viewModel.togglePreviewFullscreen()
            state.activeTab != DevStudioTab.DASHBOARD -> viewModel.selectTab(DevStudioTab.DASHBOARD)
        }
    }

    Scaffold(
        containerColor = StudioPalette.BgCanvas,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!state.isPreviewFullscreen) {
Column {
                    NovaPageHeader(
                        title = "NOVA Dev Studio",
                        subtitle = state.activeProject?.let { "${it.name} • ${it.buildStatus.label}" }
                            ?: "Autonomous App & Web Workspace",
                        onBack = {
                            if (state.activeTab != DevStudioTab.DASHBOARD) {
                                viewModel.selectTab(DevStudioTab.DASHBOARD)
                            } else {
                                onCloseActivity()
                            }
                        },
                        backTestTag = "studio_back_button",
                        actions = if (state.activeProject != null) {
                            {
                                IconButton(
                                    onClick = { viewModel.requestCloseActiveProject() },
                                    modifier = Modifier.size(44.dp).testTag("studio_close_project_button")
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Close active project",
                                        tint = StudioPalette.TextSecondary
                                    )
                                }
                            }
                        } else null
                    )
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val useRail = maxWidth >= 600.dp && !state.isPreviewFullscreen

            if (useRail) {
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail(containerColor = StudioPalette.BgSurface) {
                        DevStudioTab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = state.activeTab == tab,
                                onClick = { viewModel.selectTab(tab) },
                                icon = { Icon(iconForTab(tab), contentDescription = tab.label) },
                                label = { Text(tab.label, fontSize = 10.sp) }
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        StudioTabContent(viewModel, state, onLaunchZipImport, onLaunchZipExport, onAuthorizeSharedFolder)
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        StudioTabContent(viewModel, state, onLaunchZipImport, onLaunchZipExport, onAuthorizeSharedFolder)
                    }
                    if (!state.isPreviewFullscreen) {
StudioBottomTabs(
                            activeTab = state.activeTab,
                            onSelect = { viewModel.selectTab(it) }
                        )
                    }
                }
            }
        }
    }

    state.pendingProposal?.let { proposal ->
        ChangeDiffReviewDialog(
            proposal = proposal,
            onDismiss = { viewModel.dismissProposal() },
            onApprove = { viewModel.approveProposal(it) }
        )
    }

    state.pendingConfirmation?.let { pending ->
        val isDestructive = pending.actionType == ConfirmationActionType.DELETE_FILE ||
            pending.actionType == ConfirmationActionType.DELETE_PROJECT ||
            pending.actionType == ConfirmationActionType.OVERWRITE_SHARED_PROJECT
        AlertDialog(
            onDismissRequest = { viewModel.dismissConfirmation() },
            containerColor = StudioPalette.BgCard,
            title = { Text(pending.title, color = StudioPalette.TextPrimary) },
            text = { Text(pending.description, color = StudioPalette.TextSecondary) },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmPendingAction() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDestructive) StudioPalette.RoseError else StudioPalette.CyanAccent
                    ),
                    modifier = Modifier.testTag("studio_confirm_action_button")
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissConfirmation() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StudioTabContent(
    viewModel: DevStudioViewModel,
    state: DevStudioUiState,
    onLaunchZipImport: () -> Unit,
    onLaunchZipExport: () -> Unit,
    onAuthorizeSharedFolder: () -> Unit
) {
    when (state.activeTab) {
        DevStudioTab.DASHBOARD -> StudioDashboardScreen(
            state = state,
            onSelectProject = { proj, tab -> viewModel.selectProject(proj, tab) },
            onDeleteProject = { viewModel.requestDeleteProjectConfirmation(it) },
            onOpenBuilder = { viewModel.selectTab(DevStudioTab.BUILDER) },
            onImportZipClick = onLaunchZipImport,
            onExportZipClick = onLaunchZipExport,
            onAuthorizeSharedFolder = onAuthorizeSharedFolder,
            onSetCustomTermuxPath = { viewModel.setCustomTermuxWorkspaceBase(it) }
        )
        DevStudioTab.BUILDER -> StudioBuilderScreen(
            state = state,
            onGenerate = { prompt, type, name -> viewModel.runAutonomousBuild(prompt, type, name) },
            onCancelAgent = { viewModel.cancelAgentJob() }
        )
        DevStudioTab.WORKSPACE -> StudioWorkspaceScreen(
            state = state,
            onSearchChange = { viewModel.refreshFileTree(query = it) },
            onOpenFile = { viewModel.openFileInEditor(it) },
            onCreateFile = { viewModel.createWorkspaceFile(it) },
            onCreateFolder = { viewModel.createWorkspaceFolder(it) },
            onRenameEntry = { rel, newName -> viewModel.renameWorkspaceEntry(rel, newName) },
            onDeleteEntry = { viewModel.requestDeleteFileConfirmation(it) }
        )
        DevStudioTab.EDITOR -> StudioEditorScreen(
            state = state,
            onContentChange = { viewModel.updateEditorContent(it) },
            onSave = { viewModel.saveActiveFile() },
            onUndo = { viewModel.undoEdit() },
            onRedo = { viewModel.redoEdit() },
            onRevertBackup = { viewModel.revertActiveFileBackup() },
            onExplainCode = { viewModel.explainCurrentFile() },
            onRequestAiEdit = { viewModel.requestAiCodeEdit(it) }
        )
        DevStudioTab.PREVIEW -> StudioWebPreviewScreen(
            state = state,
            onStartPreview = { viewModel.startWebPreview() },
            onStopPreview = { viewModel.stopWebPreview() },
            onToggleViewport = { viewModel.togglePreviewViewportMode() },
            onToggleFullscreen = { viewModel.togglePreviewFullscreen() },
            onToggleAutoReload = { viewModel.togglePreviewAutoReload() },
            onPollChanges = { viewModel.pollWebPreviewChanges() },
            onConsoleMessage = { lvl, msg, src, line -> viewModel.recordWebConsoleMessage(lvl, msg, src, line) }
        )
        DevStudioTab.BUILD_TERMUX -> StudioBuildAndTermuxScreen(
            state = state,
            onValidateProject = { viewModel.validateActiveProject() },
            onAiAutoFix = { viewModel.aiAutoFixBuildErrors() },
            onBuildApkTermux = { viewModel.requestBuildAndroidApkViaTermux() },
            onCancelBuildOrCommand = { viewModel.cancelActiveBuildOrCommand() },
            onInstallApk = { viewModel.requestApkInstallConfirmation() },
            onLaunchInstalledApp = { viewModel.launchInstalledProjectApp() },
            onAuthorizeSharedFolder = onAuthorizeSharedFolder,
            onRefreshTermux = { viewModel.refreshTermuxEnvironment() },
            onLiveProbeTermux = { viewModel.probeTermuxToolchainLive() },
            onRunCommand = { viewModel.executeTermuxCommand(it) },
            onClearTerminal = { viewModel.clearTerminalLogs() }
        )
        DevStudioTab.AGENT -> StudioAgentTrackerScreen(state = state)
    }
}

private fun iconForTab(tab: DevStudioTab): ImageVector = when (tab) {
    DevStudioTab.DASHBOARD -> Icons.Default.Dashboard
    DevStudioTab.BUILDER -> Icons.Default.AutoAwesome
    DevStudioTab.WORKSPACE -> Icons.Default.Folder
    DevStudioTab.EDITOR -> Icons.Default.Code
    DevStudioTab.PREVIEW -> Icons.Default.Language
    DevStudioTab.BUILD_TERMUX -> Icons.Default.Build
    DevStudioTab.AGENT -> Icons.Default.SmartToy
}


/** Bottom tabs that scroll sideways, so all 7 sections stay tappable and readable on small phones. */
@Composable
private fun StudioBottomTabs(
    activeTab: DevStudioTab,
    onSelect: (DevStudioTab) -> Unit
) {
    Surface(color = StudioPalette.BgSurface, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DevStudioTab.entries.forEach { tab ->
                val selected = activeTab == tab
                Column(
                    modifier = Modifier
                        .widthIn(min = 72.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) NovaUi.accentSoft else Color.Transparent)
                        .clickable { onSelect(tab) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        iconForTab(tab),
                        contentDescription = tab.label,
                        modifier = Modifier.size(20.dp),
                        tint = if (selected) NovaUi.accent else NovaUi.textMuted
                    )
                    Text(
                        text = tab.label,
                        fontSize = 10.sp,
                        maxLines = 1,
                        softWrap = false,
                        color = if (selected) NovaUi.accent else NovaUi.textMuted,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}
