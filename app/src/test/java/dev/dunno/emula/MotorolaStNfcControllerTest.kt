package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class MotorolaStNfcControllerTest {
    private val uid = byteArrayOf(4, 0x34, 0x0C, 0x6A, 0x66, 0x16, 0x90.toByte())
    private val firmware = "motorola/boston_g/boston:15/test"

    @Test
    fun experimentalCandidateRemainsLimitedToBostonOnAndroid15() {
        fun target(enabled: Boolean = true, sdk: Int = 35, manufacturer: String? = "motorola",
                   model: String? = "moto g stylus 5G - 2024", device: String? = "boston") =
            MotorolaStNfcController.isTargetDevice(enabled, sdk, manufacturer, model, device)
        assertTrue(target())
        assertFalse(target(enabled = false))
        assertFalse(target(sdk = 34))
        assertFalse(target(sdk = 36))
        assertFalse(target(manufacturer = "OnePlus"))
        assertFalse(target(model = "moto g stylus 5G - 2023"))
        assertFalse(target(device = "avatrn"))
        assertFalse(target(manufacturer = null, model = null, device = null))
    }

    @Test
    fun disabledCandidateAllowsOnlyTheApiNeededForAnExistingSnapshot() {
        fun allowed(pending: Boolean, sdk: Int = 35, manufacturer: String = "motorola",
                    model: String = "moto g stylus 5G - 2024", device: String = "boston") =
            MotorolaStNfcController.isApiAllowed(false, pending, sdk, manufacturer, model, device)
        assertFalse(allowed(false))
        assertTrue(allowed(true))
        assertFalse(MotorolaStNfcController.isTargetDevice(false, 35,
            "motorola", "moto g stylus 5G - 2024", "boston"))
        assertFalse(allowed(true, sdk = 36))
        assertFalse(allowed(true, manufacturer = "Samsung"))
        assertFalse(allowed(true, model = "moto g stylus 5G - 2023"))
        assertFalse(allowed(true, device = "avatrn"))
    }

    @Test
    fun disabledOrUntargetedActivationStopsBeforeAccessingTheAndroidContext() {
        val report = report()
        val failure = expectFailure {
            MotorolaStNfcController.setCardEnabled(null, uid, true, report)
        }
        assertTrue(failure.message!!.contains("activation is disabled"))
        assertNull(report.vendorCallSucceeded)
        assertNull(report.rollbackAttempted)
    }

    @Test
    fun snapshotPrecedesVendorWriteAndSurvivesCardSwitches() {
        val store = MemoryStore()
        val api = FakeApi()
        val original = RawNciCodec.encodeConfiguration(api.values)
        api.beforeProgram = { assertNotNull(store.snapshot) }
        val first = activate(api, store)
        assertEquals("ST_CONFIG_READBACK_VERIFIED_READER_UNVERIFIED", first.result)
        assertArrayEquals(uid, api.values[0x33])
        val second = activate(api, store, byteArrayOf(1, 2, 3, 4))
        assertEquals("REUSED_PENDING", second.snapshotState)
        assertEquals(1, store.saves)
        assertEquals(original, RawNciCodec.encodeConfiguration(store.snapshot!!.configuration))

        deactivate(api, store)
        assertNull(store.snapshot)
        assertEquals(original, RawNciCodec.encodeConfiguration(api.values))
        assertEquals("set:133", api.calls.last()) // Control restored after RF parameters.
    }

    @Test
    fun voidNoOpDoesNotCountAsActivationAndRollsBack() {
        val store = MemoryStore()
        val api = FakeApi().apply { ignoreActivation = true }
        val report = report()
        val failure = expectFailure { change(api, store, true, report) }
        assertTrue(failure.message!!.contains("readback mismatch"))
        assertTrue(report.rollbackAttempted)
        assertTrue(report.rollbackSucceeded)
        assertNull(store.snapshot)
        assertTrue(api.calls.contains("program:false"))
        assertEquals("ST_ORIGINAL_NCI_CONFIG_VERIFIED", report.stRollback.result)
    }

    @Test
    fun failedRollbackKeepsSnapshotAndPrimaryFailureSeparate() {
        val store = MemoryStore()
        val api = FakeApi().apply { ignoreActivation = true; failDisable = true }
        val report = report()
        val failure = expectFailure { change(api, store, true, report) }
        assertTrue(failure.message!!.contains("readback mismatch"))
        assertTrue(report.failure.contains("readback mismatch"))
        assertFalse(report.rollbackSucceeded)
        assertTrue(report.rollbackFailure.contains("disable failed"))
        assertNotNull(store.snapshot)

        api.failDisable = false
        deactivate(api, store) // A new controller/session can retry the persisted restore.
        assertNull(store.snapshot)
    }

    @Test
    fun failedSnapshotPersistenceNeverCallsVendorSetter() {
        val api = FakeApi()
        val store = MemoryStore().apply { failSave = true }
        expectFailure { activate(api, store) }
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun missingSnapshotParameterNeverCallsVendorSetter() {
        val api = FakeApi().apply { values.remove(0x59) }
        expectFailure { activate(api, MemoryStore()) }
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun existingCustomConfigurationIsNotReplaced() {
        val api = FakeApi().apply { values[0x85] = byteArrayOf(0) }
        expectFailure { activate(api, MemoryStore()) }
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun restoreIsBlockedAfterFirmwareChangeAndWithoutSnapshotIsReadOnly() {
        val api = FakeApi()
        val store = MemoryStore()
        deactivate(api, store)
        assertTrue(api.calls.isEmpty())
        store.snapshot = MotorolaStNfcController.Snapshot("other-firmware", api.values)
        expectFailure { deactivate(api, store) }
        assertTrue(api.calls.isEmpty())
        assertNotNull(store.snapshot)
    }

    @Test
    fun silentRestoreNoOpKeepsSnapshotForRetry() {
        val store = MemoryStore()
        val api = FakeApi()
        activate(api, store)
        api.ignoreRestoreSets = true
        expectFailure { deactivate(api, store) }
        assertNotNull(store.snapshot)
    }

    @Test
    fun vendorExceptionAfterPartialWriteRestoresOriginalValues() {
        val store = MemoryStore()
        val api = FakeApi().apply { throwAfterActivation = true }
        val original = RawNciCodec.encodeConfiguration(api.values)
        val report = report()
        val failure = expectFailure { change(api, store, true, report) }
        assertEquals("vendor failed after writing", failure.message)
        assertTrue(report.rollbackSucceeded)
        assertNull(store.snapshot)
        assertEquals(original, RawNciCodec.encodeConfiguration(api.values))
    }

    @Test
    fun verifiedCandidateStopsTheChainWithoutTryingAnotherApi() {
        val api = FakeApi()
        val store = MemoryStore()
        val report = report()
        var fallbackCalls = 0
        if (!MotorolaStNfcController.tryFirst(true,
                { change(api, store, true, report) }, { store.snapshot != null }, report)) fallbackCalls++
        assertEquals(0, fallbackCalls)
        assertEquals("ST_CONFIG_READBACK_VERIFIED_READER_UNVERIFIED", report.result)
        assertEquals(listOf("program:true"), api.calls)
        assertNotNull(store.snapshot)
    }

    @Test
    fun missingApiBeforeWritesAllowsFallbackAndKeepsTheFailure() {
        val report = report()
        assertFalse(MotorolaStNfcController.tryFirst(true,
            { throw ClassNotFoundException("ST library missing") }, { false }, report))
        assertTrue(report.failure.contains("ST library missing"))
        assertEquals("FAILED", report.stage)
    }

    @Test
    fun silentActivationFailureAllowsFallbackOnlyAfterVerifiedRestoration() {
        val api = FakeApi().apply { ignoreActivation = true }
        val store = MemoryStore()
        val report = report()
        val original = RawNciCodec.encodeConfiguration(api.values)
        assertFalse(MotorolaStNfcController.tryFirst(true,
            { change(api, store, true, report) }, { store.snapshot != null }, report))
        assertTrue(report.rollbackSucceeded)
        assertNull(store.snapshot)
        assertEquals(original, RawNciCodec.encodeConfiguration(api.values))
    }

    @Test
    fun failedRestorationStopsTheChainAndKeepsTheSnapshot() {
        val api = FakeApi().apply { ignoreActivation = true; failDisable = true }
        val store = MemoryStore()
        val report = report()
        var fallbackCalls = 0
        expectFailure {
            if (!MotorolaStNfcController.tryFirst(true,
                    { change(api, store, true, report) }, { store.snapshot != null }, report)) fallbackCalls++
        }
        assertEquals(0, fallbackCalls)
        assertNotNull(store.snapshot)
        assertFalse(report.rollbackSucceeded)
    }

    @Test
    fun missingApiWithAPreviousPendingSessionCannotFallThrough() {
        val report = report()
        expectFailure {
            MotorolaStNfcController.tryFirst(true,
                { throw ClassNotFoundException("ST library disappeared") }, { true }, report)
        }
        assertTrue(report.notes.last().contains("Fallback blocked"))
    }

    @Test
    fun failedDeactivationNeverChangesApisEvenIfTheStoreLooksEmpty() {
        expectFailure {
            MotorolaStNfcController.tryFirst(false,
                { error("snapshot clear commit failed") }, { false }, report())
        }
    }

    @Test
    fun failedRollbackCannotBeHiddenByAClearedInMemoryPreference() {
        val report = report().apply { rollbackAttempted = true; rollbackSucceeded = false }
        expectFailure {
            MotorolaStNfcController.tryFirst(true,
                { error("snapshot clear commit failed") }, { false }, report)
        }
    }

    @Test
    fun preexistingCustomRfIsSkippedWithoutVendorWrites() {
        val api = FakeApi().apply { values[0x85] = byteArrayOf(0) }
        val store = MemoryStore()
        val report = report()
        assertFalse(MotorolaStNfcController.tryFirst(true,
            { change(api, store, true, report) }, { store.snapshot != null }, report))
        assertTrue(api.calls.isEmpty())
        assertNull(store.snapshot)
    }

    private fun report() = OemOperationReport(OemOperationReport.Operation.ACTIVATE)
    private fun activate(api: FakeApi, store: MemoryStore, target: ByteArray = uid) = report().also {
        MotorolaStNfcController.change(api, store, firmware, target, true, it)
    }
    private fun deactivate(api: FakeApi, store: MemoryStore) = report().also {
        change(api, store, false, it)
    }
    private fun change(api: FakeApi, store: MemoryStore, enabled: Boolean, report: OemOperationReport) {
        MotorolaStNfcController.change(api, store, firmware, uid, enabled, report)
    }
    private fun expectFailure(block: () -> Unit): Exception {
        try { block() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected failure")
    }

    private class MemoryStore : MotorolaStNfcController.Store {
        var snapshot: MotorolaStNfcController.Snapshot? = null
        var saves = 0
        var failSave = false
        override fun load() = snapshot
        override fun save(value: MotorolaStNfcController.Snapshot) {
            if (failSave) error("persistence failed")
            snapshot = value
            saves++
        }
        override fun clear() { snapshot = null }
    }

    private class FakeApi : MotorolaStNfcController.Api {
        val values = linkedMapOf(
            0x30 to byteArrayOf(4), 0x31 to byteArrayOf(0), 0x32 to byteArrayOf(0x20),
            0x33 to byteArrayOf(8, 0, 0, 0), 0x58 to byteArrayOf(0x70),
            0x59 to byteArrayOf(), 0x85 to byteArrayOf(1),
        )
        val calls = mutableListOf<String>()
        var ignoreActivation = false
        var ignoreRestoreSets = false
        var failDisable = false
        var throwAfterActivation = false
        var beforeProgram: () -> Unit = {}
        override fun program(enabled: Boolean, uid: ByteArray) {
            beforeProgram()
            calls += "program:$enabled"
            if (!enabled) {
                if (failDisable) error("disable failed")
                values[0x33] = byteArrayOf()
                values[0x85] = byteArrayOf(1)
            } else if (!ignoreActivation) {
                values[0x30] = byteArrayOf(6)
                values[0x31] = byteArrayOf(0x0C)
                values[0x32] = byteArrayOf(0x20)
                values[0x33] = uid.copyOf()
                values[0x58] = byteArrayOf(0x40)
                values[0x59] = byteArrayOf()
                values[0x85] = byteArrayOf(0)
                if (throwAfterActivation) error("vendor failed after writing")
            }
        }
        override fun setConfiguration(id: Int, value: ByteArray) {
            calls += "set:$id"
            if (!ignoreRestoreSets) values[id] = value.copyOf()
        }
        override fun readConfiguration(report: OemOperationReport) = values
    }
}
