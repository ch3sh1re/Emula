package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class PhoneNfcSessionTest {
    private val first = SavedCard("first", "Home", byteArrayOf(1, 2, 3, 4))
    private val second = SavedCard("second", "Home", byteArrayOf(1, 2, 3, 4))

    private class Backend(initial: List<SavedCard>) : PhoneNfcSession.Backend {
        val cards = initial.toMutableList()
        val calls = mutableListOf<String>()
        var active: String? = null
        var last: String? = null
        var initializationFails = false
        var initializationReady = true
        var changeFails = false
        var consent = false
        var pending = false
        override fun cards(): List<SavedCard> = cards.toList()
        override fun activeId(): String? = active
        override fun lastCardId(): String? = last
        override fun remember(card: SavedCard) { last = card.id }
        override fun forgetActive() { active = null }
        override fun recoveryPending(): Boolean = pending
        override fun preflight(card: SavedCard?): PhoneNfcSession.Reason? =
            if (consent && card != null) PhoneNfcSession.Reason.CONSENT else null
        override fun initialize(): Boolean {
            calls += "restore"
            if (initializationFails) throw IllegalStateException("Restore failed")
            if (!initializationReady) return false
            active = null
            pending = false
            return true
        }
        override fun change(card: SavedCard?, confirmed: Boolean) {
            calls += "change:${card?.id}:$confirmed"
            if (changeFails) throw IllegalStateException("NFC write failed")
            if (consent && card != null && !confirmed) {
                throw PhoneNfcSession.Failure(PhoneNfcSession.Reason.CONSENT)
            }
            active = card?.id
        }
    }

    @Test fun openingUiAfterBackgroundActivationDoesNotResetTheCard() {
        val backend = Backend(listOf(first))
        val session = PhoneNfcSession(backend)
        session.toggle(first.id)
        session.initialize()
        assertEquals(first.id, backend.active)
        assertEquals(listOf("restore", "change:first:false"), backend.calls)
    }

    @Test fun delayedVendorRestartBroadcastDoesNotUndoActivationOrResetOnUiOpen() {
        val backend = Backend(listOf(first))
        val session = PhoneNfcSession(backend)
        session.toggle(first.id)
        backend.pending = true // Persistent vendor config belongs to this active session.
        session.adapterOff(true, true) // OFF is delivered after the setter enabled NFC again.
        session.initialize() // Opening the app must keep the activated card.
        assertEquals(first.id, backend.active)
        assertTrue(backend.pending)
        assertEquals(listOf("restore", "change:first:false"), backend.calls)
    }

    @Test fun finalOffDuringVendorRestartStillInvalidatesActiveCardAndKeepsRecovery() {
        val backend = Backend(listOf(first))
        val session = PhoneNfcSession(backend)
        session.toggle(first.id)
        backend.pending = true
        session.adapterOff(true, false)
        assertNull(backend.active)
        assertTrue(backend.pending)
        // Once NFC is enabled again, opening the app performs recovery.
        session.initialize()
        assertFalse(backend.pending)
        assertEquals(listOf("restore", "change:first:false", "restore"), backend.calls)
    }

    @Test fun coldStopRestoresThePendingSessionExactlyOnce() {
        val backend = Backend(listOf(first)).apply { pending = true }
        PhoneNfcSession(backend).change(null, false)
        assertEquals(listOf("restore"), backend.calls)
        assertFalse(backend.pending)
    }

    @Test fun repeatedTogglesResolveActiveStateWhenExecuted() {
        val backend = Backend(listOf(first))
        val session = PhoneNfcSession(backend)
        session.toggle(first.id)
        session.toggle(first.id)
        assertNull(backend.active)
        assertEquals(listOf("restore", "change:first:false", "change:null:false"), backend.calls)
    }

    @Test fun lastSelectedCardTakesPriorityOverAnotherActiveRecordWithTheSameUid() {
        val backend = Backend(listOf(first, second))
        val session = PhoneNfcSession(backend)
        session.toggle(first.id)
        backend.last = second.id
        session.toggle(null)
        assertEquals(second.id, backend.active)
        assertEquals("change:second:false", backend.calls.last())
    }

    @Test fun staleShortcutDoesNotTriggerStartupWrites() {
        val backend = Backend(listOf(second))
        val failure = assertThrows(PhoneNfcSession.Failure::class.java) {
            PhoneNfcSession(backend).toggle(first.id)
        }
        assertEquals(PhoneNfcSession.Reason.MISSING, failure.reason)
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun missingTileSelectionDoesNotGuessTheFirstCardOrTouchNfc() {
        val backend = Backend(listOf(first, second))
        val failure = assertThrows(PhoneNfcSession.Failure::class.java) {
            PhoneNfcSession(backend).toggle(null)
        }
        assertEquals(PhoneNfcSession.Reason.CHOOSE, failure.reason)
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun failedStartupRestorationBlocksActivationAndIsRetried() {
        val backend = Backend(listOf(first)).apply { initializationFails = true; pending = true }
        val session = PhoneNfcSession(backend)
        repeat(2) { assertThrows(IllegalStateException::class.java) { session.toggle(first.id) } }
        assertEquals(listOf("restore", "restore"), backend.calls)
        assertTrue(backend.pending)
        backend.initializationFails = false
        session.toggle(first.id)
        assertEquals(first.id, backend.active)
    }

    @Test fun currentSavedContentIsUsedInsteadOfTheShortcutCopy() {
        val backend = Backend(listOf(first))
        val session = PhoneNfcSession(backend)
        backend.cards[0] = SavedCard(first.id, "Renamed", ByteArray(7) { it.toByte() })
        assertSame(backend.cards[0], session.selected(first.id))
        session.toggle(first.id)
        assertEquals(first.id, backend.active)
    }

    @Test fun failedDeactivationPreventsDeletionAndReplacement() {
        val backend = Backend(listOf(first))
        val session = PhoneNfcSession(backend)
        session.toggle(first.id)
        backend.changeFails = true
        assertThrows(IllegalStateException::class.java) {
            session.beforeDelete(first.id)
            backend.cards.clear()
        }
        assertThrows(IllegalStateException::class.java) {
            session.beforeReplace()
            backend.cards.clear()
        }
        assertEquals(first.id, backend.active)
        assertEquals(listOf(first), backend.cards)
    }

    @Test fun changingAnActiveUidDeactivatesWhileRenamingKeepsItActive() {
        val backend = Backend(listOf(first))
        val session = PhoneNfcSession(backend)
        session.toggle(first.id)
        session.beforeSave(first.id, first.uid)
        assertEquals(first.id, backend.active)
        session.beforeSave(first.id, byteArrayOf(4, 3, 2, 1))
        assertNull(backend.active)
    }

    @Test fun deletingAnInactiveDuplicateDoesNotDeactivateAnotherRecord() {
        val backend = Backend(listOf(first, second))
        val session = PhoneNfcSession(backend)
        session.toggle(first.id)
        session.beforeDelete(second.id)
        assertEquals(first.id, backend.active)
        assertEquals(2, backend.calls.size)
    }

    @Test fun explicitConfirmationIsNeverInferredByTheBackgroundSession() {
        val backend = Backend(listOf(first)).apply { consent = true }
        val session = PhoneNfcSession(backend)
        assertThrows(PhoneNfcSession.Failure::class.java) { session.toggle(first.id) }
        assertNull(backend.active)
        session.change(first.id, true)
        assertEquals(first.id, backend.active)
        assertEquals("change:first:true", backend.calls.last())
    }

    @Test fun aDeletedEditorRecordCannotBeResurrectedBySavingItsOldId() {
        val backend = Backend(emptyList())
        assertThrows(PhoneNfcSession.Failure::class.java) {
            PhoneNfcSession(backend).beforeSave(first.id, first.uid)
        }
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun failedSwitchDoesNotClaimThatTheRequestedCardBecameActive() {
        val backend = Backend(listOf(first, second))
        val session = PhoneNfcSession(backend)
        session.toggle(first.id)
        backend.changeFails = true
        assertThrows(IllegalStateException::class.java) { session.toggle(second.id) }
        assertEquals(first.id, backend.active)
    }

    @Test fun turningNfcOffPreservesRecoveryWithoutWritesAndNextCommandRestoresFirst() {
        val backend = Backend(listOf(first))
        val session = PhoneNfcSession(backend)
        session.toggle(first.id)
        backend.pending = true
        session.adapterOff()
        assertNull(backend.active)
        assertTrue(backend.pending)
        assertEquals(listOf("restore", "change:first:false"), backend.calls)
        session.toggle(first.id)
        assertEquals(listOf("restore", "change:first:false", "restore", "change:first:false"), backend.calls)
    }

    @Test fun unavailableNfcAtUiStartupDoesNotSkipRestorationAfterItBecomesReady() {
        val backend = Backend(listOf(first)).apply { initializationReady = false }
        val session = PhoneNfcSession(backend)
        session.initialize()
        backend.initializationReady = true
        session.toggle(first.id)
        assertEquals(listOf("restore", "restore", "change:first:false"), backend.calls)
    }

    @Test fun retryAfterAnUnknownControllerStateRestoresTheOriginalSnapshotBeforeActivation() {
        val backend = Backend(listOf(first))
        val session = PhoneNfcSession(backend)
        session.toggle(first.id)
        backend.active = null
        backend.pending = true // Activation rollback could not confirm the previous UID.
        backend.initializationFails = true
        assertThrows(IllegalStateException::class.java) { session.toggle(first.id) }
        assertTrue(backend.pending)
        assertEquals(listOf("restore", "change:first:false", "restore"), backend.calls)
        backend.initializationFails = false
        session.toggle(first.id)
        assertEquals(listOf("restore", "change:first:false", "restore", "restore", "change:first:false"), backend.calls)
        assertFalse(backend.pending)
    }
}
