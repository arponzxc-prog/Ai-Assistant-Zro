package com.arponzxc.novaai.automation.command

import android.util.Log
import com.arponzxc.novaai.audio.VoiceControlIntent
import com.arponzxc.novaai.automation.phone.PhoneControlManager
import com.arponzxc.novaai.automation.whatsapp.WhatsAppAssistant
import com.arponzxc.novaai.automation.youtube.YouTubeAutomationManager
import com.arponzxc.novaai.background.JarvisConversationService
import com.arponzxc.novaai.data.memory.MemoryManager
import com.arponzxc.novaai.storage.PendingStorageAction
import com.arponzxc.novaai.storage.SafActionCoordinator
import com.arponzxc.novaai.storage.SafFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommandExecutor(
    private val phoneManager: PhoneControlManager,
    private val youtubeManager: YouTubeAutomationManager,
    private val whatsappAssistant: WhatsAppAssistant,
    private val memoryManager: MemoryManager,
    private val safFileManager: SafFileManager
) {
    companion object {
        private const val TAG = "CommandExecutor"
    }

    suspend fun execute(command: JarvisCommand): String = withContext(Dispatchers.Main) {
        Log.i(TAG, "Executing command: $command")
        when (command) {
            // YouTube
            is JarvisCommand.PlayYouTube -> {
                val resolvedSong = memoryManager.resolveSongQuery(command.query)
                memoryManager.trackSongPlay(resolvedSong)
                youtubeManager.playVideo(resolvedSong)
            }
            is JarvisCommand.SearchYouTube -> youtubeManager.searchYouTube(command.query)
            is JarvisCommand.YouTubeAiAssistantCommand -> {
                val apiKey = com.arponzxc.novaai.JarvisApp.instance.youTubePreferences.youtubeApiKey
                val intentRes = com.arponzxc.novaai.automation.youtube.YouTubeIntentHandler.parse(command.query)
                if (intentRes != null) {
                    val (answer, _) = com.arponzxc.novaai.automation.youtube.YouTubeIntentHandler.generateNaturalAnswer(intentRes, apiKey)
                    val ctx = com.arponzxc.novaai.JarvisApp.instance.applicationContext
                    val intent = android.content.Intent(ctx, com.arponzxc.novaai.ui.youtube.YouTubeResultsActivity::class.java).apply {
                        putExtra(com.arponzxc.novaai.ui.youtube.YouTubeResultsActivity.EXTRA_QUERY, command.query)
                        putExtra(com.arponzxc.novaai.ui.youtube.YouTubeResultsActivity.EXTRA_TITLE, "YouTube AI Assistant")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        ctx.startActivity(intent)
                    } catch (_: Exception) {}
                    answer
                } else {
                    youtubeManager.searchYouTube(command.query)
                }
            }
            is JarvisCommand.PauseMedia -> youtubeManager.pausePlayback()
            is JarvisCommand.ResumeMedia -> youtubeManager.resumePlayback()
            is JarvisCommand.NextMedia -> youtubeManager.nextMedia()
            is JarvisCommand.PreviousMedia -> youtubeManager.previousMedia()
            is JarvisCommand.SubscribeChannel -> youtubeManager.subscribeToChannel()
            is JarvisCommand.ToggleFullscreen -> youtubeManager.toggleFullscreen()

            // Phone Controls
            is JarvisCommand.Flash -> phoneManager.setFlashlight(command.enabled)
            is JarvisCommand.Brightness -> {
                if (command.isRelative) {
                    phoneManager.adjustBrightness(command.delta > 0)
                } else {
                    phoneManager.setBrightnessPercent(command.level)
                }
            }
            is JarvisCommand.Volume -> {
                if (command.isRelative) {
                    phoneManager.adjustVolume(command.delta > 0)
                } else {
                    phoneManager.setVolumePercent(command.level)
                }
            }
            is JarvisCommand.OpenApp -> phoneManager.openApp(command.packageName)
            is JarvisCommand.OpenWebUrls -> phoneManager.openMultipleUrls(command.urls)
            is JarvisCommand.PhoneCall -> phoneManager.makePhoneCall(command.contactOrNumber)
            is JarvisCommand.SearchWeb -> phoneManager.searchWeb(command.query)

            // WhatsApp
            is JarvisCommand.WhatsAppMessage -> whatsappAssistant.sendMessageDirectly(command.contact, command.message)
            is JarvisCommand.WhatsAppCall -> whatsappAssistant.makeCall(command.contact, command.video)
            is JarvisCommand.WhatsAppDeleteMessage -> whatsappAssistant.deleteLastMessage(command.contact, command.forEveryone)
            is JarvisCommand.ConfirmAction -> {
                if (command.confirmed) {
                    whatsappAssistant.confirmAndSend()
                } else {
                    whatsappAssistant.cancelDraft()
                }
            }
            is JarvisCommand.DisambiguationChoice -> whatsappAssistant.handleDisambiguationChoice(command.choice)

            // Memory
            is JarvisCommand.Remember -> memoryManager.rememberFact(command.text)
            is JarvisCommand.Forget -> memoryManager.forgetFact(command.query)
            is JarvisCommand.WhatDoYouRemember -> memoryManager.getWhatDoYouRemember()
            is JarvisCommand.RecallBirthday -> memoryManager.getBirthdayResponse()
            is JarvisCommand.RecallFavoriteSong -> memoryManager.getFavoriteSongResponse()
            is JarvisCommand.RecallRoutine -> memoryManager.getRoutineResponse()
            is JarvisCommand.GoToBackground -> "Going to background."

            // Android SAF File & Folder Management
            is JarvisCommand.CreateFolder -> {
                val res = SafActionCoordinator.executePendingAction(
                    safFileManager,
                    PendingStorageAction.CreateFolder(command.folderName, command.parentFolderHint)
                )
                res.message
            }
            is JarvisCommand.CreateFile -> {
                val res = SafActionCoordinator.executePendingAction(
                    safFileManager,
                    PendingStorageAction.CreateOrWriteFile(
                        fileName = command.fileName,
                        content = command.content,
                        fileType = command.fileType,
                        folderHint = command.folderHint,
                        overwriteConfirmed = false
                    )
                )
                res.message
            }
            is JarvisCommand.ListStorageFiles -> {
                val res = safFileManager.listAndFindItems(command.query, command.folderHint)
                if (res.requiresPermission) {
                    SafActionCoordinator.requestFolderPermissionPrompt(
                        reason = res.message,
                        suggestedFolder = res.folderLabel.orEmpty(),
                        pendingAction = null
                    )
                }
                SafActionCoordinator.recordAction("List Files", res.message, res.success)
                res.message
            }
            is JarvisCommand.CheckFileExists -> {
                val res = safFileManager.checkExists(command.itemName, command.folderHint)
                if (res.requiresPermission) {
                    SafActionCoordinator.requestFolderPermissionPrompt(
                        reason = res.message,
                        suggestedFolder = res.folderLabel.orEmpty(),
                        pendingAction = null
                    )
                }
                SafActionCoordinator.recordAction("Check Exists", res.message, res.success)
                res.message
            }
            is JarvisCommand.RenameStorageItem -> {
                val res = SafActionCoordinator.executePendingAction(
                    safFileManager,
                    PendingStorageAction.RenameItem(command.currentName, command.newName, command.folderHint)
                )
                res.message
            }
            is JarvisCommand.DeleteStorageItem -> {
                val res = SafActionCoordinator.executePendingAction(
                    safFileManager,
                    PendingStorageAction.DeleteItem(
                        targetName = command.targetName,
                        folderHint = command.folderHint
                    )
                )
                res.message
            }
            is JarvisCommand.ConfirmStorageAction -> {
                val res = SafActionCoordinator.confirmCurrentPrompt(safFileManager, command.confirmed)
                res?.message ?: "No pending file confirmation found."
            }

            // Continuous Voice Listening Controls
            is JarvisCommand.VoiceListeningControl -> {
                when (command.intent) {
                    VoiceControlIntent.START_LISTENING -> {
                        JarvisConversationService.resumeListening()
                        "Voice listening resumed."
                    }
                    VoiceControlIntent.STOP_LISTENING -> {
                        JarvisConversationService.pauseListening()
                        "Voice listening paused."
                    }
                    VoiceControlIntent.CONTINUE_LISTENING -> {
                        JarvisConversationService.continueListening()
                        "Continuous listening active."
                    }
                    VoiceControlIntent.CANCEL -> {
                        SafActionCoordinator.dismissPrompt()
                        JarvisConversationService.cancelCurrentTurnOrTask()
                        "Cancelled."
                    }
                }
            }

            // Autonomous App & Web Development Studio
            is JarvisCommand.OpenDevStudio -> {
                val ctx = com.arponzxc.novaai.JarvisApp.instance.applicationContext
                val tab = command.initialTab?.let {
                    runCatching { com.arponzxc.novaai.devstudio.model.DevStudioTab.valueOf(it) }.getOrNull()
                }
                val intent = com.arponzxc.novaai.devstudio.ui.DevStudioActivity.createLaunchIntent(
                    context = ctx,
                    initialTab = tab
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                ctx.startActivity(intent)
                "Opening NOVA Autonomous Development Studio."
            }
            is JarvisCommand.GenerateDevProject -> {
                val ctx = com.arponzxc.novaai.JarvisApp.instance.applicationContext
                val type = runCatching {
                    com.arponzxc.novaai.devstudio.model.ProjectType.valueOf(command.projectType)
                }.getOrDefault(com.arponzxc.novaai.devstudio.model.ProjectType.ANDROID_KOTLIN)
                val intent = com.arponzxc.novaai.devstudio.ui.DevStudioActivity.createLaunchIntent(
                    context = ctx,
                    initialTab = com.arponzxc.novaai.devstudio.model.DevStudioTab.BUILDER,
                    autoPrompt = command.prompt,
                    autoProjectType = type
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                ctx.startActivity(intent)
                "Starting ${type.label} workspace generation in NOVA Dev Studio for: ${command.prompt.take(60)}"
            }
            is JarvisCommand.BuildActiveDevProject -> {
                val ctx = com.arponzxc.novaai.JarvisApp.instance.applicationContext
                val wm = com.arponzxc.novaai.devstudio.workspace.DevWorkspaceManager(ctx.filesDir)
                val androidProj = withContext(Dispatchers.IO) {
                    wm.listProjects().firstOrNull { it.type == com.arponzxc.novaai.devstudio.model.ProjectType.ANDROID_KOTLIN }
                }
                if (androidProj == null) {
                    val intent = com.arponzxc.novaai.devstudio.ui.DevStudioActivity.createLaunchIntent(
                        context = ctx,
                        initialTab = com.arponzxc.novaai.devstudio.model.DevStudioTab.BUILDER
                    ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                    ctx.startActivity(intent)
                    "No Android Kotlin project found in Dev Studio workspace. Create or import an Android project first."
                } else {
                    val validation = withContext(Dispatchers.IO) {
                        com.arponzxc.novaai.devstudio.build.BuildAndRunEngine.validateProject(androidProj)
                    }
                    val sharedCfg = com.arponzxc.novaai.devstudio.workspace.SharedWorkspaceBridge(ctx).getConfig()
                    val intent = com.arponzxc.novaai.devstudio.ui.DevStudioActivity.createLaunchIntent(
                        context = ctx,
                        initialTab = com.arponzxc.novaai.devstudio.model.DevStudioTab.BUILD_TERMUX,
                        autoTriggerBuild = validation.isValid && sharedCfg.isAuthorized
                    ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                    ctx.startActivity(intent)
                    when {
                        !validation.isValid -> "Pre-build validation failed for ${androidProj.name}: ${validation.summary}"
                        !sharedCfg.isAuthorized -> "Validated ${androidProj.name}, but NOVA_AI_Projects/ shared folder must be authorized first in Dev Studio."
                        else -> "Validated ${androidProj.name}. Syncing to shared workspace & running Gradle build in Termux."
                    }
                }
            }
            is JarvisCommand.OpenWebLivePreview -> {
                val ctx = com.arponzxc.novaai.JarvisApp.instance.applicationContext
                val wm = com.arponzxc.novaai.devstudio.workspace.DevWorkspaceManager(ctx.filesDir)
                val webProj = withContext(Dispatchers.IO) {
                    wm.listProjects().firstOrNull { it.type == com.arponzxc.novaai.devstudio.model.ProjectType.HTML_WEB }
                }
                val intent = com.arponzxc.novaai.devstudio.ui.DevStudioActivity.createLaunchIntent(
                    context = ctx,
                    initialTab = com.arponzxc.novaai.devstudio.model.DevStudioTab.PREVIEW,
                    autoStartPreview = webProj != null
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                ctx.startActivity(intent)
                if (webProj == null) {
                    "No HTML website project found yet. Opening Dev Studio to create one."
                } else {
                    val vr = withContext(Dispatchers.IO) {
                        com.arponzxc.novaai.devstudio.build.BuildAndRunEngine.validateProject(webProj)
                    }
                    if (vr.isValid) {
                        "Validated '${webProj.name}' and starting local HTTP Live Preview server."
                    } else {
                        "Found website '${webProj.name}' with ${vr.diagnostics.size} validation issue(s): ${vr.summary}"
                    }
                }
            }
            is JarvisCommand.CheckTermuxEnvironment -> {
                val ctx = com.arponzxc.novaai.JarvisApp.instance.applicationContext
                val (status, probeResult) = com.arponzxc.novaai.devstudio.termux.TermuxBridge.probeTermuxToolchainLive(ctx)
                val intent = com.arponzxc.novaai.devstudio.ui.DevStudioActivity.createLaunchIntent(
                    context = ctx,
                    initialTab = com.arponzxc.novaai.devstudio.model.DevStudioTab.BUILD_TERMUX
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                ctx.startActivity(intent)
                if (!status.isTermuxInstalled) {
                    "Termux is not installed on this device. Opening Build & Environment Inspector."
                } else if (!status.isRunCommandPermissionGranted) {
                    "Termux v${status.termuxVersionName ?: ""} is installed, but RUN_COMMAND permission is not granted."
                } else if (probeResult.state == com.arponzxc.novaai.devstudio.model.TermuxCommandState.COMPLETED) {
                    val javaInfo = status.tools.firstOrNull { it.name.contains("Java") }?.statusDetail ?: "Unknown"
                    val gradleInfo = status.tools.firstOrNull { it.name.contains("Gradle") }?.statusDetail ?: "Unknown"
                    "Termux Live Probe OK • Java: $javaInfo • Gradle: $gradleInfo"
                } else {
                    "Termux IPC dispatched, state: ${probeResult.state.label}. ${probeResult.errorMessage.orEmpty()}"
                }
            }
            is JarvisCommand.AnalyzeBuildErrors -> {
                val ctx = com.arponzxc.novaai.JarvisApp.instance.applicationContext
                val wm = com.arponzxc.novaai.devstudio.workspace.DevWorkspaceManager(ctx.filesDir)
                val proj = withContext(Dispatchers.IO) { wm.listProjects().firstOrNull() }
                val intent = com.arponzxc.novaai.devstudio.ui.DevStudioActivity.createLaunchIntent(
                    context = ctx,
                    initialTab = com.arponzxc.novaai.devstudio.model.DevStudioTab.BUILD_TERMUX
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                ctx.startActivity(intent)
                if (proj == null) {
                    "No project in Dev Studio workspace to analyze."
                } else {
                    val vr = withContext(Dispatchers.IO) {
                        com.arponzxc.novaai.devstudio.build.BuildAndRunEngine.validateProject(proj)
                    }
                    val lastErr = proj.lastErrorSummary?.let { " Last error: $it." }.orEmpty()
                    "Analyzed ${proj.name}: ${vr.summary}$lastErr"
                }
            }
            is JarvisCommand.CloseDevProject -> {
                val ctx = com.arponzxc.novaai.JarvisApp.instance.applicationContext
                val intent = com.arponzxc.novaai.devstudio.ui.DevStudioActivity.createLaunchIntent(
                    context = ctx,
                    initialTab = com.arponzxc.novaai.devstudio.model.DevStudioTab.DASHBOARD,
                    closeActiveProject = true
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                ctx.startActivity(intent)
                "Closing active Dev Studio project and stopping runtime preview if clean."
            }

            // Phase 2 Integrated Systems
            is JarvisCommand.AnalyzeScreenVision -> {
                val ctx = com.arponzxc.novaai.JarvisApp.instance.applicationContext
                val res = com.arponzxc.novaai.vision.VisionAnalysisEngine.analyzeCurrentScreen(
                    context = ctx,
                    question = command.question
                )
                if (res.requiresPermissionOrConsent) {
                    val intent = android.content.Intent(ctx, com.arponzxc.novaai.vision.VisionActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                }
                res.answer.ifBlank { res.errorMessage.ifBlank { "Unable to inspect the screen right now." } }
            }

            is JarvisCommand.OpenVisionStudio -> {
                val ctx = com.arponzxc.novaai.JarvisApp.instance.applicationContext
                ctx.startActivity(
                    android.content.Intent(ctx, com.arponzxc.novaai.vision.VisionActivity::class.java)
                        .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
                "Opening NOVA AI Vision Studio."
            }

            is JarvisCommand.RunResearch -> {
                val ctx = com.arponzxc.novaai.JarvisApp.instance.applicationContext
                val mode = com.arponzxc.novaai.research.ResearchMode.entries.firstOrNull {
                    it.name.equals(command.mode, ignoreCase = true) ||
                        it.label.equals(command.mode, ignoreCase = true)
                } ?: com.arponzxc.novaai.research.ResearchMode.DETAILED
                val res = com.arponzxc.novaai.research.ResearchEngine(ctx).performResearch(
                    query = command.query,
                    mode = mode.label
                )
                if (!res.success) {
                    res.errorMessage.ifBlank { "Research failed for '${command.query}'." }
                } else {
                    val topSources = res.sources.take(3).joinToString(", ") { it.title }
                    buildString {
                        append(res.summary)
                        if (topSources.isNotBlank()) {
                            append("\n\nSources: ")
                            append(topSources)
                        }
                    }
                }
            }

            is JarvisCommand.OpenResearchCenter -> {
                val ctx = com.arponzxc.novaai.JarvisApp.instance.applicationContext
                ctx.startActivity(
                    android.content.Intent(ctx, com.arponzxc.novaai.research.ResearchActivity::class.java)
                        .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
                "Opening NOVA AI Knowledge & Research Center."
            }

            is JarvisCommand.OpenFileManager -> {
                val ctx = com.arponzxc.novaai.JarvisApp.instance.applicationContext
                ctx.startActivity(
                    android.content.Intent(ctx, com.arponzxc.novaai.ui.storage.FileManagerActivity::class.java)
                        .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
                "Opening Intelligent AI File Manager."
            }

            is JarvisCommand.ToggleFloatingAvatar -> {
                val app = com.arponzxc.novaai.JarvisApp.instance
                val ctx = app.applicationContext
                when (command.enabled) {
                    true -> {
                        app.preferences.isCameraPillEnabled = true
                        if (!android.provider.Settings.canDrawOverlays(ctx)) {
                            ctx.startActivity(
                                android.content.Intent(
                                    ctx,
                                    com.arponzxc.novaai.ui.camerapill.CameraPillSettingsActivity::class.java
                                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                            )
                            "Enabled Floating Avatar, but Overlay permission is required. Opening Camera Pill settings."
                        } else {
                            com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator.refreshOverlayPermission()
                            com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator.refreshConfigFromPreferences()
                            "Floating AI Avatar enabled."
                        }
                    }
                    false -> {
                        app.preferences.isCameraPillEnabled = false
                        com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator.stopPreview()
                        com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator.refreshConfigFromPreferences()
                        "Floating AI Avatar hidden."
                    }
                    null -> {
                        ctx.startActivity(
                            android.content.Intent(
                                ctx,
                                com.arponzxc.novaai.ui.camerapill.CameraPillSettingsActivity::class.java
                            ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                        )
                        "Opening Floating AI Avatar settings."
                    }
                }
            }

            is JarvisCommand.StopSpeakingCommand -> {
                JarvisConversationService.stopSpeakingNow()
                "Speech stopped."
            }

            is JarvisCommand.OpenApiManager -> {
                val ctx = com.arponzxc.novaai.JarvisApp.instance.applicationContext
                ctx.startActivity(
                    android.content.Intent(ctx, com.arponzxc.novaai.network.apimanager.ApiManagerActivity::class.java)
                        .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
                "Opening Multi-Provider API Manager."
            }

            is JarvisCommand.SwitchAiProvider -> {
                val ctx = com.arponzxc.novaai.JarvisApp.instance.applicationContext
                val q = command.providerQuery.trim().lowercase(java.util.Locale.ROOT)
                val providers = com.arponzxc.novaai.network.apimanager.MultiProviderRouter.getAllProviders(ctx)
                val match = providers.firstOrNull { p ->
                    p.id.equals(q, ignoreCase = true) ||
                        p.name.lowercase(java.util.Locale.ROOT).contains(q) ||
                        (q == "gpt" && p.id == "openai") ||
                        (q == "claude" && p.id == "anthropic")
                }
                if (match == null) {
                    "No AI provider matching '${command.providerQuery}' was found. Add it in API Manager."
                } else if (!match.isEnabled) {
                    "Provider '${match.name}' is disabled. Enable it in Multi-Provider API Manager."
                } else {
                    val storage = com.arponzxc.novaai.network.apimanager.SecureKeyStorage(ctx)
                    val key = storage.getApiKey(match.id).ifBlank {
                        if (match.id == "gemini") com.arponzxc.novaai.JarvisApp.instance.preferences.apiKey else ""
                    }
                    if (key.isBlank()) {
                        "Provider '${match.name}' has no API key configured yet. Please add a key in API Manager."
                    } else {
                        storage.saveSelectedProvider(match.id)
                        com.arponzxc.novaai.network.apimanager.MultiProviderRouter.syncGeminiWithPreferences(ctx)
                        val liveNote = if (match.id != "gemini") {
                            " REST chat, Research, and Vision will use ${match.name}; live duplex voice remains Gemini-only."
                        } else ""
                        "Switched default AI provider to ${match.name}.$liveNote"
                    }
                }
            }
        }
    }
}
