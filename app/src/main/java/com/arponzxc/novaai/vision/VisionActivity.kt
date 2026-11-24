package com.arponzxc.novaai.vision

import com.arponzxc.novaai.ui.theme.NovaUi
import com.arponzxc.novaai.ui.theme.NovaPageTheme
import com.arponzxc.novaai.ui.theme.NovaPageHeader
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.StopScreenShare
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.background.JarvisConversationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class VisionActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null
    @Volatile
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        com.arponzxc.novaai.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.US
            }
        }

        val autoAnalyzeScreen = intent?.getBooleanExtra(EXTRA_AUTO_ANALYZE_SCREEN, false) == true

        setContent {
            NovaPageTheme {
                VisionStudioScreen(
                    autoAnalyzeScreen = autoAnalyzeScreen,
                    onSpeak = { text ->
                        if (ttsReady && text.isNotBlank()) {
                            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vision_readout")
                        }
                    },
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_AUTO_ANALYZE_SCREEN = "extra_auto_analyze_screen"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisionStudioScreen(
    autoAnalyzeScreen: Boolean,
    onSpeak: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isScreenSharing by ScreenCaptureManager.getInstance().isSharing.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraLiveActive by remember { mutableStateOf(false) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var captureTrigger by remember { mutableStateOf<(() -> Unit)?>(null) }

    var selectedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var selectedSourceLabel by remember { mutableStateOf("No visual source captured yet") }

    var selectedMode by remember { mutableStateOf(VisionAnalysisMode.DESCRIBE) }
    var questionText by remember { mutableStateOf("") }
    var isAnalyzing by remember { mutableStateOf(false) }
    var latestResult by remember { mutableStateOf<VisionAnalysisResult?>(null) }
    var history by remember { mutableStateOf<List<VisionAnalysisResult>>(emptyList()) }
    var showScreenConsentDialog by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            cameraLiveActive = true
        } else {
            Toast.makeText(context, "Camera permission denied. You can still pick gallery photos or analyze screen.", Toast.LENGTH_LONG).show()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = VisionAnalysisEngine.loadUriAsJpegBytes(context, uri)
                if (bytes != null) {
                    cameraLiveActive = false
                    selectedImageBytes = bytes
                    selectedSourceLabel = "Gallery Photo"
                } else {
                    Toast.makeText(context, "Could not decode selected image.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            scope.launch {
                if (!JarvisConversationService.isServiceRunning()) {
                    JarvisConversationService.startService(context)
                    repeat(15) {
                        if (JarvisConversationService.isServiceRunning()) return@repeat
                        delay(100)
                    }
                }
                JarvisConversationService.elevateToMediaProjection()
                val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = runCatching {
                    mpm.getMediaProjection(result.resultCode, result.data!!)
                }.getOrNull()
                if (projection == null) {
                    Toast.makeText(context, "Screen Capture could not be initialized.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                    .defaultDisplay?.getRealMetrics(metrics)
                if (metrics.widthPixels == 0) {
                    metrics.setTo(context.resources.displayMetrics)
                }
                val started = JarvisConversationService.startVisionScreenCapture(projection, metrics)
                if (started) {
                    delay(450)
                    val snap = ScreenCaptureManager.getInstance().captureSnapshotJpeg(82)
                    if (snap != null) {
                        cameraLiveActive = false
                        selectedImageBytes = snap
                        selectedSourceLabel = "Live Screen Snapshot"
                    }
                } else {
                    Toast.makeText(context, "Screen Capture failed to start foreground projection.", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            Toast.makeText(context, "Screen capture consent declined.", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchAnalysis(mode: VisionAnalysisMode = selectedMode, customQ: String = questionText) {
        scope.launch {
            isAnalyzing = true
            val bytes = selectedImageBytes
            val res = if (bytes != null) {
                VisionAnalysisEngine.analyzeImageBytes(
                    context = context,
                    rawImageBytes = bytes,
                    mode = mode,
                    question = customQ
                )
            } else {
                VisionAnalysisEngine.analyzeCurrentScreen(
                    context = context,
                    question = customQ.ifBlank { "Describe what is currently on the screen." }
                )
            }
            isAnalyzing = false
            latestResult = res
            if (res.success) {
                history = (listOf(res) + history).take(10)
            } else if (res.requiresPermissionOrConsent) {
                showScreenConsentDialog = true
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(autoAnalyzeScreen) {
        if (autoAnalyzeScreen) {
            selectedMode = VisionAnalysisMode.SCREEN_UNDERSTANDING
            launchAnalysis(VisionAnalysisMode.SCREEN_UNDERSTANDING, "What is on my screen?")
        }
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                NovaPageHeader(
                    title = "NOVA AI Vision Studio",
                    subtitle = selectedSourceLabel,
                    onBack = onBack,
                    backTestTag = "vision_back_button",
                    actions = if (isScreenSharing) {
                        {
                            OutlinedButton(
                                onClick = { JarvisConversationService.stopScreenCapture() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = NovaUi.error),
                                modifier = Modifier.testTag("stop_screen_share_button")
                            ) {
                                Icon(Icons.Rounded.StopScreenShare, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Stop", fontSize = 12.sp)
                            }
                        }
                    } else null
                )

                // Visual Stage (Live Camera2 Preview OR Selected Bitmap Preview)
                Card(
                    colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NovaUi.border, RoundedCornerShape(18.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .clip(RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (cameraLiveActive && hasCameraPermission) {
                            Camera2PreviewBox(
                                useFrontCamera = useFrontCamera,
                                onCaptureReady = { trigger -> captureTrigger = trigger },
                                onCapturedJpeg = { captured ->
                                    cameraLiveActive = false
                                    selectedImageBytes = captured
                                    selectedSourceLabel = "Camera Capture"
                                },
                                onCameraError = { msg ->
                                    cameraLiveActive = false
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            )
                        } else if (selectedImageBytes != null) {
                            val bitmap = remember(selectedImageBytes) {
                                selectedImageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Selected visual input",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                                IconButton(
                                    onClick = {
                                        selectedImageBytes = null
                                        selectedSourceLabel = "No visual source captured yet"
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .background(Color(0x99000000), RoundedCornerShape(50))
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear image", tint = Color.White)
                                }
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(20.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.CameraAlt,
                                    contentDescription = null,
                                    tint = NovaUi.accent,
                                    modifier = Modifier.size(44.dp)
                                )
                                Text(
                                    text = "Start Camera, Pick Image, or Share Screen",
                                    color = NovaUi.textSecondary,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Camera and Screen capture stay off until you explicitly grant consent.",
                                    color = NovaUi.textMuted,
                                    fontSize = 11.5.sp
                                )
                            }
                        }
                    }
                }

                // Source Actions Row
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (cameraLiveActive && hasCameraPermission) {
                        Button(
                            onClick = { captureTrigger?.invoke() },
                            modifier = Modifier.testTag("capture_camera_frame_button")
                        ) {
                            Icon(Icons.Rounded.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Capture Photo", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = { useFrontCamera = !useFrontCamera }) {
                            Icon(Icons.Rounded.Cameraswitch, contentDescription = "Switch lens", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (useFrontCamera) "Front" else "Back", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = { cameraLiveActive = false }) {
                            Text("Stop Camera", fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = {
                                if (hasCameraPermission) {
                                    cameraLiveActive = true
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            modifier = Modifier.testTag("open_camera_preview_button")
                        ) {
                            Icon(Icons.Rounded.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Camera", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.testTag("pick_gallery_photo_button")
                        ) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Photo Picker", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = { showScreenConsentDialog = true },
                            modifier = Modifier.testTag("analyze_screen_consent_button")
                        ) {
                            Icon(Icons.Rounded.ScreenShare, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isScreenSharing) "Grab Screen" else "Screen Share", fontSize = 12.sp)
                        }
                    }
                }

                // Mode Selection
                Text(
                    text = "Analysis Mode",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NovaUi.textSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VisionAnalysisMode.entries.forEach { mode ->
                        FilterChip(
                            selected = selectedMode == mode,
                            onClick = { selectedMode = mode },
                            label = { Text(mode.label, fontSize = 12.sp) },
                            modifier = Modifier.testTag("vision_mode_${mode.name.lowercase()}")
                        )
                    }
                }

                // Interactive Question + Analyze
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = questionText,
                        onValueChange = { questionText = it },
                        placeholder = { Text("Ask a question about this image or screen…", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("vision_question_input")
                    )
                    Button(
                        onClick = { launchAnalysis() },
                        enabled = !isAnalyzing,
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("vision_analyze_button")
                    ) {
                        if (isAnalyzing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = NovaUi.onAccent)
                        } else {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Analyze")
                        }
                    }
                }

                // Quick Analyze primary CTA when image loaded
                if (selectedImageBytes != null) {
                    Button(
                        onClick = { launchAnalysis() },
                        enabled = !isAnalyzing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Analyze with ${selectedMode.label}", fontWeight = FontWeight.SemiBold)
                    }
                }

                // Result Card
                latestResult?.let { res ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (res.success) NovaUi.cardSolid else NovaUi.errorBg
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (res.success) "${res.mode.label} • ${res.providerUsed}" else "Vision Notice",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (res.success) NovaUi.accent else NovaUi.error
                                )
                                if (res.success) {
                                    IconButton(onClick = { onSpeak(res.answer) }) {
                                        Icon(Icons.Rounded.VolumeUp, contentDescription = "Speak answer", tint = NovaUi.accent)
                                    }
                                }
                            }
                            Text(
                                text = if (res.success) res.answer else res.errorMessage,
                                fontSize = 13.5.sp,
                                lineHeight = 20.sp,
                                color = NovaUi.textPrimary
                            )
                            if (res.success && res.latencyMs > 0) {
                                Text(
                                    text = "Model: ${res.modelUsed} • ${res.latencyMs}ms",
                                    fontSize = 11.sp,
                                    color = NovaUi.textSecondary
                                )
                            }
                        }
                    }
                }

                // History
                if (history.size > 1) {
                    Text(
                        text = "Recent Session Analyses (${history.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NovaUi.textSecondary
                    )
                    history.drop(1).take(4).forEach { past ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = NovaUi.cardSolid),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(past.mode.label, color = NovaUi.accent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(past.answer.take(220), color = NovaUi.textSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    if (showScreenConsentDialog) {
        AlertDialog(
            onDismissRequest = { showScreenConsentDialog = false },
            icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = NovaUi.warn) },
            title = { Text("Screen Capture Permission") },
            text = {
                Text(
                    "NOVA AI needs Android Screen Capture (MediaProjection) consent to capture a screenshot and explain what is on your screen. You can tap 'Stop' anytime to end capture immediately."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showScreenConsentDialog = false
                    if (isScreenSharing && ScreenCaptureManager.getInstance().hasRecentFrame(10_000L)) {
                        val snap = ScreenCaptureManager.getInstance().captureSnapshotJpeg(82)
                        if (snap != null) {
                            cameraLiveActive = false
                            selectedImageBytes = snap
                            selectedSourceLabel = "Live Screen Snapshot"
                            return@Button
                        }
                    }
                    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                }) {
                    Text("Allow Screen Capture")
                }
            },
            dismissButton = {
                TextButton(onClick = { showScreenConsentDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun Camera2PreviewBox(
    useFrontCamera: Boolean,
    onCaptureReady: (() -> Unit) -> Unit,
    onCapturedJpeg: (ByteArray) -> Unit,
    onCameraError: (String) -> Unit
) {
    val context = LocalContext.current
    val cameraHolder = remember {
        object {
            var device: CameraDevice? = null
            var session: CameraCaptureSession? = null
            var imageReader: ImageReader? = null
            var thread: HandlerThread? = null
            var handler: Handler? = null
            var textureView: TextureView? = null
        }
    }

    fun closeCameraResources() {
        runCatching { cameraHolder.session?.close() }
        cameraHolder.session = null
        runCatching { cameraHolder.device?.close() }
        cameraHolder.device = null
        runCatching { cameraHolder.imageReader?.close() }
        cameraHolder.imageReader = null
        runCatching { cameraHolder.thread?.quitSafely() }
        cameraHolder.thread = null
        cameraHolder.handler = null
    }

    fun openCamera(surfaceTexture: SurfaceTexture) {
        closeCameraResources()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val desiredFacing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == desiredFacing
        } ?: manager.cameraIdList.firstOrNull()

        if (cameraId == null) {
            onCameraError("No physical camera found on this device.")
            return
        }

        val bgThread = HandlerThread("NovaVisionCamera2").apply { start() }
        val bgHandler = Handler(bgThread.looper)
        cameraHolder.thread = bgThread
        cameraHolder.handler = bgHandler

        val reader = ImageReader.newInstance(1280, 960, ImageFormat.JPEG, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                Handler(context.mainLooper).post {
                    onCapturedJpeg(bytes)
                }
            } finally {
                image.close()
            }
        }, bgHandler)
        cameraHolder.imageReader = reader

        surfaceTexture.setDefaultBufferSize(1280, 960)
        val previewSurface = Surface(surfaceTexture)

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraHolder.device = camera
                    val previewReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(
                        listOf(previewSurface, reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                cameraHolder.session = session
                                runCatching {
                                    session.setRepeatingRequest(previewReq.build(), null, bgHandler)
                                }
                                onCaptureReady {
                                    val dev = cameraHolder.device ?: return@onCaptureReady
                                    val ses = cameraHolder.session ?: return@onCaptureReady
                                    val captureReq = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(reader.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    }
                                    runCatching { ses.capture(captureReq.build(), null, bgHandler) }
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Handler(context.mainLooper).post {
                                    onCameraError("Camera preview session failed to configure.")
                                }
                            }
                        },
                        bgHandler
                    )
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    Handler(context.mainLooper).post {
                        onCameraError("Camera hardware error ($error).")
                    }
                }
            }, bgHandler)
        } catch (e: Exception) {
            Log.e("VisionActivity", "Failed opening camera: ${e.message}")
            onCameraError("Unable to open camera: ${e.message}")
        }
    }

    DisposableEffect(useFrontCamera) {
        val tv = cameraHolder.textureView
        if (tv != null && tv.isAvailable && tv.surfaceTexture != null) {
            openCamera(tv.surfaceTexture!!)
        }
        onDispose {
            closeCameraResources()
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                cameraHolder.textureView = this
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        openCamera(surface)
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        closeCameraResources()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
