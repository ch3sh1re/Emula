package dev.dunno.emula;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class WatchActivationPolicyTest {
    @Test public void oemRemainsPrimaryWithoutRawApiOrConsent() {
        assertEquals(WatchNfcSession.Path.OEM, WatchActivationPolicy.select(35, true, true, false, false));
        assertEquals(WatchNfcSession.Path.OEM, WatchActivationPolicy.select(36, true, true, true, true));
    }

    @Test public void unknownWatchesCanUseRawFromAndroid15WithoutAnOemApi() {
        for (int sdk : new int[] {35, 36, 37, 38}) {
            assertEquals(WatchNfcSession.Path.RAW, WatchActivationPolicy.select(sdk, true, false, true, true));
        }
    }

    @Test public void missingTransportOrPollingControlBlocksRaw() {
        assertNull(WatchActivationPolicy.select(36, true, false, false, true));
        assertNull(WatchActivationPolicy.select(36, true, false, true, false));
    }

    @Test public void rawWatchFallbackNeverTargetsPhonesOrOlderWatches() {
        assertNull(WatchActivationPolicy.select(34, true, false, true, true));
        assertNull(WatchActivationPolicy.select(36, false, false, true, true));
    }

    @Test public void approvalAppliesOnlyToTheExactIdentifiableFirmware() {
        assertTrue(WatchActivationPolicy.hasConsent("vendor/watch:16/fw1", "vendor/watch:16/fw1"));
        assertFalse(WatchActivationPolicy.hasConsent("vendor/watch:16/fw2", "vendor/watch:16/fw1"));
        assertFalse(WatchActivationPolicy.hasConsent("vendor/watch:16/fw1", null));
        for (String unknown : new String[] {null, "", " ", "unknown"}) {
            assertFalse(WatchActivationPolicy.hasConsent(unknown, unknown));
        }
    }

    @Test public void nonDefaultSnapshotPreservesOriginalValues() {
        // Synthetic UID with non-default values: 0x30=04, 0x31=00 and 0x58=41
        // must survive the transaction rather than becoming the experimental defaults.
        Map<Integer, byte[]> original = RawNciCodec.decodeConfiguration(
                "30=04;31=00;32=20;33=01020304;58=41;59=;85=01");
        assertTrue(LegacyNfcController.isCompleteExperimentalSnapshot(original));
        Map<Integer, byte[]> saved = RawNciCodec.decodeConfiguration(RawNciCodec.encodeConfiguration(original));
        assertTrue(LegacyNfcController.configurationEquals(original, saved));
        assertArrayEquals(new byte[] {6, (byte) 0x85, 1, 1, 0x30, 1, 4, 0x31, 1, 0,
                0x32, 1, 0x20, 0x33, 4, 1, 2, 3, 4, 0x58, 1, 0x41},
                RawNciCodec.configPayload(saved, 0x85, 0x30, 0x31, 0x32, 0x33, 0x58));
        for (int id : new int[] {0x85, 0x30, 0x31, 0x32, 0x33, 0x58}) {
            Map<Integer, byte[]> incomplete = new java.util.LinkedHashMap<>(original);
            incomplete.remove(id);
            assertFalse("Missing parameter " + id, LegacyNfcController.isCompleteExperimentalSnapshot(incomplete));
        }
    }
}
