package com.arponzxc.novaai.devstudio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arponzxc.novaai.devstudio.model.DevAgentPhase
import com.arponzxc.novaai.devstudio.model.DevProject
import com.arponzxc.novaai.devstudio.model.DevStudioTab
import com.arponzxc.novaai.devstudio.model.ProjectType

@Composable
fun StudioDashboardScreen(
    state: DevStudioUiState,
    onSelectProject: (DevProject, DevStudioTab?) -> Unit,
    onDeleteProject: (DevProject) -> Unit,
    onOpenBuilder: () -> Unit,
    onImportZipClick: () -> Unit,
    onExportZipClick: () -> Unit,
    onAuthorizeSharedFolder: () -> Unit,
    onSetCustomTermuxPath: (String) -> Unit
) {
    var showPosixPathDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioPalette.BgCard),
                border = BorderStroke(1.dp, StudioPalette.BorderSubtle),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Studio AI",
                            tint = StudioPalette.CyanAccent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "NOVA Autonomous Development Studio",
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioPalette.TextPrimary
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Build, inspect, edit, transfer, and run Kotlin Android & HTML Web projects inside a sandboxed workspace.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioPalette.TextMuted
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onOpenBuilder,
                            modifier = Modifier.testTag("dashboard_new_project_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("New AI Project")
                        }
                        OutlinedButton(
                            onClick = onImportZipClick,
                            modifier = Modifier.testTag("dashboard_import_zip_button")
                        ) {
                            Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Import ZIP")
                        }
                    }
                }
            }
        }

        item {
            SharedWorkspaceBridgeCard(
                state = state,
                onAuthorizeSharedFolder = onAuthorizeSharedFolder,
                onEditPosixPath = { showPosixPathDialog = true }
            )
        }

        item {
            EnvironmentSummaryBar(state)
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RECENT PROJECTS (${state.projects.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioPalette.TextMuted
                )
                if (state.activeProject != null) {
                    OutlinedButton(
                        onClick = onExportZipClick,
                        modifier = Modifier.testTag("export_active_zip_button")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export ZIP")
                    }
                }
            }
        }

        if (state.projects.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioPalette.BgSurface),
                    border = BorderStroke(1.dp, StudioPalette.BorderSubtle),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No projects in workspace yet", color = StudioPalette.TextPrimary, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Create a Kotlin Android app or HTML Web app from natural language, or import an Android Studio ZIP.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioPalette.TextMuted
                        )
                    }
                }
            }
        } else {
            items(state.projects, key = { it.id }) { project ->
                val isSelected = state.activeProject?.id == project.id
                ProjectCardItem(
                    project = project,
                    isSelected = isSelected,
                    onOpenEditor = { onSelectProject(project, DevStudioTab.WORKSPACE) },
                    onQuickRun = {
                        val dest = if (project.type == ProjectType.HTML_WEB) DevStudioTab.PREVIEW else DevStudioTab.BUILD_TERMUX
                        onSelectProject(project, dest)
                    },
                    onDelete = { onDeleteProject(project) }
                )
            }
        }
    }

    if (showPosixPathDialog) {
        var pathInput by remember {
            mutableStateOf(state.sharedWorkspace?.resolvedTermuxBasePath ?: "/storage/emulated/0/NOVA_AI_Projects")
        }
        AlertDialog(
            onDismissRequest = { showPosixPathDialog = false },
            containerColor = StudioPalette.BgCard,
            title = { Text("Termux POSIX Workspace Path", color = StudioPalette.TextPrimary) },
            text = {
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    label = { Text("POSIX path visible inside Termux") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    showPosixPathDialog = false
                    onSetCustomTermuxPath(pathInput)
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPosixPathDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SharedWorkspaceBridgeCard(
    state: DevStudioUiState,
    onAuthorizeSharedFolder: () -> Unit,
    onEditPosixPath: () -> Unit
) {
    val cfg = state.sharedWorkspace
    Card(
        colors = CardDefaults.cardColors(containerColor = StudioPalette.BgSurface),
        border = BorderStroke(
            1.dp,
            if (cfg?.isAuthorized == true) StudioPalette.EmeraldSuccess.copy(alpha = 0.5f) else StudioPalette.AmberWarn.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CreateNewFolder,
                        contentDescription = null,
                        tint = if (cfg?.isAuthorized == true) StudioPalette.EmeraldSuccess else StudioPalette.AmberWarn,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Shared Workspace (NOVA_AI_Projects/)",
                        color = StudioPalette.TextPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                StatusPill(
                    text = if (cfg?.isAuthorized == true) "SAF READY" else "NOT AUTHORIZED",
                    color = if (cfg?.isAuthorized == true) StudioPalette.EmeraldSuccess else StudioPalette.AmberWarn
                )
            }
            Text(
                text = if (cfg?.isAuthorized == true) {
                    "Termux POSIX Path: ${cfg.resolvedTermuxBasePath ?: "Tap Configure Path (SAF URI non-primary)"}"
                } else {
                    "Authorize a shared folder once via Android Storage Access Framework so Termux can compile exported Android projects."
                },
                style = MaterialTheme.typography.bodySmall,
                color = StudioPalette.TextSecondary
            )
            if (state.transferProgress.active) {
                val ratio = if (state.transferProgress.totalFiles > 0) {
                    state.transferProgress.copiedFiles.toFloat() / state.transferProgress.totalFiles.toFloat()
                } else 0f
                LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${state.transferProgress.phase} (${state.transferProgress.copiedFiles}/${state.transferProgress.totalFiles})",
                    fontSize = 11.sp,
                    color = StudioPalette.CyanAccent
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onAuthorizeSharedFolder,
                    modifier = Modifier.testTag("dashboard_authorize_shared_folder_button")
                ) {
                    Text(if (cfg?.isAuthorized == true) "Change Folder" else "Authorize Shared Folder")
                }
                if (cfg?.isAuthorized == true) {
                    OutlinedButton(onClick = onEditPosixPath) {
                        Text("Termux Path")
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentSummaryBar(state: DevStudioUiState) {
    val env = state.termuxStatus
    Card(
        colors = CardDefaults.cardColors(containerColor = StudioPalette.BgSurface),
        border = BorderStroke(1.dp, StudioPalette.BorderSubtle),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Runtime Status",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioPalette.TextPrimary
                )
                val termuxSummary = when {
                    env == null -> "Scanning device environment..."
                    env.isTermuxInstalled && env.isRunCommandPermissionGranted && env.hasLiveProbeCompleted ->
                        "Termux IPC + Toolchain Probed • Web HTTP Server Ready"
                    env.isTermuxInstalled && env.isRunCommandPermissionGranted ->
                        "Termux IPC Ready (Scan Inside Termux pending) • Web Server Ready"
                    env.isTermuxInstalled -> "Termux Installed (Permission needed) • Web Server Ready"
                    else -> "Local Web Server Ready • Termux not installed"
                }
                Text(
                    text = termuxSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioPalette.TextMuted
                )
            }
            StatusPill(
                text = if (state.previewUrl != null) "HTTP LIVE" else "SANDBOX",
                color = if (state.previewUrl != null) StudioPalette.EmeraldSuccess else StudioPalette.CyanAccent
            )
        }
    }
}

@Composable
private fun ProjectCardItem(
    project: DevProject,
    isSelected: Boolean,
    onOpenEditor: () -> Unit,
    onQuickRun: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) StudioPalette.BgElevated else StudioPalette.BgCard
        ),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) StudioPalette.CyanAccent else StudioPalette.BorderSubtle
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenEditor)
            .testTag("project_card_${project.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = if (project.type == ProjectType.ANDROID_KOTLIN) {
                                StudioPalette.EmeraldSuccess.copy(alpha = 0.16f)
                            } else {
                                StudioPalette.CyanAccent.copy(alpha = 0.16f)
                            },
                            shape = RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (project.type == ProjectType.ANDROID_KOTLIN) Icons.Default.Android else Icons.Default.Language,
                        contentDescription = project.type.label,
                        tint = if (project.type == ProjectType.ANDROID_KOTLIN) StudioPalette.EmeraldSuccess else StudioPalette.CyanAccent
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioPalette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${project.type.label} • ${project.packageName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioPalette.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("delete_project_${project.id}")
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete project", tint = StudioPalette.RoseError)
                }
            }
            if (project.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = project.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioPalette.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(project.buildStatus.label, StudioPalette.IndigoAccent)
                    StatusPill(project.runStatus.label, StudioPalette.CyanAccent)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onOpenEditor) {
                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Files")
                    }
                    Button(
                        onClick = onQuickRun,
                        colors = ButtonDefaults.buttonColors(containerColor = StudioPalette.CyanAccent)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (project.type == ProjectType.HTML_WEB) "Run" else "Build")
                    }
                }
            }
        }
    }
}

@Composable
fun StudioBuilderScreen(
    state: DevStudioUiState,
    onGenerate: (String, ProjectType, String?) -> Unit,
    onCancelAgent: () -> Unit
) {
    var selectedType by remember { mutableStateOf(ProjectType.ANDROID_KOTLIN) }
    var projectName by remember { mutableStateOf("") }
    var promptText by remember { mutableStateOf("") }

    val quickBlueprints = remember {
        listOf(
            "Build a modern scientific calculator app with calculation history" to ProjectType.ANDROID_KOTLIN,
            "Build an offline quick notes & checklist Kotlin app" to ProjectType.ANDROID_KOTLIN,
            "Build a responsive portfolio website with dark mode and interactive task board" to ProjectType.HTML_WEB,
            "Build a responsive expense tracker & math calculator web studio" to ProjectType.HTML_WEB
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.activeProjectLimitationNotice?.let { notice ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioPalette.BgSurface),
                    border = BorderStroke(1.dp, StudioPalette.AmberWarn.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = StudioPalette.AmberWarn, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(notice, style = MaterialTheme.typography.bodySmall, color = StudioPalette.TextSecondary)
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioPalette.BgCard),
                border = BorderStroke(1.dp, StudioPalette.BorderSubtle),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "AI App & Website Architect",
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioPalette.TextPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedType == ProjectType.ANDROID_KOTLIN,
                            onClick = { selectedType = ProjectType.ANDROID_KOTLIN },
                            label = { Text("Android Kotlin App") },
                            leadingIcon = { Icon(Icons.Default.Android, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.testTag("builder_type_android")
                        )
                        FilterChip(
                            selected = selectedType == ProjectType.HTML_WEB,
                            onClick = { selectedType = ProjectType.HTML_WEB },
                            label = { Text("HTML Website") },
                            leadingIcon = { Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.testTag("builder_type_web")
                        )
                    }

                    OutlinedTextField(
                        value = projectName,
                        onValueChange = { projectName = it },
                        label = { Text("Project Name (Optional)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("builder_project_name_input")
                    )

                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        label = { Text("Describe the App or Website in natural language") },
                        placeholder = { Text("e.g., Create a responsive Pomodoro timer & task planner...") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("builder_prompt_input")
                    )

                    if (state.isBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Agent Phase: ${state.agentPhase.label}",
                                color = StudioPalette.CyanAccent,
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(onClick = onCancelAgent, modifier = Modifier.testTag("builder_cancel_button")) {
                                Text("Cancel")
                            }
                        }
                    } else {
                        Button(
                            onClick = { onGenerate(promptText, selectedType, projectName.ifBlank { null }) },
                            enabled = promptText.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("builder_generate_button")
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Generate Real Project Workspace")
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "QUICK BLUEPRINTS",
                style = MaterialTheme.typography.labelMedium,
                color = StudioPalette.TextMuted
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(quickBlueprints) { (samplePrompt, sampleType) ->
                    Surface(
                        color = StudioPalette.BgSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, StudioPalette.BorderSubtle),
                        modifier = Modifier.clickable {
                            selectedType = sampleType
                            promptText = samplePrompt
                        }
                    ) {
                        Column(
                            modifier = Modifier
                                .width(220.dp)
                                .padding(12.dp)
                        ) {
                            StatusPill(sampleType.label, StudioPalette.CyanAccent)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = samplePrompt,
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioPalette.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (state.agentPhase != DevAgentPhase.IDLE && state.agentSteps.isNotEmpty()) {
            item {
                Text(
                    text = "RECENT AGENT TRACE",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioPalette.TextMuted
                )
            }
            items(state.agentSteps.takeLast(6), key = { it.id }) { step ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioPalette.BgSurface),
                    border = BorderStroke(1.dp, StudioPalette.BorderSubtle)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "[${step.phase.label}] ${step.title}",
                            color = if (step.isSuccess) StudioPalette.EmeraldSuccess else StudioPalette.RoseError,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(step.detail, style = MaterialTheme.typography.bodySmall, color = StudioPalette.TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(0.8.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
