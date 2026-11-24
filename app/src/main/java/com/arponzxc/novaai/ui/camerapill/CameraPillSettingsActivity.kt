@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.arponzxc.novaai.ui.camerapill

import androidx.compose.foundation.layout.FlowRow
import com.arponzxc.novaai.ui.theme.NovaUi
import com.arponzxc.novaai.ui.theme.NovaPageTheme
import com.arponzxc.novaai.ui.theme.NovaPageHeader
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arponzxc.novaai.JarvisApp
import kotlin.math.roundToInt

/**
 * Jetpack Compose Material 3 configuration screen for Camera Pill (AI Live Avatar).
 */
class CameraPillSettingsActivity : ComponentActivity() {

    private val preferences by lazy { JarvisApp.instance.preferences }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.arponzxc.novaai.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        CameraPillStateCoordinator.initialize(this)

        setContent {
            NovaPageTheme {
                CameraPillSettingsScreen(
                    onBack = { finish() },
                    onOpenOverlayPermission = { openOverlayPermissionSettings() },
                    onTogglePreview = { isCurrentlyPreviewing ->
                        if (isCurrentlyPreviewing) {
                            CameraPillStateCoordinator.stopPreview()
                        } else if (!Settings.canDrawOverlays(this)) {
                            Toast.makeText(
                                this,
                                "Allow Display over other apps first to preview floating Camera Pill.",
                                Toast.LENGTH_LONG
                            ).show()
                            openOverlayPermissionSettings()
                        } else {
                            CameraPillStateCoordinator.startPreview()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CameraPillStateCoordinator.refreshOverlayPermission()
        CameraPillStateCoordinator.refreshConfigFromPreferences()
    }

    private fun openOverlayPermissionSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Open Android Settings → Apps → NOVA AI → Display over other apps.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

@Composable
private fun CameraPillSettingsScreen(
    onBack: () -> Unit,
    onOpenOverlayPermission: () -> Unit,
    onTogglePreview: (Boolean) -> Unit
) {
    val preferences = remember { JarvisApp.instance.preferences }
    val liveUiState by CameraPillStateCoordinator.uiState.collectAsState()
    val hasOverlayPermission by CameraPillStateCoordinator.hasOverlayPermission.collectAsState()

    var enabled by remember { mutableStateOf(preferences.isCameraPillEnabled) }
    var displayMode by remember {
        mutableStateOf(CameraPillDisplayMode.fromPref(preferences.cameraPillDisplayMode))
    }
    var idleDelayMs by remember { mutableStateOf(preferences.cameraPillIdleDelayMs) }
    var avatarAnimation by remember { mutableStateOf(preferences.isCameraPillAvatarAnimationEnabled) }
    var audioVisualizer by remember { mutableStateOf(preferences.isCameraPillVisualizerEnabled) }
    var showThinking by remember { mutableStateOf(preferences.isCameraPillShowThinking) }
    var showSpeaking by remember { mutableStateOf(preferences.isCameraPillShowSpeaking) }
    var avatarSize by remember { mutableStateOf(preferences.cameraPillAvatarSize) }
    var intensity by remember { mutableStateOf(preferences.cameraPillAnimationIntensity) }

    val previewUiState = remember(
        liveUiState,
        enabled,
        displayMode,
        idleDelayMs,
        avatarAnimation,
        audioVisualizer,
        showThinking,
        showSpeaking,
        avatarSize,
        intensity
    ) {
        val previewState = if (liveUiState.isPreviewActive) liveUiState.aiState else CameraPillAiState.SPEAKING
        liveUiState.copy(
            config = CameraPillConfig(
                enabled = enabled,
                displayMode = displayMode,
                idleHideDelayMs = idleDelayMs,
                avatarAnimationEnabled = avatarAnimation,
                audioVisualizerEnabled = audioVisualizer,
                showThinkingAnimation = showThinking,
                showSpeakingAnimation = showSpeaking,
                avatarSizeScale = avatarSize,
                animationIntensity = intensity
            ),
            aiState = previewState,
            aiAmplitude = if (previewState == CameraPillAiState.SPEAKING) 0.72f else liveUiState.aiAmplitude,
            userAmplitude = if (previewState == CameraPillAiState.LISTENING) 0.65f else liveUiState.userAmplitude,
            shouldShowOverlay = true
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(NovaUi.bg, NovaUi.cardSolid, NovaUi.bg)
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 620.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top App Bar
            NovaPageHeader(
                title = "Camera Pill Settings",
                subtitle = "Dynamic Island AI Live Avatar & Voice Visualizer",
                onBack = onBack,
                backTestTag = "camera_pill_back_btn"
            )

            // Live Top Bezel Showcase Card
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        Brush.horizontalGradient(listOf(NovaUi.accent, NovaUi.accent)),
                        RoundedCornerShape(22.dp)
                    )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "CUTOUT PILL LIVE PREVIEW",
                        color = NovaUi.accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(78.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(NovaUi.bg)
                            .border(1.dp, NovaUi.border, RoundedCornerShape(18.dp))
                            .padding(horizontal = 12.dp)
                    ) {
                        CameraPillOverlayContent(
                            uiState = previewUiState,
                            onPillTap = { CameraPillStateCoordinator.toggleExpanded() }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { onTogglePreview(liveUiState.isPreviewActive) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (liveUiState.isPreviewActive) NovaUi.error else NovaUi.accent,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("btn_preview_camera_pill")
                    ) {
                        Icon(
                            imageVector = if (liveUiState.isPreviewActive) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (liveUiState.isPreviewActive) {
                                "Stop Floating Camera Pill Preview"
                            } else {
                                "Preview Camera Pill"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 10. Overlay Permission Status Card
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (hasOverlayPermission) NovaUi.success.copy(alpha = 0.55f)
                        else NovaUi.warn.copy(alpha = 0.65f),
                        RoundedCornerShape(18.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (hasOverlayPermission) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = if (hasOverlayPermission) NovaUi.success else NovaUi.warn,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Overlay Permission Status",
                                color = NovaUi.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (hasOverlayPermission) {
                                    "Granted — Camera Pill can render over all apps"
                                } else {
                                    "Required — Allow 'Display over other apps' in System Settings"
                                },
                                color = if (hasOverlayPermission) NovaUi.success else NovaUi.warn,
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (!hasOverlayPermission) {
                        OutlinedButton(
                            onClick = onOpenOverlayPermission,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("btn_grant_overlay_permission")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Layers,
                                contentDescription = null,
                                tint = NovaUi.accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Grant Overlay Permission",
                                color = NovaUi.accent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // 1. Master Enable Toggle & Display Modes (2 & 3)
            SectionHeader("DISPLAY & ACTIVATION")
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NovaUi.border, RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ToggleRow(
                        icon = Icons.Rounded.Visibility,
                        title = "Enable Camera Pill",
                        subtitle = "Show floating Dynamic Island avatar near camera cutout",
                        checked = enabled,
                        testTag = "switch_camera_pill_enabled",
                        onCheckedChange = { next ->
                            enabled = next
                            preferences.isCameraPillEnabled = next
                            if (next && !hasOverlayPermission) {
                                onOpenOverlayPermission()
                            }
                        }
                    )

                    HorizontalDivider(color = NovaUi.border)

                    ModeSelectionRow(
                        title = "Only While Talking",
                        subtitle = "Show during voice activity & processing, then auto-hide",
                        selected = displayMode == CameraPillDisplayMode.ONLY_WHILE_TALKING,
                        testTag = "mode_only_while_talking",
                        onSelect = {
                            displayMode = CameraPillDisplayMode.ONLY_WHILE_TALKING
                            preferences.cameraPillDisplayMode = CameraPillDisplayMode.ONLY_WHILE_TALKING.prefValue
                        }
                    )

                    AnimatedVisibility(visible = displayMode == CameraPillDisplayMode.ONLY_WHILE_TALKING) {
                        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, bottom = 10.dp)) {
                            Text(
                                text = "Auto-hide idle delay",
                                color = NovaUi.textSecondary,
                                fontSize = 11.5.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                listOf(2000L to "2s", 3500L to "3.5s", 5000L to "5s", 8000L to "8s").forEach { (ms, label) ->
                                    FilterChip(
                                        selected = idleDelayMs == ms,
                                        onClick = {
                                            idleDelayMs = ms
                                            preferences.cameraPillIdleDelayMs = ms
                                        },
                                        label = { Text(label, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = NovaUi.accent,
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = NovaUi.border)

                    ModeSelectionRow(
                        title = "Always On",
                        subtitle = "Keep pill visible while NOVA AI assistant session is active",
                        selected = displayMode == CameraPillDisplayMode.ALWAYS_ON,
                        testTag = "mode_always_on",
                        onSelect = {
                            displayMode = CameraPillDisplayMode.ALWAYS_ON
                            preferences.cameraPillDisplayMode = CameraPillDisplayMode.ALWAYS_ON.prefValue
                        }
                    )
                }
            }

            // 4, 5, 6, 7. Animation & Visualizer Controls
            SectionHeader("AVATAR & AUDIO VISUALIZER")
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NovaUi.border, RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ToggleRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = "Avatar Animation",
                        subtitle = "Natural blinking, idle breathing & facial expressions",
                        checked = avatarAnimation,
                        testTag = "switch_avatar_animation",
                        onCheckedChange = {
                            avatarAnimation = it
                            preferences.isCameraPillAvatarAnimationEnabled = it
                        }
                    )
                    HorizontalDivider(color = NovaUi.border)
                    ToggleRow(
                        icon = Icons.Rounded.GraphicEq,
                        title = "Audio Visualizer",
                        subtitle = "Real-time voice & TTS amplitude spectrum bars",
                        checked = audioVisualizer,
                        testTag = "switch_audio_visualizer",
                        onCheckedChange = {
                            audioVisualizer = it
                            preferences.isCameraPillVisualizerEnabled = it
                        }
                    )
                    HorizontalDivider(color = NovaUi.border)
                    ToggleRow(
                        icon = Icons.Rounded.Psychology,
                        title = "Show AI Thinking Animation",
                        subtitle = "Orbiting neural ring & pulse while AI processes requests",
                        checked = showThinking,
                        testTag = "switch_thinking_animation",
                        onCheckedChange = {
                            showThinking = it
                            preferences.isCameraPillShowThinking = it
                        }
                    )
                    HorizontalDivider(color = NovaUi.border)
                    ToggleRow(
                        icon = Icons.Rounded.RecordVoiceOver,
                        title = "Show AI Speaking Animation",
                        subtitle = "Mouth lip-sync & voice aura while AI speaks",
                        checked = showSpeaking,
                        testTag = "switch_speaking_animation",
                        onCheckedChange = {
                            showSpeaking = it
                            preferences.isCameraPillShowSpeaking = it
                        }
                    )
                }
            }

            // 8 & 9. Size & Intensity Sliders
            SectionHeader("PROPORTIONS & MOTION")
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NovaUi.border, RoundedCornerShape(18.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SliderControl(
                        title = "Avatar Size",
                        valueLabel = "${(avatarSize * 100).roundToInt()}%",
                        value = avatarSize,
                        range = 0.75f..1.35f,
                        testTag = "slider_avatar_size",
                        onValueChange = {
                            avatarSize = it
                            preferences.cameraPillAvatarSize = it
                        }
                    )

                    HorizontalDivider(color = NovaUi.border)

                    SliderControl(
                        title = "Animation Intensity",
                        valueLabel = "${(intensity * 100).roundToInt()}%",
                        value = intensity,
                        range = 0.4f..1.5f,
                        testTag = "slider_animation_intensity",
                        onValueChange = {
                            intensity = it
                            preferences.cameraPillAnimationIntensity = it
                        }
                    )
                }
            }

            SectionHeader("FLOATING POSITION & GESTURES")
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NovaUi.border, RoundedCornerShape(18.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (liveUiState.hasCustomPosition) {
                            "Custom dragged position active. Drag the pill anywhere on screen or tap to expand quick voice controls (Open NOVA, Pause Mic, Stop, Minimize)."
                        } else {
                            "Auto-centered on the top camera cutout. You can drag the pill anywhere on screen or tap it for quick controls."
                        },
                        color = NovaUi.textSecondary,
                        fontSize = 12.sp
                    )
                    OutlinedButton(
                        onClick = {
                            preferences.resetCameraPillPosition()
                            CameraPillStateCoordinator.resetPositionToCutout()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("btn_reset_pill_position")
                    ) {
                        Text(
                            text = if (liveUiState.hasCustomPosition) "Snap Back to Cutout Center" else "Re-center on Camera Cutout",
                            color = NovaUi.accent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = NovaUi.textMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp)
    ) {
        Surface(
            color = NovaUi.cardSolid,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = NovaUi.accent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = NovaUi.textPrimary,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = NovaUi.textSecondary,
                fontSize = 11.5.sp
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NovaUi.accent,
                uncheckedThumbColor = NovaUi.textSecondary,
                uncheckedTrackColor = NovaUi.border
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun ModeSelectionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    testTag: String,
    onSelect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 10.dp)
            .testTag(testTag)
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = NovaUi.accent,
                unselectedColor = NovaUi.textMuted
            )
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = NovaUi.textPrimary,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = NovaUi.textSecondary,
                fontSize = 11.5.sp
            )
        }
    }
}

@Composable
private fun SliderControl(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    testTag: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = NovaUi.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = valueLabel,
                color = NovaUi.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = NovaUi.accent,
                activeTrackColor = NovaUi.accent,
                inactiveTrackColor = NovaUi.border
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}
