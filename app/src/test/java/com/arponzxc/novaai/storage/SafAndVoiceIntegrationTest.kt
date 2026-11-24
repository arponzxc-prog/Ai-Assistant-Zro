package com.arponzxc.novaai.storage

import com.arponzxc.novaai.audio.AudioFrequencyData
import com.arponzxc.novaai.audio.ContinuousVoiceVadEngine
import com.arponzxc.novaai.audio.NoiseSensitivity
import com.arponzxc.novaai.audio.VadFrameDecision
import com.arponzxc.novaai.audio.VoiceControlCommandDetector
import com.arponzxc.novaai.audio.VoiceControlIntent
import com.arponzxc.novaai.audio.VoiceListeningConfig
import com.arponzxc.novaai.automation.command.CommandParser
import com.arponzxc.novaai.automation.command.JarvisCommand
import com.arponzxc.novaai.util.TimeGreetingHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafAndVoiceIntegrationTest {

    @Test
    fun timeGreetingHelperCoversAllDayPeriodsAndDefaultName() {
        assertEquals("Good Morning, Arpon Gang", TimeGreetingHelper.formatGreeting(5, ""))
        assertEquals("Good Morning, Arpon Gang", TimeGreetingHelper.formatGreeting(11, "   "))
        assertEquals("Good Afternoon, Arpon Gang", TimeGreetingHelper.formatGreeting(12, "Arpon Gang"))
        assertEquals("Good Afternoon, Arpon", TimeGreetingHelper.formatGreeting(16, "Arpon"))
        assertEquals("Good Evening, Arpon Gang", TimeGreetingHelper.formatGreeting(17, "Arpon Gang"))
        assertEquals("Good Evening, Arpon Gang", TimeGreetingHelper.formatGreeting(20, "Arpon Gang"))
        assertEquals("Good Night, Arpon Gang", TimeGreetingHelper.formatGreeting(21, "Arpon Gang"))
        assertEquals("Good Night, Arpon Gang", TimeGreetingHelper.formatGreeting(3, ""))
    }

    @Test
    fun safFileTypeResolvesMimeTypesAndTemplates() {
        assertEquals(SafFileType.TXT, SafFileType.fromFileNameAndType("Notes.txt", null))
        assertEquals(SafFileType.JSON, SafFileType.fromFileNameAndType("config.json", "json"))
        assertEquals(SafFileType.HTML, SafFileType.fromFileNameAndType("index.html", null))
        assertEquals(SafFileType.CSS, SafFileType.fromFileNameAndType("style.css", "css"))
        assertEquals(SafFileType.JS, SafFileType.fromFileNameAndType("app.js", "javascript"))
        assertEquals(SafFileType.CUSTOM, SafFileType.fromFileNameAndType("data.xml", null))

        assertEquals("application/xml", SafFileType.resolveMimeType("data.xml", null))
        assertEquals("text/plain", SafFileType.resolveMimeType("Main.kt", null))

        val htmlTemplate = SafFileType.defaultContentTemplate("portfolio.html", SafFileType.HTML)
        assertTrue(htmlTemplate.contains("<!DOCTYPE html>"))
        assertTrue(htmlTemplate.contains("<title>portfolio</title>"))

        val jsonTemplate = SafFileType.defaultContentTemplate("app.json", SafFileType.JSON)
        assertTrue(jsonTemplate.contains("\"name\": \"app\""))
        assertTrue(jsonTemplate.contains("\"createdBy\": \"NOVA AI\""))

        val cssTemplate = SafFileType.defaultContentTemplate("theme.css", SafFileType.CSS)
        assertTrue(cssTemplate.contains("--bg-color"))

        val jsTemplate = SafFileType.defaultContentTemplate("main.js", SafFileType.JS)
        assertTrue(jsTemplate.contains("export function main()"))
    }

    @Test
    fun continuousVoiceVadHandlesNoiseFloorSpectralGateAndPauseHold() {
        val config = VoiceListeningConfig(
            continuousModeEnabled = true,
            autoResumeAfterReply = true,
            silenceTimeoutMs = 1500L,
            noiseSensitivity = NoiseSensitivity.BALANCED,
            voiceStartFrames = 3,
            minUtteranceFrames = 5
        )
        val vad = ContinuousVoiceVadEngine(config)
        vad.resetAll(nowMs = 1000L)

        // Borderline low-frequency fan/AC hum should be rejected by spectral gate
        val fanRumble = AudioFrequencyData(amplitude = 0.065f, bass = 0.85f, mid = 0.08f, high = 0.02f)
        repeat(5) { idx ->
            val decision = vad.evaluateFrame(0.065f, fanRumble, 1020L + idx * 20L)
            assertEquals(VadFrameDecision.NOISE_OR_SILENCE, decision)
        }
        assertFalse(vad.hasSpeechInCurrentUtterance)

        // Clear speech frames start and continue utterance
        val speech = AudioFrequencyData(amplitude = 0.25f, bass = 0.20f, mid = 0.50f, high = 0.30f)
        assertEquals(VadFrameDecision.NOISE_OR_SILENCE, vad.evaluateFrame(0.25f, speech, 1200L))
        assertEquals(VadFrameDecision.NOISE_OR_SILENCE, vad.evaluateFrame(0.25f, speech, 1220L))
        assertEquals(VadFrameDecision.SPEECH_STARTED, vad.evaluateFrame(0.25f, speech, 1240L))
        assertEquals(VadFrameDecision.SPEECH_CONTINUING, vad.evaluateFrame(0.25f, speech, 1260L))
        assertEquals(VadFrameDecision.SPEECH_CONTINUING, vad.evaluateFrame(0.25f, speech, 1280L))
        assertEquals(VadFrameDecision.SPEECH_CONTINUING, vad.evaluateFrame(0.25f, speech, 1300L))
        assertEquals(VadFrameDecision.SPEECH_CONTINUING, vad.evaluateFrame(0.25f, speech, 1320L))
        assertTrue(vad.hasValidUtterance())

        // Mid-sentence natural pause < 1500ms stays in SPEECH_PAUSED_HOLD
        val silence = AudioFrequencyData(amplitude = 0.01f, bass = 0.01f, mid = 0.01f, high = 0.01f)
        assertEquals(VadFrameDecision.SPEECH_PAUSED_HOLD, vad.evaluateFrame(0.01f, silence, 2100L))
        assertEquals(VadFrameDecision.SPEECH_PAUSED_HOLD, vad.evaluateFrame(0.01f, silence, 2700L))

        // Silence >= 1500ms triggers SPEECH_ENDED
        assertEquals(VadFrameDecision.SPEECH_ENDED, vad.evaluateFrame(0.01f, silence, 2820L))

        // Clamp bounds check (500..3500ms)
        assertEquals(500L, config.copy(silenceTimeoutMs = 100L).clampedSilenceTimeoutMs())
        assertEquals(3500L, config.copy(silenceTimeoutMs = 9000L).clampedSilenceTimeoutMs())
    }

    @Test
    fun voiceControlDetectorRecognizesEnglishAndBengaliCommands() {
        assertEquals(VoiceControlIntent.START_LISTENING, VoiceControlCommandDetector.detect("NOVA, Start Listening"))
        assertEquals(VoiceControlIntent.STOP_LISTENING, VoiceControlCommandDetector.detect("NOVA, Stop Listening"))
        assertEquals(VoiceControlIntent.CONTINUE_LISTENING, VoiceControlCommandDetector.detect("Hey NOVA, Continue Listening"))
        assertEquals(VoiceControlIntent.CANCEL, VoiceControlCommandDetector.detect("NOVA, Cancel"))

        assertEquals(VoiceControlIntent.START_LISTENING, VoiceControlCommandDetector.detect("নোভা, শোনা শুরু করো।"))
        assertEquals(VoiceControlIntent.STOP_LISTENING, VoiceControlCommandDetector.detect("নোভা, শোনা বন্ধ করো।"))
        assertEquals(VoiceControlIntent.CONTINUE_LISTENING, VoiceControlCommandDetector.detect("শুনতে থাকো"))
        assertEquals(VoiceControlIntent.CANCEL, VoiceControlCommandDetector.detect("বাতিল করো"))

        assertNull(VoiceControlCommandDetector.detect("Create a folder named Projects"))
    }

    @Test
    fun commandParserParsesBengaliAndEnglishSafStorageCommands() {
        // 1. Bengali root folder creation
        val createRootFolder = CommandParser.parse("NOVA, আমার ফোনে Projects নামে একটি নতুন Folder তৈরি করো।")
        assertTrue(createRootFolder.command is JarvisCommand.CreateFolder)
        val rootFolderCmd = createRootFolder.command as JarvisCommand.CreateFolder
        assertEquals("Projects", rootFolderCmd.folderName)
        assertEquals("", rootFolderCmd.parentFolderHint)

        // 2. Bengali folder creation inside Downloads
        val createSubFolder = CommandParser.parse("Download Folder-এর মধ্যে AI নামে একটি নতুন Folder তৈরি করো।")
        assertTrue(createSubFolder.command is JarvisCommand.CreateFolder)
        val subFolderCmd = createSubFolder.command as JarvisCommand.CreateFolder
        assertEquals("AI", subFolderCmd.folderName)
        assertEquals("Downloads", subFolderCmd.parentFolderHint)

        // 3. Bengali named txt file creation
        val createNotes = CommandParser.parse("Notes.txt নামে একটি Text File তৈরি করো।")
        assertTrue(createNotes.command is JarvisCommand.CreateFile)
        val notesCmd = createNotes.command as JarvisCommand.CreateFile
        assertEquals("Notes.txt", notesCmd.fileName)
        assertEquals("txt", notesCmd.fileType)

        // 4. Bengali HTML file inside Documents folder
        val createHtml = CommandParser.parse("Documents Folder-এর মধ্যে একটা HTML File তৈরি করো।")
        assertTrue(createHtml.command is JarvisCommand.CreateFile)
        val htmlCmd = createHtml.command as JarvisCommand.CreateFile
        assertEquals("index.html", htmlCmd.fileName)
        assertEquals("html", htmlCmd.fileType)
        assertEquals("Documents", htmlCmd.folderHint)

        // 5. Bengali JSON file with name and version content
        val createJson = CommandParser.parse("একটি নতুন JSON File তৈরি করো এবং তার ভিতরে name ও version লেখো।")
        assertTrue(createJson.command is JarvisCommand.CreateFile)
        val jsonCmd = createJson.command as JarvisCommand.CreateFile
        assertEquals("data.json", jsonCmd.fileName)
        assertEquals("json", jsonCmd.fileType)
        assertTrue(jsonCmd.content.contains("\"name\""))
        assertTrue(jsonCmd.content.contains("\"version\""))

        // 6. Bengali JS file creation
        val createJs = CommandParser.parse("JavaScript File তৈরি করো।")
        assertTrue(createJs.command is JarvisCommand.CreateFile)
        val jsCmd = createJs.command as JarvisCommand.CreateFile
        assertEquals("script.js", jsCmd.fileName)
        assertEquals("js", jsCmd.fileType)

        // 7. English create, list, exists, rename, delete
        val enCreate = CommandParser.parse("NOVA, create a CSS file named style.css in Projects folder")
        assertTrue(enCreate.command is JarvisCommand.CreateFile)
        val enCreateCmd = enCreate.command as JarvisCommand.CreateFile
        assertEquals("style.css", enCreateCmd.fileName)
        assertEquals("css", enCreateCmd.fileType)
        assertEquals("Projects", enCreateCmd.folderHint)

        val enList = CommandParser.parse("List files in Documents")
        assertTrue(enList.command is JarvisCommand.ListStorageFiles)
        assertEquals("Documents", (enList.command as JarvisCommand.ListStorageFiles).folderHint)

        val enExists = CommandParser.parse("Check if Notes.txt exists in Downloads")
        assertTrue(enExists.command is JarvisCommand.CheckFileExists)
        val existsCmd = enExists.command as JarvisCommand.CheckFileExists
        assertEquals("Notes.txt", existsCmd.itemName)
        assertEquals("Downloads", existsCmd.folderHint)

        val enRename = CommandParser.parse("Rename file Notes.txt in Documents to Todo.txt")
        assertTrue(enRename.command is JarvisCommand.RenameStorageItem)
        val renameCmd = enRename.command as JarvisCommand.RenameStorageItem
        assertEquals("Notes.txt", renameCmd.currentName)
        assertEquals("Todo.txt", renameCmd.newName)
        assertEquals("Documents", renameCmd.folderHint)

        val enDelete = CommandParser.parse("Delete file Notes.txt from Downloads")
        assertTrue(enDelete.command is JarvisCommand.DeleteStorageItem)
        val deleteCmd = enDelete.command as JarvisCommand.DeleteStorageItem
        assertEquals("Notes.txt", deleteCmd.targetName)
        assertEquals("Downloads", deleteCmd.folderHint)

        // 8. Storage overwrite / delete confirmation handling
        val confirmEn = CommandParser.parse("Yes", isAwaitingStorageConfirmation = true)
        assertEquals(JarvisCommand.ConfirmStorageAction(true), confirmEn.command)

        val confirmBn = CommandParser.parse("হ্যাঁ, করো", isAwaitingStorageConfirmation = true)
        assertEquals(JarvisCommand.ConfirmStorageAction(true), confirmBn.command)

        val cancelBn = CommandParser.parse("না", isAwaitingStorageConfirmation = true)
        assertEquals(JarvisCommand.ConfirmStorageAction(false), cancelBn.command)
    }
}
