package com.arponzxc.novaai.devstudio.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arponzxc.novaai.devstudio.model.ProjectType
import com.arponzxc.novaai.devstudio.model.TerminalSource
import com.arponzxc.novaai.devstudio.model.TermuxCommandState
import com.arponzxc.novaai.devstudio.security.DevSandboxGuard
import com.arponzxc.novaai.devstudio.termux.TermuxBridge
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun StudioWebPreviewScreen(
    state: DevStudioUiState,
    onStartPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onToggleViewport: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleAutoReload: () -> Unit,
    onPollChanges: () -> Unit,
    onConsoleMessage: (String, String, String, Int) -> Unit
) {
    val project = state.activeProject
    if (project == null) {
        EmptySelectionNotice("Select or create an HTML Web project to launch Live Preview.")
        return
    }
    if (project.type != ProjectType.HTML_WEB) {
        EmptySelectionNotice("Live HTTP Preview supports HTML Web apps. Use 'Build & Termux' for Android Kotlin projects.")
        return
    }

    var manualReloadCount by remember { mutableIntStateOf(0) }
    var showConsole by remember { mutableStateOf(false) }

    // Poll local project files for edits without restarting the embedded HTTP server
    LaunchedEffect(state.previewUrl, state.previewAutoReloadEnabled) {
        while (state.previewUrl != null && state.previewAutoReloadEnabled) {
            delay(1200L)
            onPollChanges()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(project.name, color = StudioPalette.TextPrimary, fontWeight = FontWeight.Bold)
                Text(
                    text = state.previewUrl?.let {
                        "$it • ${if (state.previewAutoReloadEnabled) "Auto-Reload ON" else "Manual Reload"}"
                    } ?: "Preview server stopped",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.previewUrl != null) StudioPalette.EmeraldSuccess else StudioPalette.TextMuted
                )
            }
            Row {
                if (state.previewUrl == null) {
                    Button(onClick = onStartPreview, modifier = Modifier.testTag("preview_start_button")) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Serve")
                    }
                } else {
                    IconButton(onClick = onToggleAutoReload, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.Autorenew,
                            contentDescription = "Toggle auto reload",
                            tint = if (state.previewAutoReloadEnabled) StudioPalette.EmeraldSuccess else StudioPalette.TextMuted
                        )
                    }
                    IconButton(onClick = { manualReloadCount++ }, modifier = Modifier.size(48.dp).testTag("preview_reload_button")) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload page")
                    }
                    IconButton(onClick = onToggleViewport, modifier = Modifier.size(48.dp).testTag("preview_viewport_button")) {
                        Icon(
                            if (state.isPreviewDesktopMode) Icons.Default.DesktopWindows else Icons.Default.PhoneAndroid,
                            contentDescription = "Toggle Desktop or Mobile view"
                        )
                    }
                    IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(48.dp)) {
                        Icon(
                            if (state.isPreviewFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = "Toggle fullscreen"
                        )
                    }
                    IconButton(onClick = onStopPreview, modifier = Modifier.size(48.dp).testTag("preview_stop_button")) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop server", tint = StudioPalette.RoseError)
                    }
                }
            }
        }

        val previewUrl = state.previewUrl
        if (previewUrl == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(onClick = onStartPreview) {
                    Text("Start Embedded HTTP Server & Load Index")
                }
            }
        } else {
            val reloadRevision = "${state.previewLiveReloadToken}_$manualReloadCount"
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, StudioPalette.BorderSubtle),
                    modifier = if (state.isPreviewDesktopMode) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .widthIn(max = 380.dp)
                            .fillMaxSize()
                    }
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    loadWithOverviewMode = true
                                    useWideViewPort = state.isPreviewDesktopMode
                                    cacheMode = WebSettings.LOAD_NO_CACHE
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val host = request?.url?.host.orEmpty()
                                        // Restrict sandbox preview navigation strictly to loopback HTTP server
                                        return host != "127.0.0.1" && host != "localhost"
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                                        onConsoleMessage(
                                            msg.messageLevel().name,
                                            msg.message(),
                                            msg.sourceId().orEmpty(),
                                            msg.lineNumber()
                                        )
                                        return true
                                    }
                                }
                                loadUrl(previewUrl)
                            }
                        },
                        update = { webView ->
                            webView.settings.useWideViewPort = state.isPreviewDesktopMode
                            val target = "$previewUrl/?_r=$reloadRevision"
                            if (webView.tag != target) {
                                webView.tag = target
                                webView.loadUrl(target)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { showConsole = !showConsole },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("JS Console (${state.webConsoleLogs.size})")
        }

        if (showConsole && state.webConsoleLogs.isNotEmpty()) {
            LazyColumn(modifier = Modifier.height(140.dp).padding(top = 6.dp)) {
                items(state.webConsoleLogs, key = { it.id }) { log ->
                    Text(
                        text = "[${log.level}] ${log.sourceId}:${log.lineNumber} -> ${log.message}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (log.level == "ERROR") StudioPalette.RoseError else StudioPalette.TextSecondary,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StudioBuildAndTermuxScreen(
    state: DevStudioUiState,
    onValidateProject: () -> Unit,
    onAiAutoFix: () -> Unit,
    onBuildApkTermux: () -> Unit,
    onCancelBuildOrCommand: () -> Unit,
    onInstallApk: () -> Unit,
    onLaunchInstalledApp: () -> Unit,
    onAuthorizeSharedFolder: () -> Unit,
    onRefreshTermux: () -> Unit,
    onLiveProbeTermux: () -> Unit,
    onRunCommand: (String) -> Unit,
    onClearTerminal: () -> Unit
) {
    val context = LocalContext.current
    var commandInput by remember { mutableStateOf("") }
    var showSetupGuide by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Project Build & Validation Controls
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioPalette.BgCard),
                border = BorderStroke(1.dp, StudioPalette.BorderSubtle),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Build, Validation & APK Manager",
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioPalette.TextPrimary
                    )
                    Text(
                        text = state.activeProject?.let { "${it.name} • Build: ${it.buildStatus.label} • Run: ${it.runStatus.label}" }
                            ?: "No active project selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioPalette.TextMuted
                    )

                    state.activeProject?.let { proj ->
                        if (proj.type == ProjectType.ANDROID_KOTLIN) {
                            Text(
                                text = "Gradle Wrapper: ${if (proj.hasGradleWrapper) "./gradlew present" else "Not bundled (uses Termux gradle)"}",
                                fontSize = 11.sp,
                                color = if (proj.hasGradleWrapper) StudioPalette.EmeraldSuccess else StudioPalette.AmberWarn
                            )
                            Text(
                                text = "Shared Termux Dir: ${proj.sharedWorkingDir ?: state.sharedWorkspace?.resolvedTermuxBasePath ?: "Not yet synced (Authorize Shared Folder)"}",
                                fontSize = 11.sp,
                                color = StudioPalette.TextSecondary
                            )
                        }
                    }

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
                    } else if (state.isCommandRunning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onValidateProject,
                            enabled = state.activeProject != null && !state.isCommandRunning,
                            modifier = Modifier.testTag("build_validate_button")
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Validate")
                        }
                        if (state.activeProject?.type == ProjectType.ANDROID_KOTLIN) {
                            if (state.sharedWorkspace?.isAuthorized != true) {
                                OutlinedButton(onClick = onAuthorizeSharedFolder) {
                                    Text("Authorize Folder")
                                }
                            }
                            if (state.isCommandRunning) {
                                Button(
                                    onClick = onCancelBuildOrCommand,
                                    colors = ButtonDefaults.buttonColors(containerColor = StudioPalette.RoseError),
                                    modifier = Modifier.testTag("build_cancel_button")
                                ) {
                                    Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Cancel Build")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onBuildApkTermux,
                                    modifier = Modifier.testTag("build_termux_apk_button")
                                ) {
                                    Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Build APK")
                                }
                            }
                        }
                    }

                    state.lastApkVerification?.takeIf { it.isValid }?.let { apk ->
                        Surface(
                            color = StudioPalette.BgSurface,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, StudioPalette.EmeraldSuccess.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Verified APK: ${apk.fileName} (${apk.sizeBytes / 1024} KB)",
                                    color = StudioPalette.EmeraldSuccess,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    "Package: ${apk.packageName ?: state.activeProject?.packageName ?: "Unknown"} • v${apk.versionName ?: "1.0"}",
                                    color = StudioPalette.TextSecondary,
                                    fontSize = 11.sp
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = onInstallApk,
                                        modifier = Modifier.testTag("build_install_apk_button")
                                    ) {
                                        Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Install APK")
                                    }
                                    OutlinedButton(
                                        onClick = onLaunchInstalledApp,
                                        modifier = Modifier.testTag("build_launch_app_button")
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Launch App")
                                    }
                                }
                            }
                        }
                    }

                    state.validationResult?.let { vr ->
                        Text(
                            text = vr.summary,
                            color = if (vr.isValid) StudioPalette.EmeraldSuccess else StudioPalette.RoseError,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (state.compilerDiagnostics.isNotEmpty()) {
                        val errorCount = state.compilerDiagnostics.count { it.severity == "ERROR" }
                        val warningCount = state.compilerDiagnostics.size - errorCount
                        if (errorCount > 0) {
                            OutlinedButton(
                                onClick = onAiAutoFix,
                                enabled = state.buildRetryCount < DevSandboxGuard.MAX_BUILD_AUTO_FIX_RETRIES,
                                modifier = Modifier.testTag("build_ai_autofix_button")
                            ) {
                                Text(
                                    "AI Auto-Fix $errorCount Error(s) " +
                                        "(${state.buildRetryCount}/${DevSandboxGuard.MAX_BUILD_AUTO_FIX_RETRIES})"
                                )
                            }
                        }
                        if (warningCount > 0) {
                            Text(
                                text = "$warningCount warning(s) - these do not block the build",
                                color = StudioPalette.AmberWarn,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        state.compilerDiagnostics.forEach { diag ->
                            val isError = diag.severity == "ERROR"
                            val tone = if (isError) StudioPalette.RoseError else StudioPalette.AmberWarn
                            Surface(
                                color = StudioPalette.BgSurface,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, tone.copy(alpha = 0.4f))
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("${diag.category} • ${diag.file}:${diag.line}", color = tone, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text(diag.message, color = StudioPalette.TextPrimary, fontSize = 12.sp)
                                    Text("Fix: ${diag.suggestedFix}", color = StudioPalette.CyanAccent, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Termux Environment Matrix
        item {
            val env = state.termuxStatus
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioPalette.BgCard),
                border = BorderStroke(1.dp, StudioPalette.BorderSubtle),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Termux Toolchain Inspector", style = MaterialTheme.typography.titleMedium, color = StudioPalette.TextPrimary)
                            Text(
                                text = if (env?.hasLiveProbeCompleted == true) "Verified via live Termux IPC callback" else "Live Termux probe required for CLI binaries",
                                fontSize = 11.sp,
                                color = StudioPalette.TextMuted
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = onRefreshTermux) { Text("Status") }
                            Button(
                                onClick = onLiveProbeTermux,
                                enabled = !state.isCommandRunning,
                                modifier = Modifier.testTag("termux_live_probe_button")
                            ) { Text("Scan Termux") }
                        }
                    }
                    env?.tools?.forEach { tool ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(tool.name, color = StudioPalette.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                text = tool.statusDetail,
                                color = if (tool.isDetected) StudioPalette.EmeraldSuccess else StudioPalette.TextMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showSetupGuide = !showSetupGuide }) {
                            Text(if (showSetupGuide) "Hide Setup Guide" else "Termux Setup Guide")
                        }
                        if (env?.isTermuxInstalled == true) {
                            OutlinedButton(onClick = { TermuxBridge.openTermuxApp(context) }) {
                                Text("Open Termux")
                            }
                        }
                    }
                    if (showSetupGuide) {
                        Surface(color = StudioPalette.CodeBg, shape = RoundedCornerShape(8.dp)) {
                            Text(
                                text = TermuxBridge.SETUP_GUIDE_TEXT.trim(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = StudioPalette.TextSecondary,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        // Sandboxed Terminal Console
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioPalette.BgCard),
                border = BorderStroke(1.dp, StudioPalette.BorderSubtle),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Termux Execution Console (Redaction Active)", style = MaterialTheme.typography.titleMedium, color = StudioPalette.TextPrimary)
                        OutlinedButton(onClick = onClearTerminal) { Text("Clear") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = commandInput,
                            onValueChange = { commandInput = it },
                            placeholder = { Text("e.g., gradle -v, java -version, node -v") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("termux_command_input")
                        )
                        IconButton(
                            onClick = {
                                val cmd = commandInput.trim()
                                if (cmd.isNotEmpty()) {
                                    commandInput = ""
                                    onRunCommand(cmd)
                                }
                            },
                            enabled = !state.isCommandRunning,
                            modifier = Modifier.size(48.dp).testTag("termux_run_command_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run command", tint = StudioPalette.CyanAccent)
                        }
                    }
                }
            }
        }

        items(state.terminalLogs, key = { it.id }) { entry ->
            Surface(
                color = StudioPalette.CodeBg,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, StudioPalette.BorderSubtle)
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val badgeColor = when (entry.source) {
                        TerminalSource.AGENT -> StudioPalette.IndigoAccent
                        TerminalSource.BUILD -> StudioPalette.AmberWarn
                        else -> StudioPalette.CyanAccent
                    }
                    val stateColor = when (entry.executionState) {
                        TermuxCommandState.COMPLETED -> StudioPalette.EmeraldSuccess
                        TermuxCommandState.FAILED, TermuxCommandState.TIMED_OUT -> StudioPalette.RoseError
                        else -> StudioPalette.AmberWarn
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(entry.source.label, badgeColor)
                        StatusPill(
                            text = "${entry.executionState.label} • exit=${entry.exitCode ?: "N/A"} • ${entry.executionTimeMs}ms",
                            color = stateColor
                        )
                    }
                    Text("$ ${entry.command}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = StudioPalette.TextPrimary)
                    if (entry.workingDir.isNotBlank()) {
                        Text("cwd: ${entry.workingDir}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = StudioPalette.TextMuted)
                    }
                    Text(
                        text = entry.output,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = StudioPalette.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun StudioAgentTrackerScreen(state: DevStudioUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioPalette.BgCard),
                border = BorderStroke(1.dp, StudioPalette.BorderSubtle),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Autonomous Development Agent", style = MaterialTheme.typography.titleMedium, color = StudioPalette.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text("Current Phase: ${state.agentPhase.label}", color = StudioPalette.CyanAccent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (state.agentSteps.isEmpty()) {
            item {
                Text("Start an AI build in the AI Builder tab to observe step-by-step execution.", color = StudioPalette.TextMuted)
            }
        } else {
            items(state.agentSteps, key = { it.id }) { step ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = StudioPalette.BgSurface),
                    border = BorderStroke(1.dp, StudioPalette.BorderSubtle)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "${step.phase.label} • ${step.title}",
                            color = if (step.isSuccess) StudioPalette.EmeraldSuccess else StudioPalette.RoseError,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(step.detail, color = StudioPalette.TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
