package com.arponzxc.novaai.network.apimanager

import com.arponzxc.novaai.ui.theme.NovaUi
import com.arponzxc.novaai.ui.theme.NovaPageTheme
import com.arponzxc.novaai.ui.theme.NovaPageHeader
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.background.JarvisConversationService
import kotlinx.coroutines.launch
import java.util.UUID

class ApiManagerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        com.arponzxc.novaai.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        MultiProviderRouter.syncGeminiWithPreferences(this)

        setContent {
            NovaPageTheme {
                ApiManagerScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApiManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = remember { SecureKeyStorage(context) }

    var refreshTick by remember { mutableIntStateOf(0) }
    val providers = remember(refreshTick) { MultiProviderRouter.getAllProviders(context) }
    val selectedProviderId = remember(refreshTick) { storage.getSelectedProvider() }
    val usageMap = remember(refreshTick) { storage.getUsageStatsMap() }

    var testingProviderId by remember { mutableStateOf<String?>(null) }
    var testMessageByProvider by remember { mutableStateOf<Map<String, ProviderTestResult>>(emptyMap()) }

    var addKeyDialogProvider by remember { mutableStateOf<AiProviderConfig?>(null) }
    var showCustomProviderDialog by remember { mutableStateOf(false) }

    fun triggerRefresh() {
        refreshTick++
        MultiProviderRouter.syncGeminiWithPreferences(context)
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
                    .widthIn(max = 680.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                NovaPageHeader(
                    title = "Multi-Provider AI Manager",
                    subtitle = "Keystore-encrypted routing • Rotation & health checks",
                    onBack = onBack,
                    backTestTag = "api_manager_back_button",
                    actions = {
                        Button(
                            onClick = { showCustomProviderDialog = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("add_custom_provider_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = NovaUi.accent)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Custom", fontSize = 12.sp)
                        }
                    }
                )

                // Security status banner
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (storage.canStoreSecurely) NovaUi.accentSoft else NovaUi.errorBg
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Icon(
                            imageVector = if (storage.canStoreSecurely) Icons.Rounded.Security else Icons.Rounded.WarningAmber,
                            contentDescription = "Encryption status",
                            tint = if (storage.canStoreSecurely) NovaUi.success else NovaUi.error
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (storage.canStoreSecurely) {
                                    "AES-256 Hardware-Backed Keystore Active"
                                } else {
                                    "Secure Storage Unavailable (Fail-Closed)"
                                },
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = NovaUi.textPrimary
                            )
                            Text(
                                text = if (storage.canStoreSecurely) {
                                    "API keys are never logged, exposed unmasked in lists, or saved in plaintext."
                                } else {
                                    "Key writes are blocked because Android Keystore could not be initialized."
                                },
                                fontSize = 11.sp,
                                color = NovaUi.textSecondary
                            )
                        }
                    }
                }

                // Capability note if non-Gemini selected
                val activeProvider = providers.firstOrNull { it.id == selectedProviderId } ?: providers.first()
                if (activeProvider.id != "gemini") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = NovaUi.accentSoft),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Icon(Icons.Rounded.Hub, contentDescription = null, tint = NovaUi.accent2)
                            Text(
                                text = "Active Default: ${activeProvider.name}. Text Chat, Research, File AI & Vision use ${activeProvider.apiFormat.label}. Live Duplex Audio WebSocket remains Gemini-only (Unsupported on non-Gemini REST endpoints).",
                                fontSize = 11.5.sp,
                                color = NovaUi.textPrimary
                            )
                        }
                    }
                }

                // Providers list
                providers.forEach { provider ->
                    val isSelected = provider.id == selectedProviderId
                    val keys = remember(refreshTick, provider.id) { storage.getApiKeyEntries(provider.id) }
                    val activeModel = remember(refreshTick, provider.id) { storage.getSelectedModel(provider.id) }
                    val stats = usageMap[provider.id] ?: ProviderUsageStats(provider.id)
                    val testRes = testMessageByProvider[provider.id]

                    ProviderCard(
                        provider = provider,
                        isSelected = isSelected,
                        keys = keys,
                        activeModel = activeModel,
                        stats = stats,
                        testResult = testRes,
                        isTesting = testingProviderId == provider.id,
                        onSelectDefault = {
                            if (!provider.isEnabled) {
                                Toast.makeText(context, "Enable ${provider.name} first.", Toast.LENGTH_SHORT).show()
                                return@ProviderCard
                            }
                            runCatching {
                                storage.saveSelectedProvider(provider.id)
                                storage.saveSelectedModel(activeModel.ifBlank { provider.defaultModel }, provider.id)
                                if (provider.id == "gemini") {
                                    JarvisApp.instance.preferences.apiKey = storage.getApiKey("gemini")
                                    JarvisConversationService.restartSession()
                                }
                                triggerRefresh()
                            }.onFailure {
                                Toast.makeText(context, it.message ?: "Unable to switch provider.", Toast.LENGTH_LONG).show()
                            }
                        },
                        onToggleEnabled = { enabled ->
                            runCatching {
                                if (provider.isBuiltin) {
                                    storage.setBuiltinProviderEnabled(provider.id, enabled)
                                } else {
                                    storage.saveCustomProvider(provider.copy(isEnabled = enabled))
                                }
                                triggerRefresh()
                            }
                        },
                        onSelectModel = { model ->
                            runCatching {
                                storage.saveSelectedModel(model, provider.id)
                                triggerRefresh()
                            }
                        },
                        onAddKey = { addKeyDialogProvider = provider },
                        onToggleKey = { keyId, enabled ->
                            runCatching {
                                storage.setApiKeyEnabled(provider.id, keyId, enabled)
                                triggerRefresh()
                            }
                        },
                        onDeleteKey = { keyId ->
                            runCatching {
                                storage.removeApiKeyEntry(provider.id, keyId)
                                triggerRefresh()
                            }
                        },
                        onTestConnection = {
                            testingProviderId = provider.id
                            scope.launch {
                                val res = MultiProviderRouter.testProviderConnection(context, provider, activeModel)
                                testMessageByProvider = testMessageByProvider + (provider.id to res)
                                testingProviderId = null
                                triggerRefresh()
                            }
                        },
                        onDeleteCustomProvider = if (!provider.isBuiltin) {
                            {
                                storage.deleteCustomProvider(provider.id)
                                triggerRefresh()
                            }
                        } else null
                    )
                }

                // Usage summary & reset
                Card(
                    colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.Analytics, contentDescription = null, tint = NovaUi.accent)
                            val totalReqs = usageMap.values.sumOf { it.totalRequests }
                            val totalRotations = usageMap.values.sumOf { it.keyRotations }
                            Column {
                                Text("Usage Metrics", color = NovaUi.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(
                                    "$totalReqs requests • $totalRotations auto key rotations",
                                    color = NovaUi.textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                storage.resetUsageStats(null)
                                triggerRefresh()
                            },
                            modifier = Modifier.testTag("reset_usage_stats_button")
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reset", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    addKeyDialogProvider?.let { targetProvider ->
        AddApiKeyDialog(
            providerName = targetProvider.name,
            onDismiss = { addKeyDialogProvider = null },
            onSave = { label, key ->
                runCatching {
                    storage.addApiKeyEntry(targetProvider.id, label, key)
                    addKeyDialogProvider = null
                    triggerRefresh()
                    Toast.makeText(context, "Key encrypted & saved.", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, it.message ?: "Failed saving key.", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    if (showCustomProviderDialog) {
        AddCustomProviderDialog(
            onDismiss = { showCustomProviderDialog = false },
            onSave = { config, apiKey ->
                runCatching {
                    storage.saveCustomProvider(config)
                    storage.saveSelectedModel(config.defaultModel, config.id)
                    if (apiKey.isNotBlank()) {
                        storage.addApiKeyEntry(config.id, "Primary Key", apiKey)
                    }
                    showCustomProviderDialog = false
                    triggerRefresh()
                    Toast.makeText(context, "Custom provider '${config.name}' saved.", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, it.message ?: "Failed saving custom provider.", Toast.LENGTH_LONG).show()
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    provider: AiProviderConfig,
    isSelected: Boolean,
    keys: List<ApiKeyEntry>,
    activeModel: String,
    stats: ProviderUsageStats,
    testResult: ProviderTestResult?,
    isTesting: Boolean,
    onSelectDefault: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectModel: (String) -> Unit,
    onAddKey: () -> Unit,
    onToggleKey: (String, Boolean) -> Unit,
    onDeleteKey: (String) -> Unit,
    onTestConnection: () -> Unit,
    onDeleteCustomProvider: (() -> Unit)?
) {
    val borderColor = if (isSelected) NovaUi.accent else NovaUi.border
    Card(
        colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                !provider.isEnabled -> NovaUi.textMuted
                                keys.any { it.isEnabled } -> NovaUi.success
                                else -> NovaUi.warn
                            }
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = NovaUi.textPrimary
                    )
                    Text(
                        text = "${provider.apiFormat.name} • ${provider.baseUrl}",
                        fontSize = 11.sp,
                        color = NovaUi.textSecondary
                    )
                }
                Switch(
                    checked = provider.isEnabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            // Models chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                provider.availableModels.forEach { model ->
                    FilterChip(
                        selected = activeModel == model,
                        onClick = { onSelectModel(model) },
                        label = { Text(model.removePrefix("models/"), fontSize = 11.sp) }
                    )
                }
            }

            // Masked API Keys + rotation list
            HorizontalDivider(color = NovaUi.border)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Encrypted API Keys (${keys.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NovaUi.textSecondary
                )
                TextButton(
                    onClick = onAddKey,
                    modifier = Modifier.testTag("add_key_${provider.id}")
                ) {
                    Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Key", fontSize = 12.sp)
                }
            }

            if (keys.isEmpty()) {
                Text(
                    text = "No API key stored for ${provider.name}.",
                    fontSize = 11.5.sp,
                    color = NovaUi.warn
                )
            } else {
                keys.forEach { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(NovaUi.cardSolid)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = NovaUi.textPrimary
                            )
                            Text(
                                text = entry.maskedKey + if (entry.failureCount > 0) " • ${entry.failureCount} fails" else "",
                                fontSize = 11.sp,
                                color = NovaUi.textSecondary
                            )
                        }
                        Switch(
                            checked = entry.isEnabled,
                            onCheckedChange = { onToggleKey(entry.id, it) }
                        )
                        IconButton(onClick = { onDeleteKey(entry.id) }) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = "Delete key",
                                tint = NovaUi.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Stats & Test feedback
            Text(
                text = "Stats: ${stats.totalRequests} calls (${stats.successfulRequests} OK, ${stats.failedRequests} failed) • Last: ${stats.lastStatus}",
                fontSize = 11.sp,
                color = NovaUi.textSecondary
            )

            AnimatedVisibility(visible = testResult != null) {
                testResult?.let { res ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (res.success) NovaUi.successBg else NovaUi.errorBg)
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = if (res.success) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = if (res.success) NovaUi.success else NovaUi.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(res.message, fontSize = 11.5.sp, color = NovaUi.textPrimary)
                    }
                }
            }

            // Card Actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onTestConnection,
                    enabled = !isTesting,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("test_provider_${provider.id}")
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test", fontSize = 12.sp)
                }

                Button(
                    onClick = onSelectDefault,
                    enabled = !isSelected && provider.isEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("select_provider_${provider.id}"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) NovaUi.success else NovaUi.accent
                    )
                ) {
                    Text(
                        text = if (isSelected) "Active Default" else "Set Default",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (onDeleteCustomProvider != null) {
                    IconButton(onClick = onDeleteCustomProvider) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "Delete custom provider",
                            tint = NovaUi.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddApiKeyDialog(
    providerName: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var label by remember { mutableStateOf("Backup / Rotation Key") }
    var rawKey by remember { mutableStateOf("") }
    var revealKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add API Key • $providerName", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Key Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = rawKey,
                    onValueChange = { rawKey = it },
                    label = { Text("API Key (Encrypted in Keystore)") },
                    singleLine = true,
                    visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { revealKey = !revealKey }) {
                            Icon(
                                imageVector = if (revealKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "Toggle key visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(label.trim(), rawKey.trim()) },
                enabled = rawKey.isNotBlank()
            ) {
                Text("Save Encrypted")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddCustomProviderDialog(
    onDismiss: () -> Unit,
    onSave: (AiProviderConfig, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("https://") }
    var defaultModel by remember { mutableStateOf("") }
    var headerPair by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(AiProviderFormat.OPENAI) }
    var supportsVision by remember { mutableStateOf(true) }
    var allowHttpRisk by remember { mutableStateOf(false) }

    val policyCheck = remember(baseUrl, allowHttpRisk) {
        MultiProviderRouter.validateEndpointPolicy(baseUrl, allowHttpRisk)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom AI Provider", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Provider Name (e.g. OpenRouter / Groq / Ollama)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL (HTTPS recommended)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (policyCheck.requiresHttpsWarning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(NovaUi.warnBg)
                            .padding(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Insecure HTTP Warning", color = NovaUi.warn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Traffic over plain HTTP is unencrypted. Confirm risk to allow.", color = NovaUi.warn, fontSize = 10.5.sp)
                        }
                        Switch(checked = allowHttpRisk, onCheckedChange = { allowHttpRisk = it })
                    }
                } else if (!policyCheck.isValid && baseUrl != "https://") {
                    Text(policyCheck.errorMessage ?: "Invalid URL", color = NovaUi.error, fontSize = 11.sp)
                }

                Text("API Format", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AiProviderFormat.entries.forEach { f ->
                        FilterChip(
                            selected = format == f,
                            onClick = { format = f },
                            label = { Text(f.name, fontSize = 11.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = defaultModel,
                    onValueChange = { defaultModel = it },
                    label = { Text("Model ID (e.g. llama-3.3-70b-versatile)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = headerPair,
                    onValueChange = { headerPair = it },
                    label = { Text("Optional Headers (Key:Value, comma-separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key (optional for local server)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Supports Multimodal Vision", fontSize = 12.5.sp)
                    Switch(checked = supportsVision, onCheckedChange = { supportsVision = it })
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && defaultModel.isNotBlank() && policyCheck.isValid,
                onClick = {
                    val headers = headerPair.split(",")
                        .mapNotNull { entry ->
                            val parts = entry.split(":", limit = 2)
                            if (parts.size == 2 && parts[0].isNotBlank()) {
                                parts[0].trim() to parts[1].trim()
                            } else null
                        }.toMap()
                    val cleanModels = defaultModel.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    val config = AiProviderConfig(
                        id = "custom_${UUID.randomUUID().toString().take(8)}",
                        name = name.trim(),
                        baseUrl = baseUrl.trim(),
                        defaultModel = cleanModels.first(),
                        supportsVision = supportsVision,
                        supportsStreaming = false,
                        supportsTools = false,
                        apiFormat = format,
                        availableModels = cleanModels,
                        customHeaders = headers,
                        isBuiltin = false,
                        isEnabled = true,
                        allowInsecureHttpConfirmed = allowHttpRisk
                    )
                    onSave(config, apiKey.trim())
                }
            ) {
                Text("Save Provider")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
