package com.arponzxc.novaai.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.arponzxc.novaai.automation.accessibility.JarvisAccessibilityService
import com.arponzxc.novaai.network.apimanager.MultiProviderRouter
import com.arponzxc.novaai.network.apimanager.UnifiedAiVisionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

enum class VisionAnalysisMode(val label: String, val systemInstruction: String) {
    DESCRIBE(
        label = "Describe",
        systemInstruction = "Provide a clear, accurate description of the image scene, main subjects, colors, and context."
    ),
    OBJECTS(
        label = "Objects & Scene",
        systemInstruction = "Identify the key objects, people/items if visible, environment/setting, and their relative positions."
    ),
    OCR(
        label = "Extract Text (OCR)",
        systemInstruction = "Extract all legible printed or handwritten text visible in the image verbatim. Preserve line breaks and structure. Mark illegible characters cleanly instead of guessing."
    ),
    SCREEN_UNDERSTANDING(
        label = "Screen UI",
        systemInstruction = "Analyze this mobile/desktop screen image: identify the app or webpage, readable UI elements, active errors/alerts, and summarize what is happening on screen."
    ),
    CUSTOM_QA(
        label = "Custom Q&A",
        systemInstruction = "Answer the user's question based strictly on visual evidence present in the image."
    )
}

data class VisionAnalysisResult(
    val success: Boolean,
    val answer: String = "",
    val mode: VisionAnalysisMode = VisionAnalysisMode.DESCRIBE,
    val providerUsed: String = "",
    val modelUsed: String = "",
    val latencyMs: Long = 0L,
    val errorMessage: String = "",
    val requiresPermissionOrConsent: Boolean = false,
    val isUnsupportedProvider: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

object VisionAnalysisEngine {

    private const val MAX_IMAGE_DIM = 1024
    private const val ANTI_HALLUCINATION_POLICY =
        "CRITICAL GROUNDING RULE: Describe ONLY details that are clearly visible in the image. " +
            "If details, labels, or text are blurry, low-resolution, cut off, dark, or unreadable, " +
            "explicitly state that they cannot be determined instead of guessing or fabricating."

    fun prepareJpegBytesForUpload(rawBytes: ByteArray, quality: Int = 82): ByteArray? {
        if (rawBytes.isEmpty()) return null
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        var sampleSize = 1
        while (boundsOpts.outWidth / sampleSize > MAX_IMAGE_DIM * 2 ||
            boundsOpts.outHeight / sampleSize > MAX_IMAGE_DIM * 2
        ) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts) ?: return null
        val scaled = scaleDownIfNeeded(decoded, MAX_IMAGE_DIM)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 95), out)
        if (scaled !== decoded) decoded.recycle()
        scaled.recycle()
        return out.toByteArray()
    }

    suspend fun loadUriAsJpegBytes(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null
        prepareJpegBytesForUpload(raw)
    }

    suspend fun analyzeImageBytes(
        context: Context,
        rawImageBytes: ByteArray,
        mode: VisionAnalysisMode,
        question: String = "",
        extraContext: String = ""
    ): VisionAnalysisResult = withContext(Dispatchers.IO) {
        val jpegBytes = prepareJpegBytesForUpload(rawImageBytes)
            ?: return@withContext VisionAnalysisResult(
                success = false,
                mode = mode,
                errorMessage = "The selected image or frame could not be decoded cleanly."
            )

        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val prompt = buildString {
            append(mode.systemInstruction)
            if (question.isNotBlank()) {
                append("\n\nUser Question: ").append(question.trim())
            }
            if (extraContext.isNotBlank()) {
                append("\n\nAccessibility / Screen Structure Context:\n").append(extraContext.trim())
            }
        }

        val response = MultiProviderRouter.generateVisionAnalysis(
            context = context,
            request = UnifiedAiVisionRequest(
                systemPrompt = ANTI_HALLUCINATION_POLICY,
                userPrompt = prompt,
                imageBase64 = base64,
                mimeType = "image/jpeg"
            )
        )

        if (response.success) {
            VisionAnalysisResult(
                success = true,
                answer = response.text,
                mode = mode,
                providerUsed = response.providerName,
                modelUsed = response.modelUsed,
                latencyMs = response.latencyMs
            )
        } else {
            VisionAnalysisResult(
                success = false,
                mode = mode,
                providerUsed = response.providerName,
                modelUsed = response.modelUsed,
                errorMessage = response.errorMessage.ifBlank { "Vision analysis failed." },
                isUnsupportedProvider = response.isUnsupportedCapability
            )
        }
    }

    /**
     * Analyzes the user's current device screen using Screen Share snapshot (MediaProjection)
     * and/or Accessibility UI tree grounding. Never fabricates screen contents when neither is active.
     */
    suspend fun analyzeCurrentScreen(
        context: Context,
        question: String = "What is on my screen right now?"
    ): VisionAnalysisResult = withContext(Dispatchers.IO) {
        val screenCapture = ScreenCaptureManager.getInstance()
        val snapshotBytes = if (screenCapture.hasRecentFrame(maxAgeMs = 15_000L)) {
            screenCapture.captureSnapshotJpeg(quality = 80)
        } else {
            null
        }

        val accService = JarvisAccessibilityService.instance
        val uiSummary = if (accService != null) {
            runCatching {
                val engine = UiGroundingEngine()
                val root = accService.getActiveRootNode()
                val elements = engine.scanScreen(root)
                engine.formatElementsSummary(elements, root)
            }.getOrDefault("")
        } else {
            ""
        }

        if (snapshotBytes != null) {
            return@withContext analyzeImageBytes(
                context = context,
                rawImageBytes = snapshotBytes,
                mode = VisionAnalysisMode.SCREEN_UNDERSTANDING,
                question = question,
                extraContext = uiSummary.take(2500)
            )
        }

        if (uiSummary.isNotBlank()) {
            val response = MultiProviderRouter.generateText(
                context = context,
                request = com.arponzxc.novaai.network.apimanager.UnifiedAiTextRequest(
                    systemPrompt = "You are NOVA AI analyzing active Android accessibility UI structure. Do not invent visual graphics that are not in the UI tree.",
                    userMessage = "Question: $question\n\nObserved UI Tree (no raw screenshot captured):\n${uiSummary.take(4000)}"
                )
            )
            return@withContext if (response.success) {
                VisionAnalysisResult(
                    success = true,
                    answer = response.text,
                    mode = VisionAnalysisMode.SCREEN_UNDERSTANDING,
                    providerUsed = response.providerName,
                    modelUsed = response.modelUsed,
                    latencyMs = response.latencyMs
                )
            } else {
                VisionAnalysisResult(
                    success = false,
                    mode = VisionAnalysisMode.SCREEN_UNDERSTANDING,
                    errorMessage = response.errorMessage.ifBlank { "Unable to summarize screen UI right now." }
                )
            }
        }

        VisionAnalysisResult(
            success = false,
            mode = VisionAnalysisMode.SCREEN_UNDERSTANDING,
            requiresPermissionOrConsent = true,
            errorMessage = "Screen Share (MediaProjection) and Accessibility Screen Reader are not active. Grant Screen Share consent in Vision Studio to inspect what is on your screen."
        )
    }

    private fun scaleDownIfNeeded(bmp: Bitmap, maxDim: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w <= maxDim && h <= maxDim) return bmp
        val ratio = maxDim.toFloat() / maxOf(w, h).toFloat()
        val targetW = (w * ratio).toInt().coerceAtLeast(1)
        val targetH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
    }
}
