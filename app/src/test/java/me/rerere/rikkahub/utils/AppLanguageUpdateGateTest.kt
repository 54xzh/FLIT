package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageUpdateGateTest {
    @Test
    fun deferUntilRestart_blocksUpdatesUntilResumed() {
        val gate = AppLanguageUpdateGate()

        assertFalse(gate.isDeferredUntilRestart.value)

        val token = gate.deferUntilRestart()
        assertTrue(gate.isDeferredUntilRestart.value)

        gate.resumeUpdates(token)
        assertFalse(gate.isDeferredUntilRestart.value)
    }

    @Test
    fun nestedDeferrals_areReleasedByTheirOwnTokens() {
        val gate = AppLanguageUpdateGate()
        val first = gate.deferUntilRestart()
        val second = gate.deferUntilRestart()
        gate.markRestartRequired(first)

        gate.resumeUpdates(second)
        assertTrue(gate.isDeferredUntilRestart.value)
        assertTrue(gate.isRestartRequired.value)

        gate.resumeUpdates(first)
        assertFalse(gate.isDeferredUntilRestart.value)
        assertFalse(gate.isRestartRequired.value)
    }

    @Test
    fun applyIfUpdatesAllowed_checksTheLatestStateAtomically() {
        val gate = AppLanguageUpdateGate()
        var applyCount = 0

        assertTrue(gate.applyIfUpdatesAllowed { applyCount++ })
        val token = gate.deferUntilRestart()
        assertFalse(gate.applyIfUpdatesAllowed { applyCount++ })
        assertTrue(gate.isDeferredUntilRestart.value)
        assertEquals(1, applyCount)

        gate.resumeUpdates(token)
        assertTrue(gate.applyIfUpdatesAllowed { applyCount++ })
        assertEquals(2, applyCount)
    }

    @Test
    fun restartIsRequiredOnlyAfterSuccessfulRestoreIsMarked() {
        val gate = AppLanguageUpdateGate()
        val token = gate.deferUntilRestart()

        assertFalse(gate.isRestartRequired.value)

        gate.markRestartRequired(token)
        assertTrue(gate.isRestartRequired.value)
    }
}
