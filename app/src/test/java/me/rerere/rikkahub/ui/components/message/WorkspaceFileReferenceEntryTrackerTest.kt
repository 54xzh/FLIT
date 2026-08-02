package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFileReferenceEntryTrackerTest {
    @Test
    fun `recognizes files that already existed when a conversation opened`() {
        val candidate = WorkspaceFileReferenceCandidate("workspace-1", "output/report.pdf")
        val tracker = WorkspaceFileReferenceEntryTracker(
            initiallyEnteredKeys = setOf(candidate.workspaceFileReferenceEntryKey()),
        )

        assertTrue(tracker.hasEntered(candidate.workspaceFileReferenceEntryKey()))
    }

    @Test
    fun `same workspace path uses one entry key`() {
        val first = WorkspaceFileReferenceCandidate("workspace-1", "output/report.pdf")
        val second = WorkspaceFileReferenceCandidate("workspace-1", "output/report.pdf")

        assertTrue(first == second)
        assertTrue(first.workspaceFileReferenceEntryKey() == second.workspaceFileReferenceEntryKey())
    }

    @Test
    fun `different workspace paths animate independently`() {
        val first = WorkspaceFileReferenceCandidate("workspace-1", "output/report.pdf")
        val second = WorkspaceFileReferenceCandidate("workspace-1", "output/data.csv")
        val tracker = WorkspaceFileReferenceEntryTracker(setOf(first.workspaceFileReferenceEntryKey()))

        assertNotEquals(first.workspaceFileReferenceEntryKey(), second.workspaceFileReferenceEntryKey())
        assertFalse(tracker.hasEntered(second.workspaceFileReferenceEntryKey()))
    }
}
