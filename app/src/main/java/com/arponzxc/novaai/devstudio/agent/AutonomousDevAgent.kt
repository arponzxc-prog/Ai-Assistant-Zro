package com.arponzxc.novaai.devstudio.agent

import android.content.Context
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.devstudio.builder.AndroidProjectGenerator
import com.arponzxc.novaai.devstudio.builder.WebProjectGenerator
import com.arponzxc.novaai.devstudio.model.BuildErrorDiagnostic
import com.arponzxc.novaai.devstudio.model.CodeDiffEntry
import com.arponzxc.novaai.devstudio.model.DevChangeProposal
import com.arponzxc.novaai.devstudio.model.DevProject
import com.arponzxc.novaai.devstudio.model.DiffAction
import com.arponzxc.novaai.devstudio.model.GeneratedFileSpec
import com.arponzxc.novaai.devstudio.model.GeneratedProjectPlan
import com.arponzxc.novaai.devstudio.model.ProjectType
import com.arponzxc.novaai.devstudio.workspace.DevWorkspaceManager
import com.arponzxc.novaai.network.GeminiTextChatEngine
import com.arponzxc.novaai.network.apimanager.MultiProviderRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AutonomousDevAgent(
    private val context: Context,
    private val chatEngine: GeminiTextChatEngine = GeminiTextChatEngine()
) {

    private fun resolveApiKey(): String {
        val routerKey = runCatching { MultiProviderRouter.getActiveApiKey(context) }.getOrDefault("")
        if (routerKey.isNotBlank()) return routerKey
        return runCatching { JarvisApp.instance.preferences.apiKey }.getOrDefault("")
    }

    private fun resolveModel(): String {
        return runCatching { JarvisApp.instance.preferences.aiModel }.getOrDefault("models/gemini-2.5-flash")
    }

    suspend fun planAndGenerateProject(
        prompt: String,
        projectType: ProjectType,
        requestedName: String? = null
    ): GeneratedProjectPlan = withContext(Dispatchers.IO) {
        val apiKey = resolveApiKey().trim()
        if (apiKey.isBlank()) {
            val basePlan = when (projectType) {
                ProjectType.ANDROID_KOTLIN -> AndroidProjectGenerator.generateProjectPlan(prompt, requestedName)
                ProjectType.HTML_WEB -> WebProjectGenerator.generateProjectPlan(prompt, requestedName)
            }
            return@withContext basePlan.copy(
                limitationNotice = "Template Fallback Mode (Gemini API Key not configured): Generated a fixed starter template rather than custom AI architecture. Add your Gemini API key in Settings -> API & Model for custom generation."
            )
        }

        val typeInstruction = when (projectType) {
            ProjectType.ANDROID_KOTLIN -> """
                Generate a complete native Kotlin Android Studio Gradle project (minSdk 26, compileSdk 34, targetSdk 34, Kotlin, AndroidManifest.xml, settings.gradle.kts, build.gradle.kts, app/build.gradle.kts, strings.xml, themes.xml, activity_main.xml, MainActivity.kt).
                Use clean AndroidX AppCompat/Material 3 components and ensure balanced braces and exact matching package names.
            """.trimIndent()
            ProjectType.HTML_WEB -> """
                Generate a complete responsive multi-file web app (index.html, style.css, script.js, components/header.js, data/app-data.json).
                Make sure it works completely offline/over http://127.0.0.1 with zero broken external CDN scripts, clean modern design, interactive JS, and responsive mobile-first layout.
            """.trimIndent()
        }

        val systemPrompt = """
            You are NOVA AI Autonomous Software Architect.
            Return ONLY valid JSON matching this schema (no markdown explanation outside JSON):
            {
              "projectName": "Short App Name",
              "packageName": "com.novastudio.appname",
              "summary": "Brief architectural summary",
              "architectureNotes": ["note 1", "note 2"],
              "files": [
                { "path": "relative/path.ext", "description": "purpose", "content": "full file content" }
              ]
            }
            $typeInstruction
        """.trimIndent()

        val nameHint = if (!requestedName.isNullOrBlank()) "Preferred Name: $requestedName\n" else ""
        val result = chatEngine.generateReply(
            apiKey = apiKey,
            configuredModel = resolveModel(),
            systemPrompt = systemPrompt,
            recentTurns = emptyList(),
            userMessage = "${nameHint}Build specification:\n$prompt",
            toolDeclarations = JSONArray(),
            toolExecutor = null
        )

        if (result.success) {
            val parsed = parseAiProjectJson(result.replyText, projectType)
            if (parsed != null && parsed.files.isNotEmpty()) {
                return@withContext ensureEssentialProjectFiles(parsed, prompt, projectType)
                    .copy(generatedByAi = true, limitationNotice = null)
            }
        }

        val fallbackReason = if (!result.success) result.errorMessage else "AI response could not be parsed as valid project JSON"
        val fallbackPlan = when (projectType) {
            ProjectType.ANDROID_KOTLIN -> AndroidProjectGenerator.generateProjectPlan(prompt, requestedName)
            ProjectType.HTML_WEB -> WebProjectGenerator.generateProjectPlan(prompt, requestedName)
        }
        fallbackPlan.copy(
            limitationNotice = "Template Fallback Mode ($fallbackReason): Generated a fixed starter template instead of custom AI code."
        )
    }

    suspend fun explainCode(project: DevProject, filePath: String, content: String): String =
        withContext(Dispatchers.IO) {
            val apiKey = resolveApiKey().trim()
            if (apiKey.isBlank()) {
                val lines = content.lines().size
                return@withContext "Offline Code Analysis ($filePath, $lines lines):\n" +
                    "• Project Type: ${project.type.label}\n" +
                    "• Configure a Gemini API Key in Settings for deep AI semantic explanation."
            }
            val result = chatEngine.generateReply(
                apiKey = apiKey,
                configuredModel = resolveModel(),
                systemPrompt = "You are a senior Android & Web code reviewer. Explain the file clearly and concisely with key symbols, data flow, and potential edge cases.",
                recentTurns = emptyList(),
                userMessage = "Project: ${project.name} (${project.type.label})\nFile: $filePath\n\n```\n${content.take(12_000)}\n```",
                toolDeclarations = JSONArray()
            )
            if (result.success) result.replyText else "AI explain failed: ${result.errorMessage}"
        }

    suspend fun proposeScopedCodeEdit(
        workspace: DevWorkspaceManager,
        project: DevProject,
        filePath: String,
        currentContent: String,
        instruction: String
    ): Result<DevChangeProposal> = withContext(Dispatchers.IO) {
        val apiKey = resolveApiKey().trim()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Gemini API key is required for AI code edits. Add your key in Settings -> API & Model.")
            )
        }

        val sysPrompt = """
            You are a precise code editing assistant. Return ONLY valid JSON:
            {
              "title": "Short change title",
              "rationale": "Why this edit solves the request",
              "updatedContent": "COMPLETE updated file content"
            }
        """.trimIndent()

        val result = chatEngine.generateReply(
            apiKey = apiKey,
            configuredModel = resolveModel(),
            systemPrompt = sysPrompt,
            recentTurns = emptyList(),
            userMessage = "Project: ${project.name} (${project.type.label})\nTarget File: $filePath\nInstruction: $instruction\n\nCurrent file:\n```\n${currentContent.take(14_000)}\n```",
            toolDeclarations = JSONArray()
        )
        if (!result.success) {
            return@withContext Result.failure(IllegalStateException(result.errorMessage))
        }

        val jsonObj = extractJsonObject(result.replyText)
            ?: return@withContext Result.failure(IllegalStateException("AI did not return structured JSON edit."))
        val updatedContent = jsonObj.optString("updatedContent", "")
        if (updatedContent.isBlank()) {
            return@withContext Result.failure(IllegalStateException("AI returned empty updated file content."))
        }
        val title = jsonObj.optString("title", "AI Edit: $filePath")
        val rationale = jsonObj.optString("rationale", instruction)
        val diff: CodeDiffEntry = workspace.computeDiffEntry(
            relativePath = filePath,
            beforeContent = currentContent,
            afterContent = updatedContent,
            action = DiffAction.MODIFY
        )
        Result.success(
            DevChangeProposal(
                id = UUID.randomUUID().toString(),
                projectId = project.id,
                title = title,
                rationale = rationale,
                diffs = listOf(diff)
            )
        )
    }

    suspend fun proposeBuildErrorFix(
        workspace: DevWorkspaceManager,
        project: DevProject,
        diagnostics: List<BuildErrorDiagnostic>
    ): Result<DevChangeProposal> = withContext(Dispatchers.IO) {
        val primaryDiag = diagnostics.firstOrNull()
            ?: return@withContext Result.failure(IllegalStateException("No diagnostics available to fix."))

        val tree = workspace.listFileTree(project.id).filter { !it.isDirectory }
        val candidatePath = tree.firstOrNull {
            it.relativePath.equals(primaryDiag.file, ignoreCase = true) ||
                primaryDiag.file.endsWith(it.name, ignoreCase = true)
        }?.relativePath ?: project.entryPoint

        val beforeContent = runCatching { workspace.readFile(project.id, candidatePath) }.getOrDefault("")
        val errorDesc = diagnostics.joinToString("\n") {
            "[${it.severity}] ${it.category} in ${it.file}:${it.line} -> ${it.message} (Hint: ${it.suggestedFix})"
        }
        proposeScopedCodeEdit(
            workspace = workspace,
            project = project,
            filePath = candidatePath,
            currentContent = beforeContent,
            instruction = "Fix these build/validation errors without breaking existing behavior:\n$errorDesc"
        )
    }

    private fun parseAiProjectJson(rawText: String, projectType: ProjectType): GeneratedProjectPlan? {
        val obj = extractJsonObject(rawText) ?: return null
        val filesArr = obj.optJSONArray("files") ?: return null
        val files = mutableListOf<GeneratedFileSpec>()
        for (i in 0 until filesArr.length()) {
            val item = filesArr.optJSONObject(i) ?: continue
            val path = item.optString("path", "").trim().replace('\\', '/').trimStart('/')
            val content = item.optString("content", "")
            val desc = item.optString("description", "")
            if (path.isNotBlank() && !path.contains("..")) {
                files += GeneratedFileSpec(path, content, desc)
            }
        }
        if (files.isEmpty()) return null
        val notesArr = obj.optJSONArray("architectureNotes")
        val notes = mutableListOf<String>()
        if (notesArr != null) {
            for (i in 0 until notesArr.length()) {
                notesArr.optString(i).takeIf { it.isNotBlank() }?.let { notes += it }
            }
        }
        val name = obj.optString("projectName", if (projectType == ProjectType.ANDROID_KOTLIN) "NovaApp" else "NovaWeb")
        val pkg = obj.optString("packageName", "com.novastudio.app")
        return GeneratedProjectPlan(
            projectName = name,
            packageName = pkg,
            summary = obj.optString("summary", "Generated by NOVA AI Architect"),
            architectureNotes = notes,
            files = files,
            generatedByAi = true
        )
    }

    private fun ensureEssentialProjectFiles(
        plan: GeneratedProjectPlan,
        prompt: String,
        type: ProjectType
    ): GeneratedProjectPlan {
        val existingPaths = plan.files.map { it.relativePath }.toSet()
        val baseline = when (type) {
            ProjectType.ANDROID_KOTLIN -> AndroidProjectGenerator.generateProjectPlan(prompt, plan.projectName, plan.packageName)
            ProjectType.HTML_WEB -> WebProjectGenerator.generateProjectPlan(prompt, plan.projectName)
        }
        val merged = plan.files.toMutableList()
        baseline.files.forEach { baseFile ->
            if (baseFile.relativePath !in existingPaths) {
                merged += baseFile
            }
        }
        return plan.copy(files = merged)
    }

    private fun extractJsonObject(raw: String): JSONObject? {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(trimmed.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }
}
