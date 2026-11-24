package com.arponzxc.novaai.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VoskModelManagerTest {

    @Test
    fun hasValidAsrModelRejectsDirectoryMissingIvectorExtractor() {
        val tempDir = Files.createTempDirectory("vosk-incomplete").toFile()
        try {
            // Simulate the incomplete asset layout missing ivector/ and graph/HCLr.fst
            listOf(
                "am/final.mdl",
                "conf/model.conf",
                "conf/mfcc.conf",
                "graph/Gr.fst"
            ).forEach { rel ->
                val f = File(tempDir, rel)
                f.parentFile?.mkdirs()
                f.writeText("stub")
            }
            assertFalse(VoskModelManager.hasValidAsrModel(tempDir))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun hasValidAsrModelAcceptsCompleteModelLayout() {
        val tempDir = Files.createTempDirectory("vosk-complete").toFile()
        try {
            VoskModelManager.REQUIRED_ASR_FILES.forEach { rel ->
                val f = File(tempDir, rel)
                f.parentFile?.mkdirs()
                f.writeText("non-empty")
            }
            assertTrue(VoskModelManager.hasValidAsrModel(tempDir))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun hasValidSpeakerModelChecksAllRequiredFiles() {
        val tempDir = Files.createTempDirectory("vosk-spk").toFile()
        try {
            VoskModelManager.REQUIRED_SPK_FILES.dropLast(1).forEach { rel ->
                val f = File(tempDir, rel)
                f.parentFile?.mkdirs()
                f.writeText("non-empty")
            }
            assertFalse(VoskModelManager.hasValidSpeakerModel(tempDir))

            val last = File(tempDir, VoskModelManager.REQUIRED_SPK_FILES.last())
            last.writeText("non-empty")
            assertTrue(VoskModelManager.hasValidSpeakerModel(tempDir))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun stripSingleRootFolderStripsZipTopLevelDirectory() {
        assertEquals(
            "ivector/final.ie",
            VoskModelManager.stripSingleRootFolder("vosk-model-small-en-us-0.15/ivector/final.ie")
        )
        assertEquals(
            "",
            VoskModelManager.stripSingleRootFolder("vosk-model-small-en-us-0.15/")
        )
        assertEquals(
            "am/final.mdl",
            VoskModelManager.stripSingleRootFolder("/vosk-model-small-en-us-0.15/am/final.mdl")
        )
    }
}
