package dev.dunno.emula;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class WatchActivationPolicyTest {
    private static byte[] parameters(OemNfcController.EmulationProfile profile) {
        return new byte[] {profile.bitFrameSdd, profile.platformConfig, profile.selInfo, profile.rats};
    }

    @Test public void googleWatchesUseTheLunaParametersAcrossModelsAndGooglePhonesKeepMinimal() {
        OemNfcController.EmulationProfile watch = OemNfcController.resolveProfile("Google", true);
        assertEquals("pixel-watch-iso-dep", watch.name);
        assertArrayEquals(new byte[] {4, 0, 0x20, 0}, parameters(watch));
        assertSame(watch, OemNfcController.resolveProfile("google", true));
        OemNfcController.EmulationProfile phone = OemNfcController.resolveProfile("Google", false);
        assertEquals("pixel-minimal", phone.name);
        assertArrayEquals(new byte[] {4, 0, 0, 0}, parameters(phone));
    }

    @Test public void samsungAndOtherOemsKeepCtsType4a() {
        OemNfcController.EmulationProfile samsung = OemNfcController.resolveProfile("samsung", true);
        assertEquals("cts-type-4a", samsung.name);
        assertArrayEquals(new byte[] {6, 12, 0x20, 0x40}, parameters(samsung));
        for (String maker : new String[] {"Samsung", "Other", null}) {
            assertSame(samsung, OemNfcController.resolveProfile(maker, true));
            assertSame(samsung, OemNfcController.resolveProfile(maker, false));
        }
    }

    @Test public void liveControllerValuesAreComparedWithTheSelectedProfile() {
        Map<Integer, byte[]> pixel = RawNciCodec.decodeConfiguration(
                "30=04;31=00;32=20;33=11223344;58=00;85=00");
        Map<Integer, byte[]> samsung = RawNciCodec.decodeConfiguration(
                "30=06;31=0C;32=20;33=11223344;58=40;85=00");
        for (String maker : new String[] {"Google", "Samsung"}) {
            OemNfcController.EmulationProfile profile = OemNfcController.resolveProfile(maker, true);
            OemOperationReport report = new OemOperationReport(OemOperationReport.Operation.ACTIVATE);
            report.atqa = new byte[] {profile.bitFrameSdd, profile.platformConfig};
            report.sak = new byte[] {profile.selInfo};
            report.rats = new byte[] {profile.rats};
            report.uid = new byte[] {0x11, 0x22, 0x33, 0x44};
            Map<Integer, byte[]> expected = maker.equals("Google") ? pixel : samsung;
            Map<Integer, byte[]> other = maker.equals("Google") ? samsung : pixel;
            report.recordAndroidReadback(new LegacyNfcDiagnostics.ProbeResult("", "", "", expected));
            assertEquals("MATCH", report.androidReadbackStatus);
            assertEquals("MISMATCH", report.classifyAndroidReadback(other));
            assertEquals("INCOMPLETE", report.classifyAndroidReadback(java.util.Collections.singletonMap(0x33, report.uid)));
            assertEquals("MATCH", report.androidReadbackStatus);
        }
    }

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
