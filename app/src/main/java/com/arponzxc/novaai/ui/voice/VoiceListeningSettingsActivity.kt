package com.arponzxc.novaai.ui.voice

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.R
import com.arponzxc.novaai.audio.NoiseSensitivity
import com.arponzxc.novaai.background.JarvisConversationService
import com.arponzxc.novaai.data.model.ConversationState
import com.arponzxc.novaai.ui.permission.PermissionActivity
import com.arponzxc.novaai.util.PermissionHelper

class VoiceListeningSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val colors = darkColorScheme(
                primary = Color(0xFF38BDF8),
                secondary = Color(0xFF10B981),
                background = Color(0xFF060B16),
                surface = Color(0xFF0F172A),
                onBackground = Color(0xFFF8FAFC),
                onSurface = Color(0xFFF8FAFC)
            )
            MaterialTheme(colorScheme = colors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VoiceListeningSettingsScreen(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun VoiceListeningSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { JarvisApp.instance.preferences }

    val isSessionOn by JarvisConversationService.isSessionOn.collectAsState()
    val isStandby by JarvisConversationService.isStandby.collectAsState()
    val isPaused by JarvisConversationService.isListeningPaused.collectAsState()
    val conversationState by JarvisConversationService.conversationState.collectAsState()
    val audioLevel by JarvisConversationService.audioLevel.collectAsState()

    var continuousEnabled by remember { mutableStateOf(prefs.isContinuousListeningEnabled) }
    var autoContinueEnabled by remember { mutableStateOf(prefs.isAutoContinueAfterReply) }
    var backgroundEnabled by remember { mutableStateOf(prefs.isBackgroundVoiceListeningEnabled) }
    var silenceTimeoutMs by remember { mutableFloatStateOf(prefs.voiceSilenceTimeoutMs.toFloat()) }
    var sensitivity by remember { mutableStateOf(NoiseSensitivity.fromPref(prefs.voiceNoiseSensitivity)) }
    var selectedLanguage by remember { mutableStateOf(prefs.language) }

    val hasMicPermission = PermissionHelper.hasRecordAudioPermission(context)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp).testTag("voice_settings_back")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.voice_listening_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.voice_listening_subtitle),
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            // Live Listening Indicator & Controls
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101C33))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val indicatorColor = when {
                            isPaused -> Color(0xFFF59E0B)
                            isSessionOn && conversationState == ConversationState.LISTENING -> Color(0xFF10B981)
                            isSessionOn -> Color(0xFF38BDF8)
                            isStandby -> Color(0xFF60A5FA)
                            else -> Color(0xFF64748B)
                        }
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(indicatorColor.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPaused || !isSessionOn) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                contentDescription = "Microphone status",
                                tint = indicatorColor
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    isPaused -> "LISTENING PAUSED"
                                    isSessionOn && conversationState == ConversationState.LISTENING -> "MIC ACTIVE • SPEAK NATURALLY"
                                    isSessionOn && conversationState == ConversationState.SPEAKING -> "NOVA AI SPEAKING"
                                    isSessionOn && conversationState == ConversationState.THINKING -> "PROCESSING COMMAND"
                                    isSessionOn -> "CONTINUOUS VOICE READY"
                                    isStandby -> "BACKGROUND STANDBY (HEY NOVA)"
                                    else -> "VOICE SERVICE IDLE"
                                },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = indicatorColor
                            )
                            Text(
                                text = "Silence threshold: ${silenceTimeoutMs.toLong()} ms • ${sensitivity.name}",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { audioLevel.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = Color(0xFF38BDF8),
                        trackColor = Color(0xFF1E293B)
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!hasMicPermission) {
                                    context.startActivity(Intent(context, PermissionActivity::class.java))
                                    return@Button
                                }
                                if (isSessionOn && isPaused) {
                                    JarvisConversationService.resumeListening()
                                } else if (isSessionOn) {
                                    JarvisConversationService.pauseListening()
                                } else {
                                    val i = Intent(context, JarvisConversationService::class.java)
                                        .setAction(JarvisConversationService.ACTION_START_CONVERSATION)
                                    ContextCompat.startForegroundService(context, i)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("voice_toggle_start_pause_button")
                        ) {
                            Icon(
                                imageVector = when {
                                    !isSessionOn || isPaused -> Icons.Rounded.PlayArrow
                                    else -> Icons.Rounded.Pause
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when {
                                    !isSessionOn -> "Start Listening"
                                    isPaused -> "Resume Mic"
                                    else -> "Pause Mic"
                                },
                                fontSize = 13.sp
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                val i = Intent(context, JarvisConversationService::class.java)
                                    .setAction(JarvisConversationService.ACTION_STOP_ALL)
                                context.startService(i)
                                Toast.makeText(context, "Voice session stopped.", Toast.LENGTH_SHORT).show()
                            },
                            enabled = isSessionOn || isStandby,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("voice_stop_button")
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stop Service", fontSize = 13.sp)
                        }
                    }
                }
            }

            // Continuous Conversation Settings
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Color(0xFF38BDF8))
                        Spacer(Modifier.width(8.dp))
                        Text("Continuous Conversation & VAD", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                    }

                    ToggleRow(
                        title = "Continuous Voice Listening",
                        subtitle = "Keep listening automatically without tapping the mic button every turn",
                        checked = continuousEnabled,
                        testTag = "switch_continuous_listening",
                        onCheckedChange = {
                            continuousEnabled = it
                            prefs.isContinuousListeningEnabled = it
                            JarvisConversationService.refreshVoiceListeningConfig()
                        }
                    )

                    HorizontalDivider(color = Color(0xFF1E293B))

                    ToggleRow(
                        title = "Auto-Continue After NOVA Replies",
                        subtitle = "Re-open mic immediately after spoken answers for multi-turn dialogue",
                        checked = autoContinueEnabled,
                        testTag = "switch_auto_continue",
                        onCheckedChange = {
                            autoContinueEnabled = it
                            prefs.isAutoContinueAfterReply = it
                            JarvisConversationService.refreshVoiceListeningConfig()
                        }
                    )

                    HorizontalDivider(color = Color(0xFF1E293B))

                    ToggleRow(
                        title = "Background Foreground Listening",
                        subtitle = "Allow \"Hey Nova\" and voice controls while app is minimized",
                        checked = backgroundEnabled,
                        testTag = "switch_background_listening",
                        onCheckedChange = {
                            backgroundEnabled = it
                            prefs.isBackgroundVoiceListeningEnabled = it
                            JarvisConversationService.refreshVoiceListeningConfig()
                        }
                    )
                }
            }

            // Silence Detection & Noise Handling
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Tune, contentDescription = null, tint = Color(0xFF38BDF8))
                        Spacer(Modifier.width(8.dp))
                        Text("Long Command Pause & Noise Rejection", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "End-of-Speech Silence Wait: ${silenceTimeoutMs.toLong()} ms",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Higher values prevent mid-sentence cutoffs during longer Bengali or English commands.",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                    Slider(
                        value = silenceTimeoutMs,
                        onValueChange = {
                            silenceTimeoutMs = it
                            prefs.voiceSilenceTimeoutMs = it.toLong()
                            JarvisConversationService.refreshVoiceListeningConfig()
                        },
                        valueRange = 500f..3000f,
                        modifier = Modifier.testTag("slider_silence_timeout")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(700L to "Fast (0.7s)", 1100L to "Natural (1.1s)", 1800L to "Long (1.8s)").forEach { (ms, label) ->
                            FilterChip(
                                selected = kotlin.math.abs(silenceTimeoutMs - ms) < 120f,
                                onClick = {
                                    silenceTimeoutMs = ms.toFloat()
                                    prefs.voiceSilenceTimeoutMs = ms
                                    JarvisConversationService.refreshVoiceListeningConfig()
                                },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Adaptive Noise Rejection",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NoiseSensitivity.entries.forEach { level ->
                            FilterChip(
                                selected = sensitivity == level,
                                onClick = {
                                    sensitivity = level
                                    prefs.voiceNoiseSensitivity = level.name
                                    JarvisConversationService.refreshVoiceListeningConfig()
                                },
                                label = {
                                    Text(
                                        when (level) {
                                            NoiseSensitivity.LOW -> "Sensitive"
                                            NoiseSensitivity.BALANCED -> "Balanced"
                                            NoiseSensitivity.HIGH -> "Noisy Room"
                                        },
                                        fontSize = 11.sp
                                    )
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Voice Recognition Language",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Auto", "Bengali", "English", "Hindi").forEach { lang ->
                            FilterChip(
                                selected = selectedLanguage.equals(lang, ignoreCase = true),
                                onClick = {
                                    selectedLanguage = lang
                                    prefs.language = lang
                                    JarvisConversationService.refreshVoiceListeningConfig()
                                    Toast.makeText(context, "Language set to $lang", Toast.LENGTH_SHORT).show()
                                },
                                label = { Text(lang, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            // Voice Control Commands Guide
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, tint = Color(0xFF38BDF8))
                        Spacer(Modifier.width(8.dp))
                        Text("Built-in Voice Control Commands", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                    }
                    Text("• \"NOVA, Start Listening\" / \"শোনা শুরু করো\"", fontSize = 12.sp, color = Color(0xFFCBD5E1))
                    Text("• \"NOVA, Stop Listening\" / \"শোনা বন্ধ করো\"", fontSize = 12.sp, color = Color(0xFFCBD5E1))
                    Text("• \"NOVA, Continue Listening\" / \"শুনতে থাকো\"", fontSize = 12.sp, color = Color(0xFFCBD5E1))
                    Text("• \"NOVA, Cancel\" / \"বাতিল করো\"", fontSize = 12.sp, color = Color(0xFFCBD5E1))
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(context, PermissionActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("open_permissions_from_voice")
                    ) {
                        Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Manage Microphone & Storage Permissions")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(text = subtitle, color = Color(0xFF94A3B8), fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
