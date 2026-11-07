package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class XiaomiNfcControllerTest {
    private val firmware = XiaomiNfcController.KNOWN_BASELINE_FINGERPRINT
    private val uid = byteArrayOf(4, 0x12, 0x34, 0x56)

    private class Store : XiaomiNfcController.Store {
        var pending: String? = null
        var reset: String? = null
        var legacy = true
        var failSave = false
        var failClear = false
        override fun load() = pending?.let { XiaomiNfcController.Recovery(it, reset, legacy) }
        override fun save(recovery: XiaomiNfcController.Recovery) {
            check(!failSave) { "disk full" }
            pending = recovery.fingerprint
            reset = recovery.reset
            legacy = false
        }
        override fun clear() {
            check(!failClear) { "disk full" }
            pending = null
            reset = null
        }
    }

    private class Api(private val store: Store) : XiaomiNfcController.ConfigApi {
        val calls = mutableListOf<String?>()
        var activateStatus = 0
        var resetStatus = 0
        var failActivation = false
        var failReset = false
        var ready = true
        var defaultsStatus = 0
        var failPrepare = false
        var baseline = "NXP_SET_CONFIG_ALWAYS=0x01\n" + XiaomiNxpConfig.CORE_CONF_206
        var rfBaseline = "NXP_RF_CONF_BLK_12={20,02,05,01,A0,00,01,00}\n"
        override fun prepare(uid: ByteArray?): XiaomiNxpConfig.Plan {
            check(!failPrepare) { "config unavailable" }
            return XiaomiNxpConfig.plan(baseline, rfBaseline, uid)
        }
        override fun setConfig(value: String?): Int {
            assertNotNull("Recovery must be durable before every service write", store.pending)
            calls += value
            if (value != null && value == store.reset) return defaultsStatus
            check(!(if (value == null) failReset else failActivation)) { "dead service" }
            return if (value == null) resetStatus else activateStatus
        }
        override fun nfcReady() = ready
    }

    private fun report() = OemOperationReport(OemOperationReport.Operation.ACTIVATE)
    private fun change(api: Api, store: Store, uid: ByteArray?, report: OemOperationReport = report()) =
        XiaomiNfcController.transact(api, store, firmware, uid, report)

    @Test fun targetUsesExactModelAndProductAcrossSupportedAndroidVersions() {
        fun target(sdk: Int = 35, manufacturer: String? = "Xiaomi", model: String? = "2312DRA50G",
                   device: String? = "garnet", product: String? = "garnet_global") =
            XiaomiNfcController.isTargetDevice(sdk, manufacturer, model, device, product)
        assertTrue(target())
        assertTrue(target(manufacturer = "xiaomi"))
        assertTrue(target(sdk = 36))
        assertTrue(target(sdk = 37))
        assertTrue(target(sdk = 38))
        assertFalse(target(sdk = 34))
        assertFalse(target(model = "23122PCD1G")) // POCO uses a different SKU.
        assertFalse(target(manufacturer = "Samsung"))
        assertFalse(target(product = "garnet"))
        assertFalse(target(device = "other"))
        assertFalse(target(manufacturer = null, model = null, device = null, product = null))
    }

    @Test fun firmwareProbeRejectsNoResponseAndStubValues() {
        assertTrue(XiaomiNfcController.validFirmware(byteArrayOf(1, 0, 2)))
        assertFalse(XiaomiNfcController.validFirmware(null))
        assertFalse(XiaomiNfcController.validFirmware(byteArrayOf(1, 2)))
        assertFalse(XiaomiNfcController.validFirmware(byteArrayOf(0, 0, 0)))
        assertFalse(XiaomiNfcController.validFirmware(byteArrayOf(-1, -1, -1)))
    }

    @Test fun ditingTargetUsesExactGlobalProIdentityAcrossSupportedAndroidVersions() {
        fun target(sdk: Int = 35, manufacturer: String? = "Xiaomi", model: String? = "22081212UG",
                   device: String? = "diting", product: String? = "ditingp_global") =
            XiaomiNfcController.isTargetDevice(sdk, manufacturer, model, device, product)
        for (sdk in 35..38) assertTrue(target(sdk = sdk))
        assertTrue(target(manufacturer = "xiaomi"))
        assertFalse(target(sdk = 34))
        assertFalse(target(manufacturer = "other"))
        assertFalse(target(model = "other"))
        assertFalse(target(device = "ditingp"))
        assertFalse(target(product = "diting_global"))
        assertFalse(target(product = "diting"))
        assertFalse(target(product = null))
    }

    @Test fun xiaomiModelDeviceAndProductMustBelongToTheSameTuple() {
        assertFalse(XiaomiNfcController.isTargetDevice(35, "Xiaomi", "22081212UG", "garnet", "garnet_global"))
        assertFalse(XiaomiNfcController.isTargetDevice(35, "Xiaomi", "2312DRA50G", "diting", "ditingp_global"))
        assertFalse(XiaomiNfcController.isTargetDevice(35, "Xiaomi", "22081212UG", "diting", "garnet_global"))
    }

    @Test fun ditingSessionKeepsItsFirmwareParametersAndCanRequestDefaultsOnStop() {
        val ditingFirmware = "Xiaomi/ditingp_global/diting:15/AQ3A.250226.002/OS3.0.4.0.VLFMIXM:user/release-keys"
        val store = Store()
        // Synthetic different defaults exercise firmware ownership; not a diting firmware dump.
        val api = Api(store).apply {
            baseline = baseline.replace("30,01,04", "30,01,08")
                .replace("32,01,60", "32,01,20")
        }
        val report = report()
        XiaomiNfcController.transact(api, store, ditingFirmware, uid, report)
        assertEquals(ditingFirmware, store.pending)
        val configured = XiaomiNxpConfig.parse(api.calls.single()!!)
        assertArrayEquals(byteArrayOf(8), configured[0x30])
        assertArrayEquals(byteArrayOf(0x20), configured[0x32])
        assertArrayEquals(uid, configured[0x33])
        assertEquals("Xiaomi / NXP transit config (experimental)", report.profile)
        assertEquals("VENDOR_CONFIG_ACCEPTED_READER_UNVERIFIED", report.result)
        XiaomiNfcController.transact(api, store, ditingFirmware, null, report())
        assertNull(api.calls.last())
        assertNull(store.pending)
    }

    @Test fun generatedConfigRetainsFirmwareDefaultsExceptUidAndConfigControl() {
        val defaults = mapOf(0x28 to 0, 0x21 to 0, 0x30 to 4, 0x31 to 0, 0x32 to 0x60,
            0x38 to 1, 0x54 to 6, 0x50 to 2, 0x5B to 0, 0x3E to 0, 0x80 to 1,
            0x81 to 1, 0x82 to 0x0E, 0x18 to 1, 0x68 to 1)
        for (size in listOf(4, 7, 10)) {
            val input = ByteArray(size) { (it * 17).toByte() }
            val config = XiaomiNxpConfig.configuration(XiaomiNxpConfig.CORE_CONF_206, input)
            assertTrue(config.endsWith("}\n"))
            val bytes = config.substringAfter('{').substringBefore('}').split(',')
                .map { it.toInt(16) }
            assertEquals(listOf(0x20, 2), bytes.take(2))
            assertEquals(bytes.size - 3, bytes[2])
            assertEquals(17, bytes[3])
            val parsed = linkedMapOf<Int, List<Int>>()
            var index = 4
            while (index < bytes.size) {
                val id = bytes[index++]
                val count = bytes[index++]
                assertNull(parsed.put(id, bytes.subList(index, index + count)))
                index += count
            }
            assertEquals(17, parsed.size)
            assertEquals(0x85, parsed.keys.first())
            assertEquals(listOf(0), parsed[0x85])
            assertEquals(input.map { it.toInt() and 255 }, parsed[0x33])
            defaults.forEach { (id, value) -> assertEquals("param $id", listOf(value), parsed[id]) }
        }
    }

    @Test fun invalidUidDoesNotPersistOrWrite() {
        val store = Store()
        val api = Api(store)
        assertThrows(IllegalArgumentException::class.java) { change(api, store, byteArrayOf(1)) }
        assertNull(store.pending)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun unreadableOrInvalidFirmwareConfigurationPreventsPersistenceAndAnySetter() {
        val store = Store()
        val api = Api(store).apply { baseline = "not a valid NFC configuration" }
        assertThrows(IllegalArgumentException::class.java) { change(api, store, uid) }
        assertNull(store.pending)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun successRetainsRecoveryAndDoesNotClaimReaderVerification() {
        val store = Store()
        val api = Api(store)
        val report = report()
        change(api, store, uid, report)
        assertEquals(firmware, store.pending)
        assertEquals(1, api.calls.size)
        assertEquals("VENDOR_CONFIG_ACCEPTED_READER_UNVERIFIED", report.result)
        assertTrue(report.formatDetailed().contains("not verified by this report"))
        assertArrayEquals(uid, report.uid)
    }

    @Test fun startupWithoutOurMarkerDoesNotDeleteAnotherApplicationsOverride() {
        val store = Store()
        val api = Api(store)
        change(api, store, null)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun aNewSessionCanResetUsingTheDurableMarker() {
        val store = Store()
        change(Api(store), store, uid)
        val newApi = Api(store)
        val report = report()
        change(newApi, store, null, report)
        assertEquals(listOf<String?>(null), newApi.calls)
        assertNull(store.pending)
        assertEquals("DEFAULT_RESET_ACCEPTED_NFC_READY", report.result)
    }

    @Test fun failedActivationResetsAndPreservesThePrimaryStatus() {
        val store = Store()
        val api = Api(store).apply { activateStatus = 255 }
        val report = report()
        assertThrows(IllegalStateException::class.java) { change(api, store, uid, report) }
        assertEquals(2, api.calls.size)
        assertNull(api.calls.last())
        assertNull(store.pending)
        assertEquals(255, report.vendorStatus)
        assertTrue(report.rollbackSucceeded)
        assertEquals(0, report.vendorRollback.vendorStatus)
        assertTrue(report.failure.contains("255"))
    }

    @Test fun serviceDeathAlsoAttemptsResetAndRetainsMarkerIfResetFails() {
        val store = Store()
        val api = Api(store).apply { failActivation = true; resetStatus = 255 }
        val report = report()
        assertThrows(IllegalStateException::class.java) { change(api, store, uid, report) }
        assertEquals(firmware, store.pending)
        assertTrue(report.failure.contains("dead service"))
        assertFalse(report.rollbackSucceeded)
        assertTrue(report.rollbackFailure.contains("255"))
        assertEquals(255, report.vendorRollback.vendorStatus)
        api.resetStatus = 0
        change(api, store, null)
        assertNull(store.pending)
    }

    @Test fun acceptedStatusWhileNfcRemainsOffDoesNotClearRecovery() {
        val store = Store().apply { pending = firmware }
        val api = Api(store).apply { ready = false }
        assertThrows(IllegalStateException::class.java) { change(api, store, null) }
        assertEquals(firmware, store.pending)
    }

    @Test fun firmwareChangeBlocksBothActivationAndResetWithoutDiscardingRecovery() {
        val store = Store().apply { pending = "previous firmware" }
        val api = Api(store)
        assertThrows(IllegalStateException::class.java) { change(api, store, uid) }
        assertThrows(IllegalStateException::class.java) { change(api, store, null) }
        assertTrue(api.calls.isEmpty())
        assertEquals("previous firmware", store.pending)
    }

    @Test fun persistenceFailureBeforeActivationNeverCallsTheService() {
        val store = Store().apply { failSave = true }
        val api = Api(store)
        assertThrows(IllegalStateException::class.java) { change(api, store, uid) }
        assertTrue(api.calls.isEmpty())
    }

    @Test fun failedMarkerCleanupAllowsAnIdempotentResetRetry() {
        val store = Store().apply { pending = firmware; failClear = true }
        val api = Api(store)
        assertThrows(IllegalStateException::class.java) { change(api, store, null) }
        assertEquals(firmware, store.pending)
        store.failClear = false
        change(api, store, null)
        assertNull(store.pending)
        assertEquals(listOf<String?>(null, null), api.calls)
    }

    private fun rfApi(store: Store) = Api(store).apply {
        baseline = baseline.replace("NXP_SET_CONFIG_ALWAYS=0x01", "NXP_SET_CONFIG_ALWAYS=0x00")
    }

    @Test fun transitRfActivationPersistsDefaultsBeforeTheWriteAndRecoversWithoutRereadingFiles() {
        val store = Store()
        val api = rfApi(store)
        change(api, store, uid)
        assertTrue(api.calls.single()!!.startsWith("NXP_RF_CONF_BLK_13="))
        val reset = store.reset!!
        assertFalse(reset.contains("04,12,34,56"))
        assertFalse(store.legacy)
        val restarted = Api(store).apply { failPrepare = true }
        val report = report()
        change(restarted, store, null, report)
        assertEquals(listOf(reset, null), restarted.calls)
        assertNull(store.pending)
        assertEquals(listOf("XIAOMI_APPLY_FIRMWARE_DEFAULTS", "XIAOMI_REMOVE_TRANSIT_OVERRIDE"),
            report.transitCalls.map { it.stage })
        assertTrue(report.formatDetailed().contains(reset.trim()))
    }

    @Test fun oldBuildMarkerIsUpgradedBeforeExplicitFirmwareReset() {
        val store = Store().apply { pending = firmware }
        val api = rfApi(store)
        change(api, store, null)
        assertEquals(2, api.calls.size)
        assertTrue(api.calls.first()!!.startsWith("NXP_RF_CONF_BLK_13="))
        assertNull(api.calls.last())
        assertNull(store.pending)
    }

    @Test fun unavailableOldBuildDefaultsOrFailedUpgradeNeverDeletesTheOverride() {
        val store = Store().apply { pending = firmware }
        val api = rfApi(store).apply { failPrepare = true }
        assertThrows(IllegalStateException::class.java) { change(api, store, null) }
        api.failPrepare = false
        store.failSave = true
        assertThrows(IllegalStateException::class.java) { change(api, store, null) }
        assertTrue(api.calls.isEmpty())
        assertEquals(firmware, store.pending)
    }

    @Test fun rejectedDefaultsKeepTheOverrideAndRecoveryForRetry() {
        val store = Store()
        val api = rfApi(store)
        change(api, store, uid)
        val reset = store.reset
        api.defaultsStatus = 255
        val report = report()
        assertThrows(IllegalStateException::class.java) { change(api, store, null, report) }
        assertEquals(reset, api.calls.last())
        assertFalse(api.calls.contains(null))
        assertEquals(firmware, store.pending)
        assertEquals(255, report.transitCalls.single().status)
        api.defaultsStatus = 0
        change(api, store, null)
        assertNull(store.pending)
    }

    @Test fun failedOverrideRemovalRetainsBothCallResultsAndCanReplayDefaults() {
        val store = Store()
        val api = rfApi(store)
        change(api, store, uid)
        val reset = store.reset
        api.resetStatus = 255
        val report = report()
        assertThrows(IllegalStateException::class.java) { change(api, store, null, report) }
        assertEquals(listOf(0, 255), report.transitCalls.map { it.status })
        assertEquals(reset, store.reset)
        api.resetStatus = 0
        change(api, store, null)
        assertEquals(listOf(reset, null, reset, null), api.calls.drop(1))
        assertNull(store.pending)
    }

    @Test fun rejectedRfActivationRollsBackThroughDefaultsBeforeRemovingOverride() {
        val store = Store()
        val api = rfApi(store).apply { activateStatus = 255 }
        val report = report()
        assertThrows(IllegalStateException::class.java) { change(api, store, uid, report) }
        assertEquals(3, api.calls.size)
        assertEquals(255, report.vendorStatus)
        assertTrue(report.rollbackSucceeded)
        assertEquals(2, report.vendorRollback.transitCalls.size)
        assertNull(store.pending)
    }

    @Test fun repeatedUidSwitchesRetainTheSameRecoveryAndRefuseChangedRfLayout() {
        val store = Store()
        val api = rfApi(store)
        change(api, store, uid)
        val reset = store.reset
        change(api, store, byteArrayOf(0x2d, 0xd3.toByte(), 0x45, 0x4d))
        assertEquals(reset, store.reset)
        assertEquals(2, api.calls.size)
        api.rfBaseline += "NXP_RF_CONF_BLK_13={20,02,05,01,A0,00,01,00}\n"
        assertThrows(IllegalStateException::class.java) { change(api, store, uid) }
        assertEquals(2, api.calls.size)
        assertEquals(reset, store.reset)
    }

    @Test fun malformedPersistedResetPreventsEveryWrite() {
        val store = Store().apply { pending = firmware; reset = "NXP_RF_CONF_BLK_13={2F,00}"; legacy = false }
        val api = rfApi(store)
        assertThrows(IllegalArgumentException::class.java) { change(api, store, null) }
        assertTrue(api.calls.isEmpty())
        assertEquals(firmware, store.pending)
    }

    @Test fun timedOutBinderCallBlocksOverlappingResetUntilItReallyCompletes() {
        val gate = XiaomiNfcController.VendorCallGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            assertThrows(IllegalStateException::class.java) {
                gate.call({
                    entered.countDown()
                    release.await()
                    finished.countDown()
                    0
                }, 100)
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val failure = assertThrows(IllegalStateException::class.java) {
                gate.call({ fail("Must not overlap the timed-out write"); 0 }, 100)
            }
            assertTrue(failure.message!!.contains("still running"))
            release.countDown()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            // FutureTask publishes completion after the callable returns.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            var result: Int? = null
            while (result == null && System.nanoTime() < deadline) {
                try { result = gate.call({ 7 }, 1_000) }
                catch (error: IllegalStateException) {
                    if (!error.message!!.contains("still running")) throw error
                    Thread.yield()
                }
            }
            assertEquals(7, result)
        } finally {
            release.countDown()
        }
    }

    private class Ledger : XiaomiNfcController.WriteLedger {
        var unfinished = -1
        var failBegin = false
        var failFinish = false
        override fun unfinishedBoot() = unfinished
        override fun begin(boot: Int) { check(!failBegin); unfinished = boot }
        override fun finish() { check(!failFinish); unfinished = -1 }
    }

    @Test fun anInterruptedCallSurvivesProcessDeathAndBlocksWritesUntilReboot() {
        val ledger = Ledger().apply { unfinished = 10 }
        assertThrows(IllegalStateException::class.java) {
            XiaomiNfcController.trackedWrite(ledger, 10) { fail("Old Binder call may still be writing"); 0 }
        }
        assertEquals(10, ledger.unfinished)
        assertEquals(0, XiaomiNfcController.trackedWrite(ledger, 11) {
            assertEquals(11, ledger.unfinished)
            0
        })
        assertEquals(-1, ledger.unfinished)
    }

    @Test fun missingBootCounterOrFailedCallJournalPreventsTheWrite() {
        val ledger = Ledger()
        assertThrows(IllegalStateException::class.java) {
            XiaomiNfcController.trackedWrite(ledger, -1) { fail("Missing boot identity"); 0 }
        }
        ledger.failBegin = true
        assertThrows(IllegalStateException::class.java) {
            XiaomiNfcController.trackedWrite(ledger, 10) { fail("Missing durable call state"); 0 }
        }
    }

    @Test fun failedCompletionJournalRetainsTheInterruptedCallMarker() {
        val ledger = Ledger().apply { failFinish = true }
        assertThrows(IllegalStateException::class.java) {
            XiaomiNfcController.trackedWrite(ledger, 10) { 0 }
        }
        assertEquals(10, ledger.unfinished)
    }
}
