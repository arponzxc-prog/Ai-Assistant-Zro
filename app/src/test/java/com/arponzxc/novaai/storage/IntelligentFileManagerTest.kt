package com.arponzxc.novaai.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntelligentFileManagerTest {

    @Test
    fun `saf boundary guard blocks hidden internal and traversal names or URIs`() {
        assertFalse(SafBoundaryGuard.isSafeDisplayName(".env"))
        assertFalse(SafBoundaryGuard.isSafeDisplayName(".git"))
        assertFalse(SafBoundaryGuard.isSafeDisplayName("../secret.txt"))
        assertFalse(SafBoundaryGuard.isSafeDisplayName("folder/nested.txt"))
        assertFalse(SafBoundaryGuard.isSafeDisplayName("   "))
        assertTrue(SafBoundaryGuard.isSafeDisplayName("Meeting Notes.md"))

        assertTrue(SafBoundaryGuard.isBlockedInternalOrHiddenUri("file:///data/data/com.arponzxc.novaai/files/x"))
        assertTrue(SafBoundaryGuard.isBlockedInternalOrHiddenUri("content://something/data/user/0/secret"))
        assertTrue(SafBoundaryGuard.isBlockedInternalOrHiddenUri(""))
        assertFalse(
            SafBoundaryGuard.isBlockedInternalOrHiddenUri(
                "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
            )
        )
    }

    @Test
    fun `saf file type resolves markdown and pdf formats correctly`() {
        assertEquals(SafFileType.MD, SafFileType.fromFileNameAndType("README.md", null))
        assertEquals(SafFileType.PDF, SafFileType.fromFileNameAndType("report.pdf", "pdf"))
        assertEquals("text/markdown", SafFileType.resolveMimeType("notes.md", null))
        assertEquals("application/pdf", SafFileType.resolveMimeType("invoice.pdf", null))
        assertTrue(SafFileType.defaultContentTemplate("README.md", SafFileType.MD).startsWith("# README"))
    }

    @Test
    fun `saf action coordinator requires explicit user confirmation for move and delete`() {
        SafActionCoordinator.dismissPrompt()
        val moveAction = PendingStorageAction.MoveItem(
            sourceUri = "content://auth/doc1",
            itemName = "budget.csv",
            targetFolderUri = "content://auth/finance",
            targetFolderLabel = "Finance"
        )
        SafActionCoordinator.requestMoveConfirmation("budget.csv", "Finance", moveAction)
        assertTrue(SafActionCoordinator.hasPendingConfirmation())
        assertTrue(SafActionCoordinator.pendingPrompt.value is StorageUiPrompt.ConfirmMove)

        SafActionCoordinator.dismissPrompt()
        assertFalse(SafActionCoordinator.hasPendingConfirmation())

        val deleteAction = PendingStorageAction.DeleteItem("old.txt", "Docs")
        SafActionCoordinator.requestDeleteConfirmation("old.txt", false, "Docs", deleteAction)
        assertTrue(SafActionCoordinator.hasPendingConfirmation())
        assertTrue(SafActionCoordinator.pendingPrompt.value is StorageUiPrompt.ConfirmDelete)
        SafActionCoordinator.dismissPrompt()
    }

    @Test
    fun `ai organization suggestion parser filters valid SAF URIs and safe target names`() {
        val sourceFiles = listOf(
            StorageItemEntry(
                name = "note1.txt",
                uriString = "content://ext/doc/1",
                isDirectory = false,
                sizeBytes = 120L,
                lastModifiedMs = 1000L,
                mimeType = "text/plain",
                parentFolderLabel = "Docs"
            )
        )
        val rawAiJson = """
            ```json
            [
              {
                "sourceUri": "content://ext/doc/1",
                "currentName": "note1.txt",
                "suggestedAction": "RENAME",
                "targetNameOrFolder": "2026_sprint_notes.txt",
                "reason": "Clear descriptive name"
              },
              {
                "sourceUri": "content://ext/doc/invented",
                "currentName": "fake.txt",
                "suggestedAction": "MOVE",
                "targetNameOrFolder": "../escape",
                "reason": "Should be ignored because not in sourceFiles"
              }
            ]
            ```
        """.trimIndent()

        val suggestions = SafFileAiAnalyzer.parseSuggestionsJson(rawAiJson, sourceFiles)
        assertEquals(1, suggestions.size)
        assertEquals("2026_sprint_notes.txt", suggestions.first().targetNameOrFolder)
        assertEquals("RENAME", suggestions.first().suggestedAction)
    }
}
