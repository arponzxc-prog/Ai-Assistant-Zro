package com.arponzxc.novaai.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Base64
import com.arponzxc.novaai.network.apimanager.MultiProviderRouter
import com.arponzxc.novaai.network.apimanager.UnifiedAiTextRequest
import com.arponzxc.novaai.network.apimanager.UnifiedAiVisionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.Locale

data class FileAiAnalysisResult(
    val success: Boolean,
    val title: String,
    val content: String,
    val suggestions: List<OrganizationSuggestion> = emptyList(),
    val providerUsed: String = "",
    val errorMessage: String = ""
)

/**
 * AI File Understanding & Organization Advisor working strictly on user-approved SAF files.
 * - Text & source code files: Summary, Code Explanation, Q&A, Smart Organization Suggestions.
 * - PDF documents: Renders page bitmaps via native Android PdfRenderer and analyzes them via
 *   active Vision-capable provider, or extracts readable plain PDF strings if present; returns
 *   an honest notice when a binary/scanned PDF requires a Vision-capable provider.
 * - Organization suggestions are NEVER applied automatically; the UI requires per-action confirmation.
 */
object SafFileAiAnalyzer {

    private val CODE_EXTENSIONS = setOf("kt", "java", "py", "js", "ts", "html", "htm", "css", "xml", "json", "gradle", "kts", "sql", "sh")
    private val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "csv", "log", "yaml", "yml")

    suspend fun analyzeDocument(
        context: Context,
        safFileManager: SafFileManager,
        entry: StorageItemEntry,
        question: String = ""
    ): FileAiAnalysisResult = withContext(Dispatchers.IO) {
        if (SafBoundaryGuard.isBlockedInternalOrHiddenUri(entry.uriString)) {
            return@withContext FileAiAnalysisResult(
                success = false,
                title = entry.name,
                content = "",
                errorMessage = "Blocked: File URI is outside authorized SAF storage."
            )
        }

        val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext == "pdf" || entry.mimeType == "application/pdf") {
            return@withContext analyzePdfDocument(context, entry, question)
        }

        if (ext !in TEXT_EXTENSIONS && ext !in CODE_EXTENSIONS && !entry.mimeType.orEmpty().startsWith("text/")) {
            return@withContext FileAiAnalysisResult(
                success = false,
                title = entry.name,
                content = "",
                errorMessage = "Unsupported file format (.${ext.ifBlank { "binary" }}) for text extraction. NOVA supports text, code, and PDF documents."
            )
        }

        val text = safFileManager.readTextPreview(entry.uriString)
        if (text.isBlank()) {
            return@withContext FileAiAnalysisResult(
                success = false,
                title = entry.name,
                content = "",
                errorMessage = "\"${entry.name}\" appears empty or unreadable."
            )
        }

        val isCode = ext in CODE_EXTENSIONS
        val promptTask = when {
            question.isNotBlank() -> "Answer this user question strictly from the file contents:\n$question"
            isCode -> "Explain what this code file does, its key components/functions, and any issues or improvements."
            else -> "Provide a clear, structured summary highlighting the main topics and key action items."
        }

        val resp = MultiProviderRouter.generateText(
            context = context,
            request = UnifiedAiTextRequest(
                systemPrompt = "You are NOVA AI Intelligent Document & Code Analyst. Ground answers strictly in the supplied file text. Never invent missing lines.",
                userMessage = "File: ${entry.name} (Folder: ${entry.parentFolderLabel})\n$promptTask\n\n---\n${text.take(24_000)}"
            )
        )

        if (resp.success) {
            FileAiAnalysisResult(
                success = true,
                title = entry.name,
                content = resp.text,
                providerUsed = "${resp.providerName} (${resp.modelUsed})"
            )
        } else {
            FileAiAnalysisResult(
                success = false,
                title = entry.name,
                content = "",
                errorMessage = resp.errorMessage.ifBlank { "AI analysis failed." }
            )
        }
    }

    suspend fun suggestFolderOrganization(
        context: Context,
        folderLabel: String,
        items: List<StorageItemEntry>
    ): FileAiAnalysisResult = withContext(Dispatchers.IO) {
        val files = items.filter { !it.isDirectory }.take(25)
        if (files.isEmpty()) {
            return@withContext FileAiAnalysisResult(
                success = false,
                title = folderLabel,
                content = "",
                errorMessage = "No files found in \"$folderLabel\" to analyze for organization."
            )
        }

        val listing = files.joinToString("\n") { "- name=${it.name} | uri=${it.uriString} | size=${it.sizeBytes}B" }
        val resp = MultiProviderRouter.generateText(
            context = context,
            request = UnifiedAiTextRequest(
                systemPrompt = """
                    You are NOVA AI File Organizer. Propose helpful, conservative file naming or subfolder organization suggestions.
                    Return ONLY a valid JSON array (up to 6 suggestions) of objects with keys:
                    "sourceUri", "currentName", "suggestedAction" ("RENAME" or "MOVE"), "targetNameOrFolder", "reason".
                    Do not invent URIs. If files are already cleanly organized, return [].
                """.trimIndent(),
                userMessage = "Folder: $folderLabel\nFiles:\n$listing",
                temperature = 0.25
            )
        )

        if (!resp.success) {
            return@withContext FileAiAnalysisResult(
                success = false,
                title = folderLabel,
                content = "",
                errorMessage = resp.errorMessage.ifBlank { "Unable to generate organization suggestions." }
            )
        }

        val parsed = parseSuggestionsJson(resp.text, files)
        FileAiAnalysisResult(
            success = true,
            title = "Organization Suggestions for $folderLabel",
            content = if (parsed.isEmpty()) {
                "Your files in \"$folderLabel\" are already cleanly organized."
            } else {
                "Proposed ${parsed.size} improvement(s). Review carefully and tap Apply + Confirm for any change you want to execute."
            },
            suggestions = parsed,
            providerUsed = "${resp.providerName} (${resp.modelUsed})"
        )
    }

    internal fun parseSuggestionsJson(raw: String, sourceFiles: List<StorageItemEntry>): List<OrganizationSuggestion> {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val startIdx = cleaned.indexOf('[')
        val endIdx = cleaned.lastIndexOf(']')
        if (startIdx < 0 || endIdx <= startIdx) return emptyList()
        val jsonSlice = cleaned.substring(startIdx, endIdx + 1)
        val allowedUris = sourceFiles.associateBy { it.uriString }
        val allowedNames = sourceFiles.associateBy { it.name.lowercase(Locale.ROOT) }

        return runCatching {
            val arr = JSONArray(jsonSlice)
            val list = mutableListOf<OrganizationSuggestion>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val currentName = obj.optString("currentName").trim()
                val rawUri = obj.optString("sourceUri").trim()
                val matched = allowedUris[rawUri] ?: allowedNames[currentName.lowercase(Locale.ROOT)] ?: continue
                val action = obj.optString("suggestedAction").trim().uppercase(Locale.ROOT)
                if (action != "RENAME" && action != "MOVE") continue
                val target = obj.optString("targetNameOrFolder").trim()
                val reason = obj.optString("reason").trim()
                if (target.isBlank()) continue
                list.add(
                    OrganizationSuggestion(
                        id = "sug_$i",
                        sourceUri = matched.uriString,
                        currentName = matched.name,
                        suggestedAction = action,
                        targetNameOrFolder = target,
                        reason = reason.ifBlank { "Improve clarity and grouping" }
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    private suspend fun analyzePdfDocument(
        context: Context,
        entry: StorageItemEntry,
        question: String
    ): FileAiAnalysisResult {
        val uri = runCatching { Uri.parse(entry.uriString) }.getOrNull()
            ?: return FileAiAnalysisResult(false, entry.name, "", errorMessage = "Invalid PDF URI.")

        val renderedInfo = renderFirstPdfPageToJpeg(context, uri)
        if (renderedInfo == null) {
            return FileAiAnalysisResult(
                success = false,
                title = entry.name,
                content = "",
                errorMessage = "Could not open PDF \"${entry.name}\" (it may be password-protected, corrupted, or inaccessible)."
            )
        }

        val (pageCount, firstPageJpeg) = renderedInfo
        val prompt = buildString {
            append("PDF Document: ${entry.name} (Total pages: $pageCount, showing Page 1).")
            if (question.isNotBlank()) {
                append("\nQuestion: $question")
            } else {
                append("\nExtract visible text/headings and summarize Page 1 clearly. Mention honestly that only Page 1 preview is inspected if further details could be on later pages.")
            }
        }

        val visionResp = MultiProviderRouter.generateVisionAnalysis(
            context = context,
            request = UnifiedAiVisionRequest(
                systemPrompt = "Analyze this rendered PDF page image strictly based on visible text and layout.",
                userPrompt = prompt,
                imageBase64 = Base64.encodeToString(firstPageJpeg, Base64.NO_WRAP),
                mimeType = "image/jpeg"
            )
        )

        return if (visionResp.success) {
            FileAiAnalysisResult(
                success = true,
                title = "${entry.name} ($pageCount pages)",
                content = visionResp.text,
                providerUsed = "${visionResp.providerName} (${visionResp.modelUsed})"
            )
        } else {
            FileAiAnalysisResult(
                success = false,
                title = entry.name,
                content = "",
                errorMessage = if (visionResp.isUnsupportedCapability) {
                    "Unsupported: Active AI provider does not support Vision to read rendered PDF pages. Switch to Gemini, OpenAI, or Anthropic Vision."
                } else {
                    visionResp.errorMessage.ifBlank { "PDF analysis failed." }
                }
            )
        }
    }

    private fun renderFirstPdfPageToJpeg(context: Context, uri: Uri): Pair<Int, ByteArray>? {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val pageCount = renderer.pageCount
                    if (pageCount <= 0) return@use null
                    renderer.openPage(0).use { page ->
                        val maxDim = 1024
                        val scale = minOf(maxDim.toFloat() / page.width.coerceAtLeast(1), maxDim.toFloat() / page.height.coerceAtLeast(1))
                            .coerceAtMost(2.0f)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                        bmp.recycle()
                        pageCount to out.toByteArray()
                    }
                }
            }
        }.getOrNull()
    }
}
