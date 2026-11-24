package com.arponzxc.novaai.automation.command

sealed class JarvisCommand {
    data class PlayYouTube(val query: String) : JarvisCommand()
    data class SearchYouTube(val query: String) : JarvisCommand()
    data class OpenApp(val packageName: String) : JarvisCommand()
    data class WhatsAppMessage(val contact: String, val message: String) : JarvisCommand()
    data class WhatsAppCall(val contact: String, val video: Boolean) : JarvisCommand()
    data class WhatsAppDeleteMessage(val contact: String?, val forEveryone: Boolean) : JarvisCommand()
    object SubscribeChannel : JarvisCommand()
    object ToggleFullscreen : JarvisCommand()
    data class Brightness(val level: Int, val isRelative: Boolean = false, val delta: Int = 0) : JarvisCommand()
    data class Volume(val level: Int, val isRelative: Boolean = false, val delta: Int = 0) : JarvisCommand()
    data class Flash(val enabled: Boolean) : JarvisCommand()
    data class Remember(val text: String) : JarvisCommand()
    data class Forget(val query: String) : JarvisCommand()
    object WhatDoYouRemember : JarvisCommand()
    data class SearchWeb(val query: String) : JarvisCommand()
    data class OpenWebUrls(val urls: List<String>) : JarvisCommand()
    data class PhoneCall(val contactOrNumber: String) : JarvisCommand()
    object PauseMedia : JarvisCommand()
    object ResumeMedia : JarvisCommand()
    object NextMedia : JarvisCommand()
    object PreviousMedia : JarvisCommand()
    data class ConfirmAction(val confirmed: Boolean) : JarvisCommand()
    data class DisambiguationChoice(val choice: String) : JarvisCommand()
    object RecallBirthday : JarvisCommand()
    object RecallFavoriteSong : JarvisCommand()
    object RecallRoutine : JarvisCommand()
    object GoToBackground : JarvisCommand()

    // Android SAF Storage Commands (Voice & Text)
    data class CreateFolder(val folderName: String, val parentFolderHint: String = "") : JarvisCommand()
    data class CreateFile(
        val fileName: String,
        val content: String = "",
        val fileType: String? = null,
        val folderHint: String = ""
    ) : JarvisCommand()
    data class ListStorageFiles(val query: String = "", val folderHint: String = "") : JarvisCommand()
    data class CheckFileExists(val itemName: String, val folderHint: String = "") : JarvisCommand()
    data class RenameStorageItem(
        val currentName: String,
        val newName: String,
        val folderHint: String = ""
    ) : JarvisCommand()
    data class DeleteStorageItem(val targetName: String, val folderHint: String = "") : JarvisCommand()
    data class ConfirmStorageAction(val confirmed: Boolean) : JarvisCommand()

    // Continuous Voice Listening Control
    data class VoiceListeningControl(val intent: com.arponzxc.novaai.audio.VoiceControlIntent) : JarvisCommand()

    // YouTube AI Assistant Command
    data class YouTubeAiAssistantCommand(val query: String) : JarvisCommand()

    // Autonomous App & Web Development Studio Commands
    data class OpenDevStudio(val initialTab: String? = null) : JarvisCommand()
    data class GenerateDevProject(
        val prompt: String,
        val projectType: String
    ) : JarvisCommand()
    object OpenWebLivePreview : JarvisCommand()
    object CheckTermuxEnvironment : JarvisCommand()
    object BuildActiveDevProject : JarvisCommand()
    object AnalyzeBuildErrors : JarvisCommand()
    object CloseDevProject : JarvisCommand()

    // Phase 2 Integrated Systems Commands
    data class AnalyzeScreenVision(val question: String = "Describe what is currently visible on the screen.") : JarvisCommand()
    object OpenVisionStudio : JarvisCommand()
    data class RunResearch(val query: String, val mode: String = "NORMAL") : JarvisCommand()
    object OpenResearchCenter : JarvisCommand()
    object OpenFileManager : JarvisCommand()
    data class ToggleFloatingAvatar(val enabled: Boolean? = null) : JarvisCommand()
    object StopSpeakingCommand : JarvisCommand()
    object OpenApiManager : JarvisCommand()
    data class SwitchAiProvider(val providerQuery: String) : JarvisCommand()
}
