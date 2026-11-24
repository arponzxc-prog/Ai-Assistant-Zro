package com.arponzxc.novaai.camerapill

import android.graphics.Rect
import com.arponzxc.novaai.ui.camerapill.CameraPillAiState
import com.arponzxc.novaai.ui.camerapill.CameraPillConfig
import com.arponzxc.novaai.ui.camerapill.CameraPillDisplayMode
import com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator
import com.arponzxc.novaai.ui.camerapill.CutoutAlignment
import com.arponzxc.novaai.ui.camerapill.CutoutLocator
import com.arponzxc.novaai.ui.camerapill.CutoutPlacement
import com.arponzxc.novaai.util.PromptGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPillCoordinatorTest {

    @Test
    fun `only while talking hides after idle delay and shows while speaking or listening`() {
        val cfg = CameraPillConfig(
            enabled = true,
            displayMode = CameraPillDisplayMode.ONLY_WHILE_TALKING,
            idleHideDelayMs = 3000L
        )
        val t0 = 10_000L

        assertTrue(
            CameraPillStateCoordinator.computeVisibility(
                config = cfg,
                hasOverlayPermission = true,
                isSessionActive = true,
                isStandbyActive = false,
                aiState = CameraPillAiState.LISTENING,
                isPreviewActive = false,
                lastActiveTimestampMs = t0,
                nowMs = t0 + 100L
            )
        )

        assertTrue(
            CameraPillStateCoordinator.computeVisibility(
                config = cfg,
                hasOverlayPermission = true,
                isSessionActive = true,
                isStandbyActive = false,
                aiState = CameraPillAiState.IDLE,
                isPreviewActive = false,
                lastActiveTimestampMs = t0,
                nowMs = t0 + 2500L
            )
        )

        assertFalse(
            CameraPillStateCoordinator.computeVisibility(
                config = cfg,
                hasOverlayPermission = true,
                isSessionActive = true,
                isStandbyActive = false,
                aiState = CameraPillAiState.IDLE,
                isPreviewActive = false,
                lastActiveTimestampMs = t0,
                nowMs = t0 + 3500L
            )
        )
    }

    @Test
    fun `always on stays visible while assistant active and hides when session off`() {
        val cfg = CameraPillConfig(
            enabled = true,
            displayMode = CameraPillDisplayMode.ALWAYS_ON
        )

        assertTrue(
            CameraPillStateCoordinator.computeVisibility(
                config = cfg,
                hasOverlayPermission = true,
                isSessionActive = true,
                isStandbyActive = false,
                aiState = CameraPillAiState.IDLE,
                isPreviewActive = false,
                lastActiveTimestampMs = 0L,
                nowMs = 50_000L
            )
        )

        assertFalse(
            CameraPillStateCoordinator.computeVisibility(
                config = cfg,
                hasOverlayPermission = true,
                isSessionActive = false,
                isStandbyActive = false,
                aiState = CameraPillAiState.IDLE,
                isPreviewActive = false,
                lastActiveTimestampMs = 0L,
                nowMs = 50_000L
            )
        )
    }

    @Test
    fun `permission denial prevents overlay visibility even in preview`() {
        val cfg = CameraPillConfig(enabled = true)
        assertFalse(
            CameraPillStateCoordinator.computeVisibility(
                config = cfg,
                hasOverlayPermission = false,
                isSessionActive = true,
                isStandbyActive = false,
                aiState = CameraPillAiState.SPEAKING,
                isPreviewActive = true,
                lastActiveTimestampMs = 1000L,
                nowMs = 1000L
            )
        )
    }

    private fun testRect(left: Int, top: Int, right: Int, bottom: Int): Rect = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    @Test
    fun `cutout locator aligns left center right and clamps safely within screen bounds`() {
        assertEquals(CutoutAlignment.CENTER, CutoutLocator.resolveAlignment(testRect(490, 0, 590, 80), 1080))
        assertEquals(CutoutAlignment.LEFT, CutoutLocator.resolveAlignment(testRect(40, 0, 140, 80), 1080))
        assertEquals(CutoutAlignment.RIGHT, CutoutLocator.resolveAlignment(testRect(920, 0, 1020, 80), 1080))

        val centerPlacement = CutoutPlacement(
            alignment = CutoutAlignment.CENTER,
            cutoutRect = testRect(490, 0, 590, 90),
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            statusBarTopPx = 90,
            rotation = 0
        )
        val (cx, cy) = CutoutLocator.computeOverlayWindowPosition(
            placement = centerPlacement,
            pillWidthPx = 420,
            pillHeightPx = 100,
            edgePaddingPx = 24,
            topFallbackOffsetPx = 20
        )
        assertEquals(330, cx)
        assertTrue(cy >= 12)
    }

    @Test
    fun `prompt generator includes Arpon creator and owner attribution`() {
        val prompt = PromptGenerator.generateSystemPrompt(
            personality = "Nova Pro",
            userName = "Test"
        )
        assertTrue(prompt.contains("আমার বস অর্পন (Arpon)"))
        assertTrue(prompt.contains("My boss Arpon"))
    }
}
