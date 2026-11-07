package dev.dunno.emula

import org.junit.Assert.*
import org.junit.Test

class TanzaniteNxpTrialTest {
    private val firmware = "Redmi/tanzanite_eea/tanzanite:16/test"
    private fun baseline() = javaClass.getResource("/tanzanite-libnfc-nxp.conf")!!.readText()
    private fun snapshot() = linkedMapOf(
        0x30 to byteArrayOf(4), 0x31 to byteArrayOf(0), 0x32 to byteArrayOf(0x20),
        0x33 to byteArrayOf(1, 2, 3, 4), 0x58 to byteArrayOf(0x41),
        0x59 to byteArrayOf(), 0x85 to byteArrayOf(1),
    )
    private fun report() = OemOperationReport(OemOperationReport.Operation.ACTIVATE)
    private class Store : XiaomiNfcController.Store {
        var recovery: XiaomiNfcController.Recovery? = null
        var failSave = false
        override fun load() = recovery
        override fun save(value: XiaomiNfcController.Recovery) {
            check(!failSave) { "storage unavailable" }
            recovery = value
        }
        override fun clear() { recovery = null }
    }
    private inner class Api(val store: Store) : XiaomiNfcController.ConfigApi {
        var values = snapshot().toMutableMap()
        val calls = mutableListOf<String?>()
        var ignoreActivation = false
        var omitActivationHistoricalBytes = false
        var failRestorationReadback = false
        var failPrepare = false
        override fun usesLiveSnapshot() = true
        override fun prepare(uid: ByteArray?) = prepare(uid, null)
        override fun prepare(uid: ByteArray?, savedReset: String?): XiaomiNxpConfig.Plan {
            check(!failPrepare) { "configuration file is unavailable" }
            return XiaomiNxpConfig.snapshotPlan(baseline(), uid,
                if (savedReset == null) values else null, savedReset)
        }
        override fun setConfig(value: String?): Int {
            assertNotNull("Snapshot must be durable before every setter", store.recovery)
            calls += value
            val restoring = value == store.recovery!!.reset
            if (value == null) {
                values = snapshot().toMutableMap()
                if (failRestorationReadback) values[0x33] = byteArrayOf(9, 9, 9, 9)
            } else if (!ignoreActivation || restoring) {
                values = XiaomiNxpConfig.snapshotFromConfiguration(value).toMutableMap()
                if (!restoring && omitActivationHistoricalBytes) values.remove(0x59)
            }
            return 0
        }
        override fun nfcReady() = true
        override fun verifyConfiguration(config: String, report: OemOperationReport) {
            if (!XiaomiNxpConfig.matchesSnapshot(config, values)) {
                error("readback mismatch")
            }
        }
    }

    @Test fun exactReportedEeaModelIsEligibleOnAndroid15AndNewer() {
        for (sdk in listOf(35, 36, 37, 38, 100)) {
            assertTrue(XiaomiNfcController.isTargetDevice(sdk, "Xiaomi", "24117RN76E", "tanzanite", "tanzanite_eea"))
        }
        for (tuple in listOf(
            listOf("Xiaomi", "24117RN76G", "tanzanite", "tanzanite_eea"),
            listOf("Xiaomi", "24117RN76E", "tanzanite", "tanzanite_global"),
            listOf("Xiaomi", "24117RN76E", "garnet", "tanzanite_eea"),
            listOf("HONOR", "24117RN76E", "tanzanite", "tanzanite_eea"),
        )) assertFalse(XiaomiNfcController.isTargetDevice(36, tuple[0], tuple[1], tuple[2], tuple[3]))
        assertFalse(XiaomiNfcController.isTargetDevice(34, "Xiaomi", "24117RN76E", "tanzanite", "tanzanite_eea"))
        assertFalse(RawNfcDeviceCatalog.isSupported("Xiaomi", "24117RN76E"))
    }

    @Test fun sparseConfigurationNeedsSnapshotAndKeepsItsOwnDefaultsForAllUidLengths() {
        assertThrows(IllegalArgumentException::class.java) { XiaomiNxpConfig.parse(baseline()) }
        val original = snapshot().apply { put(0x30, byteArrayOf(8)); put(0x31, byteArrayOf(3)) }
        for (size in listOf(4, 7, 10)) {
            val uid = ByteArray(size) { (it + 16).toByte() }
            val plan = XiaomiNxpConfig.snapshotPlan(baseline(), uid, original, null)
            val configured = XiaomiNxpConfig.parse(plan.activation)
            original.forEach { (id, value) ->
                assertArrayEquals(value, XiaomiNxpConfig.snapshotFromReset(plan.reset)[id])
                if (id != 0x33 && id != 0x85) assertArrayEquals(value, configured[id])
            }
            assertArrayEquals(uid, configured[0x33])
            assertArrayEquals(byteArrayOf(0), configured[0x85])
            assertArrayEquals(byteArrayOf(6), configured[0x54])
            assertArrayEquals(byteArrayOf(0x0E), configured[0x82])
            assertEquals(17, configured.size)
        }
    }

    @Test fun everySnapshotParameterIsRequiredIncludingEmptyHistoricalBytes() {
        for (id in snapshot().keys) {
            assertThrows(IllegalArgumentException::class.java) {
                XiaomiNxpConfig.snapshotPlan(baseline(), byteArrayOf(4, 3, 2, 1), snapshot().filterKeys { it != id }, null)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            XiaomiNxpConfig.snapshotPlan(baseline(), byteArrayOf(4, 3, 2, 1), snapshot() + (0x30 to byteArrayOf(1, 2)), null)
        }
    }

    @Test fun customRfOrUidAndUnsupportedApplicationPolicyAreRejectedBeforeWriting() {
        for (values in listOf(snapshot() + (0x85 to byteArrayOf(0)), snapshot() + (0x33 to byteArrayOf(9, 9, 9, 9)))) {
            assertThrows(IllegalArgumentException::class.java) {
                XiaomiNxpConfig.snapshotPlan(baseline(), byteArrayOf(4, 3, 2, 1), values, null)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            XiaomiNxpConfig.snapshotPlan(baseline().replace("NXP_SET_CONFIG_ALWAYS=0x01", "NXP_SET_CONFIG_ALWAYS=0x00"),
                byteArrayOf(4, 3, 2, 1), snapshot(), null)
        }
    }

    @Test fun savedSnapshotCannotInjectOtherConfigKeysOrHideFirmwareChanges() {
        val reset = XiaomiNxpConfig.snapshotPlan(baseline(), byteArrayOf(4, 3, 2, 1), snapshot(), null).reset
        for (bad in listOf(reset + "NXP_OTHER=1", reset + reset,
            reset.replace("28,01,00", "A0,01,00"), reset.replace("85,01,01", "85,01,00"))) {
            assertThrows(IllegalArgumentException::class.java) { XiaomiNxpConfig.snapshotFromReset(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { XiaomiNxpConfig.validateReset(reset) }
        assertThrows(IllegalArgumentException::class.java) {
            XiaomiNxpConfig.snapshotPlan(baseline().replace("54, 01, 06", "54, 01, 07"), byteArrayOf(4, 3, 2, 1), null, reset)
        }
    }

    @Test fun activationSwitchAndRestartRecoveryRetainOriginalSnapshot() {
        val store = Store(); val api = Api(store)
        for (size in listOf(4, 7, 10)) {
            val uid = ByteArray(size) { (it + 16).toByte() }; val report = report()
            XiaomiNfcController.transact(api, store, firmware, uid, report)
            assertArrayEquals(uid, api.values[0x33])
            assertEquals("NCI_CONFIG_READBACK_VERIFIED_READER_UNVERIFIED", report.result)
            assertArrayEquals(snapshot()[0x33], XiaomiNxpConfig.snapshotFromReset(store.recovery!!.reset)[0x33])
        }
        val reset = store.recovery!!.reset
        val restarted = Api(store).apply { failPrepare = true }
        val restored = report()
        XiaomiNfcController.transact(restarted, store, firmware, null, restored)
        assertEquals(listOf(reset, null), restarted.calls)
        assertNull(store.recovery)
        assertEquals("NCI_RESTORE_READBACK_VERIFIED", restored.result)
    }

    @Test fun successfulVendorReturnCannotHideFailedOrIncompleteReadback() {
        for (missing in listOf(false, true)) {
            val store = Store(); val api = Api(store).apply {
                ignoreActivation = !missing; omitActivationHistoricalBytes = missing
            }
            val report = report()
            assertThrows(Exception::class.java) {
                XiaomiNfcController.transact(api, store, firmware, byteArrayOf(4, 3, 2, 1), report)
            }
            assertTrue(report.rollbackSucceeded)
            assertNull(store.recovery)
            assertFalse(XiaomiNfcController.permitsRawFallback(true, true, false, true, report))
        }
    }

    @Test fun failedRestoreReadbackRetainsRecoveryUntilVerifiedRetry() {
        val store = Store(); val api = Api(store)
        XiaomiNfcController.transact(api, store, firmware, byteArrayOf(4, 3, 2, 1), report())
        api.failRestorationReadback = true
        assertThrows(IllegalStateException::class.java) {
            XiaomiNfcController.transact(api, store, firmware, null, report())
        }
        assertNotNull(store.recovery)
        api.failRestorationReadback = false
        XiaomiNfcController.transact(api, store, firmware, null, report())
        assertNull(store.recovery)
    }

    @Test fun preWriteFailuresNeverCallSettersAndKeepGenericRawConsent() {
        for (failSave in listOf(false, true)) {
            val store = Store().apply { this.failSave = failSave }; val api = Api(store).apply { failPrepare = !failSave }
            assertThrows(IllegalStateException::class.java) {
                XiaomiNfcController.transact(api, store, firmware, byteArrayOf(4, 3, 2, 1), report())
            }
            assertTrue(api.calls.isEmpty())
        }
        val failed = report().apply { path = OemOperationReport.Path.XIAOMI_NXP_TRANSIT }
        assertTrue(XiaomiNfcController.permitsRawFallback(true, true, false, true, failed))
        assertFalse(XiaomiNfcController.permitsRawFallback(false, true, false, true, failed))
        assertFalse(XiaomiNfcController.permitsRawFallback(true, false, false, true, failed))
        assertFalse(XiaomiNfcController.permitsRawFallback(true, true, true, true, failed))
        assertFalse(XiaomiNfcController.permitsRawFallback(true, true, false, false, failed))
        assertEquals(NfcActivationPolicy.Path.CONFIRM_EXPERIMENTAL_RAW,
            NfcActivationPolicy.choose(true, false, true, false, false, false))
    }
}
