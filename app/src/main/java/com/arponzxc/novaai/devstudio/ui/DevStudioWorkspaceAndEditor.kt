package com.arponzxc.novaai.devstudio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arponzxc.novaai.devstudio.model.DevChangeProposal
import com.arponzxc.novaai.devstudio.model.DevFileNode

@Composable
fun StudioWorkspaceScreen(
    state: DevStudioUiState,
    onSearchChange: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit
) {
    val project = state.activeProject
    if (project == null) {
        EmptySelectionNotice("Select or generate a project from Dashboard / AI Builder first.")
        return
    }

    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DevFileNode?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.fileSearchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Filter project files...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("workspace_search_input")
            )
            IconButton(
                onClick = { showCreateFileDialog = true },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("workspace_create_file_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = "Create file", tint = StudioPalette.CyanAccent)
            }
            IconButton(
                onClick = { showCreateFolderDialog = true },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("workspace_create_folder_button")
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "Create folder", tint = StudioPalette.IndigoAccent)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "${project.name} • ${state.fileNodes.size} item(s)",
            style = MaterialTheme.typography.labelMedium,
            color = StudioPalette.TextMuted
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.fileNodes, key = { it.relativePath }) { node ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioPalette.BgCard),
                    border = BorderStroke(1.dp, StudioPalette.BorderSubtle),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !node.isDirectory) { onOpenFile(node.relativePath) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (12 + node.depth * 12).dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (node.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = node.language,
                            tint = if (node.isDirectory) StudioPalette.AmberWarn else StudioPalette.CyanAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(node.name, color = StudioPalette.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = if (node.isDirectory) node.relativePath else "${node.language.uppercase()} • ${node.sizeBytes} B",
                                color = StudioPalette.TextMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(
                            onClick = { renameTarget = node },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.DriveFileRenameOutline, contentDescription = "Rename", tint = StudioPalette.TextSecondary)
                        }
                        IconButton(
                            onClick = { onDeleteEntry(node.relativePath) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = StudioPalette.RoseError)
                        }
                    }
                }
            }
        }
    }

    if (showCreateFileDialog) {
        SingleInputDialog(
            title = "New Workspace File",
            label = "Relative Path (e.g. app/src/main/java/Helper.kt or app.js)",
            initial = "",
            onDismiss = { showCreateFileDialog = false },
            onConfirm = {
                showCreateFileDialog = false
                if (it.isNotBlank()) onCreateFile(it.trim())
            }
        )
    }
    if (showCreateFolderDialog) {
        SingleInputDialog(
            title = "New Workspace Folder",
            label = "Folder Path (e.g. components or assets/icons)",
            initial = "",
            onDismiss = { showCreateFolderDialog = false },
            onConfirm = {
                showCreateFolderDialog = false
                if (it.isNotBlank()) onCreateFolder(it.trim())
            }
        )
    }
    renameTarget?.let { node ->
        SingleInputDialog(
            title = "Rename ${node.name}",
            label = "New Name",
            initial = node.name,
            onDismiss = { renameTarget = null },
            onConfirm = {
                renameTarget = null
                if (it.isNotBlank()) onRenameEntry(node.relativePath, it.trim())
            }
        )
    }
}

@Composable
fun StudioEditorScreen(
    state: DevStudioUiState,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRevertBackup: () -> Unit,
    onExplainCode: () -> Unit,
    onRequestAiEdit: (String) -> Unit
) {
    val filePath = state.selectedFilePath
    if (state.activeProject == null || filePath == null) {
        EmptySelectionNotice("Select a file in the Files tab to inspect or edit code.")
        return
    }

    var showFindReplace by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var showAiEditDialog by remember { mutableStateOf(false) }
    var showSyntaxHighlightPreview by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Editor Header & Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = filePath.substringAfterLast('/'),
                        color = StudioPalette.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    if (state.hasUnsavedChanges) {
                        Spacer(Modifier.width(6.dp))
                        StatusPill("UNSAVED", StudioPalette.AmberWarn)
                    }
                }
                Text(filePath, style = MaterialTheme.typography.bodySmall, color = StudioPalette.TextMuted)
            }
            Row {
                IconButton(
                    onClick = onUndo,
                    enabled = state.undoStack.isNotEmpty(),
                    modifier = Modifier.size(48.dp).testTag("editor_undo_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
                IconButton(
                    onClick = onRedo,
                    enabled = state.redoStack.isNotEmpty(),
                    modifier = Modifier.size(48.dp).testTag("editor_redo_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                }
                IconButton(
                    onClick = { showFindReplace = !showFindReplace },
                    modifier = Modifier.size(48.dp).testTag("editor_find_button")
                ) {
                    Icon(Icons.Default.FindReplace, contentDescription = "Find and Replace")
                }
                IconButton(
                    onClick = onRevertBackup,
                    modifier = Modifier.size(48.dp).testTag("editor_revert_button")
                ) {
                    Icon(Icons.Default.History, contentDescription = "Revert last backup")
                }
                IconButton(
                    onClick = onSave,
                    modifier = Modifier.size(48.dp).testTag("editor_save_button")
                ) {
                    Icon(Icons.Default.Save, contentDescription = "Save file", tint = StudioPalette.EmeraldSuccess)
                }
            }
        }

        if (showFindReplace) {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioPalette.BgSurface),
                border = BorderStroke(1.dp, StudioPalette.BorderSubtle),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = findText,
                        onValueChange = { findText = it },
                        placeholder = { Text("Find") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = replaceText,
                        onValueChange = { replaceText = it },
                        placeholder = { Text("Replace") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (findText.isNotEmpty()) {
                                onContentChange(state.editorContent.replace(findText, replaceText))
                            }
                        }
                    ) {
                        Text("All")
                    }
                }
            }
        }

        // AI Coding Assistant Action Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onExplainCode,
                modifier = Modifier.testTag("editor_ai_explain_button")
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("AI Explain")
            }
            OutlinedButton(
                onClick = { showAiEditDialog = true },
                modifier = Modifier.testTag("editor_ai_edit_button")
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("AI Edit / Fix")
            }
            OutlinedButton(
                onClick = { showSyntaxHighlightPreview = !showSyntaxHighlightPreview },
                modifier = Modifier.testTag("editor_syntax_toggle_button")
            ) {
                Text(if (showSyntaxHighlightPreview) "Edit Mode" else "Syntax Color View")
            }
        }

        state.aiExplainOutput?.let { explanation ->
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioPalette.BgElevated),
                border = BorderStroke(1.dp, StudioPalette.CyanAccent.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioPalette.TextPrimary,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Code Canvas with Line Numbers
        val lines = remember(state.editorContent) { state.editorContent.lines() }
        val lineCountText = remember(lines.size) { (1..lines.size.coerceAtLeast(1)).joinToString("\n") }

        Surface(
            color = StudioPalette.CodeBg,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, StudioPalette.BorderSubtle),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                Text(
                    text = lineCountText,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = StudioPalette.CodeComment
                    ),
                    modifier = Modifier.padding(end = 10.dp)
                )
                Box(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    if (showSyntaxHighlightPreview) {
                        Text(
                            text = highlightSyntax(state.editorContent),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        )
                    } else {
                        BasicTextField(
                            value = state.editorContent,
                            onValueChange = onContentChange,
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = StudioPalette.TextPrimary
                            ),
                            cursorBrush = SolidColor(StudioPalette.CyanAccent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 360.dp)
                                .testTag("code_editor_textfield")
                        )
                    }
                }
            }
        }
    }

    if (showAiEditDialog) {
        SingleInputDialog(
            title = "AI Code Assistant",
            label = "Describe bug fix, refactor, or enhancement for $filePath",
            initial = "",
            onDismiss = { showAiEditDialog = false },
            onConfirm = {
                showAiEditDialog = false
                if (it.isNotBlank()) onRequestAiEdit(it.trim())
            }
        )
    }
}

@Composable
fun ChangeDiffReviewDialog(
    proposal: DevChangeProposal,
    onDismiss: () -> Unit,
    onApprove: (DevChangeProposal) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPalette.BgCard,
        title = { Text(proposal.title, color = StudioPalette.TextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(proposal.rationale, style = MaterialTheme.typography.bodySmall, color = StudioPalette.TextSecondary)
                proposal.diffs.forEach { diff ->
                    Surface(
                        color = StudioPalette.CodeBg,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, StudioPalette.BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(diff.summary, style = MaterialTheme.typography.labelMedium, color = StudioPalette.CyanAccent)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = diff.unifiedDiff,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = StudioPalette.TextPrimary
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApprove(proposal) }, modifier = Modifier.testTag("diff_apply_button")) {
                Text("Apply Changes (Backup Auto-Saved)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Discard") }
        }
    )
}

@Composable
fun EmptySelectionNotice(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = StudioPalette.TextMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SingleInputDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioPalette.BgCard,
        title = { Text(title, color = StudioPalette.TextPrimary) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { Button(onClick = { onConfirm(value) }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun highlightSyntax(code: String): AnnotatedString {
    val keywords = setOf(
        "package", "import", "class", "object", "fun", "val", "var", "if", "else", "when",
        "return", "override", "private", "public", "true", "false", "null", "function",
        "const", "let", "export", "async", "await"
    )
    return buildAnnotatedString {
        code.lines().forEachIndexed { idx, line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("<!--") -> {
                    withStyle(SpanStyle(color = StudioPalette.CodeComment)) { append(line) }
                }
                else -> {
                    val words = line.split(Regex("(?<=\\b)|(?=\\b)"))
                    words.forEach { token ->
                        when {
                            token in keywords -> withStyle(SpanStyle(color = StudioPalette.CodeKeyword)) { append(token) }
                            token.startsWith("\"") || token.startsWith("'") -> withStyle(SpanStyle(color = StudioPalette.CodeString)) { append(token) }
                            token.firstOrNull()?.isUpperCase() == true -> withStyle(SpanStyle(color = StudioPalette.CodeType)) { append(token) }
                            else -> withStyle(SpanStyle(color = StudioPalette.TextPrimary)) { append(token) }
                        }
                    }
                }
            }
            if (idx < code.lines().lastIndex) append('\n')
        }
    }
}
