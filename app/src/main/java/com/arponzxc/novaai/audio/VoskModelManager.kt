package com.arponzxc.novaai.audio

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import org.vosk.SpeakerModel
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

object VoskModelManager {
    private const val TAG = "VoskModelManager"
    private const val MODEL_DOWNLOAD_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    internal val REQUIRED_ASR_FILES = listOf(
        "am/final.mdl",
        "conf/model.conf",
        "conf/mfcc.conf",
        "ivector/final.ie",
        "ivector/final.mat",
        "ivector/splice.conf",
        "ivector/global_cmvn.stats",
        "ivector/online_cmvn.conf",
        "graph/Gr.fst",
        "graph/HCLr.fst"
    )

    internal val REQUIRED_SPK_FILES = listOf(
        "final.ext.raw",
        "mean.vec",
        "mfcc.conf",
        "transform.mat"
    )

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    private val _isSpeakerModelReady = MutableStateFlow(false)
    val isSpeakerModelReady: StateFlow<Boolean> = _isSpeakerModelReady.asStateFlow()

    private val _modelError = MutableStateFlow<String?>(null)
    val modelError: StateFlow<String?> = _modelError.asStateFlow()

    @Volatile
    var model: Model? = null
        private set

    @Volatile
    var speakerModel: SpeakerModel? = null
        private set

    private val isInitializing = AtomicBoolean(false)

    fun init(context: Context, scope: CoroutineScope) {
        if (model != null && speakerModel != null) return
        if (!isInitializing.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Load speaker recognition model independently so owner verification works even if ASR model is downloading
                if (speakerModel == null) {
                    loadSpeakerModel(appContext)
                }

                // 2. Load speech recognition model (only after validating ivector & graph files exist)
                if (model == null) {
                    loadAsrModel(appContext)
                }
            } finally {
                isInitializing.set(false)
            }
        }
    }

    private fun loadSpeakerModel(context: Context) {
        try {
            val spkTargetDir = File(context.filesDir, "vosk-spk-model")
            if (!hasValidSpeakerModel(spkTargetDir)) {
                Log.i(TAG, "Unpacking Vosk Speaker model to: ${spkTargetDir.absolutePath}")
                spkTargetDir.deleteRecursively()
                spkTargetDir.mkdirs()
                copyAssetFolder(context.assets, "model-spk", spkTargetDir)
            }
            if (!hasValidSpeakerModel(spkTargetDir)) {
                Log.w(TAG, "Vosk Speaker model files are incomplete in ${spkTargetDir.absolutePath}")
                return
            }
            val loadedSpk = SpeakerModel(spkTargetDir.absolutePath)
            speakerModel = loadedSpk
            _isSpeakerModelReady.value = true
            Log.i(TAG, "Vosk SpeakerModel ready in memory!")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load Vosk SpeakerModel: ${e.message}", e)
        }
    }

    private fun loadAsrModel(context: Context) {
        val targetDir = File(context.filesDir, "vosk-model")

        if (!hasValidAsrModel(targetDir)) {
            if (hasCompleteBundledAsrAssets(context.assets)) {
                Log.i(TAG, "Unpacking complete bundled Vosk model to: ${targetDir.absolutePath}")
                targetDir.deleteRecursively()
                targetDir.mkdirs()
                copyAssetFolder(context.assets, "model-en-us", targetDir)
            } else {
                Log.w(
                    TAG,
                    "Bundled assets/model-en-us is missing required ivector/graph files; fetching complete offline model package."
                )
                targetDir.deleteRecursively()
                val downloaded = downloadAndExtractAsrModel(context, targetDir)
                if (!downloaded) {
                    _modelError.value =
                        "Offline Vosk wake-word model needs a one-time download (using system wake-word fallback)."
                    return
                }
            }
        }

        if (!hasValidAsrModel(targetDir)) {
            Log.w(TAG, "Vosk model directory is missing required files; skipping native Model() init.")
            _modelError.value = "Offline Vosk wake-word model files are incomplete."
            return
        }

        try {
            val loadedModel = Model(targetDir.absolutePath)
            model = loadedModel
            _modelError.value = null
            _isModelReady.value = true
            Log.i(TAG, "Vosk Model ready in memory!")
        } catch (e: Throwable) {
            Log.w(TAG, "Could not initialize native Vosk Model: ${e.message}", e)
            _modelError.value = e.message ?: "Failed to initialize Vosk Model"
        }
    }

    internal fun hasValidAsrModel(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        return REQUIRED_ASR_FILES.all { rel ->
            val f = File(dir, rel)
            f.exists() && f.isFile && f.length() > 0L
        }
    }

    internal fun hasValidSpeakerModel(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        return REQUIRED_SPK_FILES.all { rel ->
            val f = File(dir, rel)
            f.exists() && f.isFile && f.length() > 0L
        }
    }

    private fun hasCompleteBundledAsrAssets(assetManager: AssetManager): Boolean {
        return try {
            val ivectorFiles = assetManager.list("model-en-us/ivector")?.toSet().orEmpty()
            val graphFiles = assetManager.list("model-en-us/graph")?.toSet().orEmpty()
            val amFiles = assetManager.list("model-en-us/am")?.toSet().orEmpty()
            val confFiles = assetManager.list("model-en-us/conf")?.toSet().orEmpty()
            ivectorFiles.containsAll(listOf("final.ie", "final.mat", "splice.conf", "global_cmvn.stats", "online_cmvn.conf")) &&
                graphFiles.containsAll(listOf("Gr.fst", "HCLr.fst")) &&
                amFiles.contains("final.mdl") &&
                confFiles.containsAll(listOf("model.conf", "mfcc.conf"))
        } catch (_: Exception) {
            false
        }
    }

    internal fun stripSingleRootFolder(entryName: String): String {
        val normalized = entryName.replace('\\', '/').trimStart('/')
        if (normalized.isEmpty()) return ""
        val slashIndex = normalized.indexOf('/')
        return if (slashIndex >= 0) {
            normalized.substring(slashIndex + 1)
        } else {
            normalized
        }
    }

    private fun downloadAndExtractAsrModel(context: Context, targetDir: File): Boolean {
        val zipFile = File(context.cacheDir, "vosk-model-small-en-us-0.15.zip")
        val stagingDir = File(context.filesDir, "vosk-model-staging")
        return try {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()

            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(MODEL_DOWNLOAD_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Vosk model download failed with HTTP ${response.code}")
                    return false
                }
                val body = response.body ?: return false
                body.byteStream().use { input ->
                    FileOutputStream(zipFile).use { output ->
                        input.copyTo(output, bufferSize = 32 * 1024)
                    }
                }
            }

            val canonicalStaging = stagingDir.canonicalPath + File.separator
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var entry = zis.nextEntry
                val buffer = ByteArray(16 * 1024)
                while (entry != null) {
                    val relPath = stripSingleRootFolder(entry.name)
                    if (relPath.isNotBlank()) {
                        val outFile = File(stagingDir, relPath)
                        val canonicalOut = outFile.canonicalPath
                        if (!canonicalOut.startsWith(canonicalStaging) && canonicalOut != stagingDir.canonicalPath) {
                            throw SecurityException("Blocked unsafe zip entry: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                while (true) {
                                    val count = zis.read(buffer)
                                    if (count <= 0) break
                                    fos.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (!hasValidAsrModel(stagingDir)) {
                Log.w(TAG, "Downloaded Vosk zip did not contain expected ivector/graph files")
                stagingDir.deleteRecursively()
                return false
            }

            targetDir.deleteRecursively()
            if (!stagingDir.renameTo(targetDir)) {
                stagingDir.copyRecursively(targetDir, overwrite = true)
                stagingDir.deleteRecursively()
            }
            Log.i(TAG, "Complete Vosk ASR model downloaded and extracted to ${targetDir.absolutePath}")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to fetch complete Vosk model (offline or blocked): ${e.message}")
            stagingDir.deleteRecursively()
            false
        } finally {
            zipFile.delete()
        }
    }

    private fun copyAssetFolder(assetManager: AssetManager, fromAssetPath: String, toDir: File) {
        val list = assetManager.list(fromAssetPath)
        if (list.isNullOrEmpty()) {
            try {
                assetManager.open(fromAssetPath).use { input ->
                    toDir.parentFile?.mkdirs()
                    FileOutputStream(toDir).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not copy asset $fromAssetPath: ${e.message}")
            }
        } else {
            toDir.mkdirs()
            for (item in list) {
                val childAsset = if (fromAssetPath.isEmpty()) item else "$fromAssetPath/$item"
                val childTarget = File(toDir, item)
                copyAssetFolder(assetManager, childAsset, childTarget)
            }
        }
    }
}
