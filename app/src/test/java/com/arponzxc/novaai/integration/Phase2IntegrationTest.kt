package com.arponzxc.novaai.integration

import com.arponzxc.novaai.automation.command.CommandParser
import com.arponzxc.novaai.automation.command.JarvisCommand
import com.arponzxc.novaai.ui.camerapill.CutoutLocator
import com.arponzxc.novaai.vision.VisionAnalysisMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2IntegrationTest {

    @Test
    fun `command parser routes all phase 2 voice commands in english and bengali`() {
        val stopCmd = CommandParser.parse("NOVA, stop speaking")
        assertTrue(stopCmd.command is JarvisCommand.StopSpeakingCommand)

        val stopBn = CommandParser.parse("চুপ করো")
        assertTrue(stopBn.command is JarvisCommand.StopSpeakingCommand)

        val screenCmd = CommandParser.parse("Hey NOVA what is on my screen")
        assertTrue(screenCmd.command is JarvisCommand.AnalyzeScreenVision)

        val screenBn = CommandParser.parse("নোভা, স্ক্রিনে কি আছে")
        assertTrue(screenBn.command is JarvisCommand.AnalyzeScreenVision)

        val visionCmd = CommandParser.parse("NOVA open vision studio")
        assertTrue(visionCmd.command is JarvisCommand.OpenVisionStudio)

        val researchCmd = CommandParser.parse("deep research Quantum Cryptography")
        assertTrue(researchCmd.command is JarvisCommand.RunResearch)
        assertEquals("Quantum Cryptography", (researchCmd.command as JarvisCommand.RunResearch).query)

        val openResearch = CommandParser.parse("open research center")
        assertTrue(openResearch.command is JarvisCommand.OpenResearchCenter)

        val filesCmd = CommandParser.parse("open intelligent file manager")
        assertTrue(filesCmd.command is JarvisCommand.OpenFileManager)

        val filesBn = CommandParser.parse("ফাইল ম্যানেজার খোলো")
        assertTrue(filesBn.command is JarvisCommand.OpenFileManager)

        val hidePill = CommandParser.parse("hide floating avatar")
        assertEquals(JarvisCommand.ToggleFloatingAvatar(false), hidePill.command)

        val showPill = CommandParser.parse("show camera pill")
        assertEquals(JarvisCommand.ToggleFloatingAvatar(true), showPill.command)

        val apiMgr = CommandParser.parse("open api manager")
        assertTrue(apiMgr.command is JarvisCommand.OpenApiManager)

        val switchProvider = CommandParser.parse("switch provider to openai")
        assertEquals(JarvisCommand.SwitchAiProvider("openai"), switchProvider.command)
    }

    @Test
    fun `cutout locator clamps dragged overlay positions safely inside display bounds`() {
        val (clampedX, clampedY) = CutoutLocator.clampOverlayPosition(
            rawX = 5000,
            rawY = -100,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            pillWidthPx = 300,
            pillHeightPx = 100,
            edgePaddingPx = 24
        )
        assertEquals(1080 - 300 - 24, clampedX)
        assertEquals(12, clampedY)

        val (minX, maxY) = CutoutLocator.clampOverlayPosition(
            rawX = -50,
            rawY = 9999,
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            pillWidthPx = 300,
            pillHeightPx = 100,
            edgePaddingPx = 24
        )
        assertEquals(24, minX)
        assertEquals(2400 - 100 - 24, maxY)
    }

    @Test
    fun `vision analysis modes define non-empty grounded instructions`() {
        assertEquals(5, VisionAnalysisMode.entries.size)
        VisionAnalysisMode.entries.forEach { mode ->
            assertTrue(mode.label.isNotBlank())
            assertTrue(mode.systemInstruction.isNotBlank())
        }
    }
}
