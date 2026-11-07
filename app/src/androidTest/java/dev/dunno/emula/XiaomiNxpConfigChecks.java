package dev.dunno.emula;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Run the real parser on Android's ICU regex engine; no NFC or vendor service calls. */
final class XiaomiNxpConfigChecks {
    static void initialize() {
        try {
            Class.forName("dev.dunno.emula.XiaomiNxpConfig", true,
                    XiaomiNxpConfigChecks.class.getClassLoader());
        } catch (ClassNotFoundException failure) {
            throw new AssertionError("Xiaomi config parser must be packaged", failure);
        }
    }

    static void parseAndConfigure() {
        String baseline = "# Android runtime fixture, not a device snapshot\n"
                + XiaomiNxpConfig.CORE_CONF_206.replace("30,01,04", "30,01,08")
                .replace("32,01,60", "32,01,20").replace(",", ", \n") + " # end\n";
        Map<Integer, byte[]> original = XiaomiNxpConfig.parse(baseline);
        for (int length : new int[]{4, 7, 10}) {
            byte[] uid = new byte[length];
            for (int i = 0; i < length; i++) uid[i] = (byte) (i * 17);
            Map<Integer, byte[]> configured = XiaomiNxpConfig.parse(
                    XiaomiNxpConfig.configuration(baseline, uid));
            check(Arrays.equals(uid, configured.get(0x33)), "Selected UID is preserved");
            check(Arrays.equals(new byte[]{0}, configured.get(0x85)), "Config control is explicit");
            for (Map.Entry<Integer, byte[]> entry : original.entrySet()) {
                if (entry.getKey() == 0x33 || entry.getKey() == 0x85) continue;
                check(Arrays.equals(entry.getValue(), configured.get(entry.getKey())),
                        "Firmware parameter must be preserved: " + entry.getKey());
            }
        }
        reject(baseline + baseline);
        reject("NXP_CORE_CONF={20,02");
        reject(XiaomiNxpConfig.CORE_CONF_206.replace("20,02,33,11", "20,02,32,11"));
        String conditional = "NXP_SET_CONFIG_ALWAYS=0x00\n" + baseline;
        String rf = "NXP_RF_CONF_BLK_12={20,02,05,01,A0,00,01,00}\n";
        XiaomiNxpConfig.Plan plan = XiaomiNxpConfig.plan(conditional, rf, new byte[]{1, 2, 3, 4});
        check(plan.activation.startsWith("NXP_RF_CONF_BLK_13="), "Transit update uses a trailing RF block");
        XiaomiNxpConfig.validateReset(plan.reset);
        Map<Integer, byte[]> defaults = XiaomiNxpConfig.parse(
                plan.reset.replace("NXP_RF_CONF_BLK_13", "NXP_CORE_CONF"));
        for (Map.Entry<Integer, byte[]> entry : original.entrySet()) {
            check(Arrays.equals(entry.getValue(), defaults.get(entry.getKey())), "Exact firmware default reset");
        }
    }

    static void recoveryStorage(Context context) {
        String name = "xiaomi-recovery-test-" + UUID.randomUUID();
        SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
        try {
            check(prefs.edit().putBoolean("pending", true).putInt("version", 1)
                    .putString("firmware", "test-firmware").commit(), "Legacy fixture committed");
            XiaomiNfcController.Store store = XiaomiNfcController.store(prefs);
            check(store.load().legacy, "Build 35 recovery remains readable");
            String reset = XiaomiNxpConfig.plan("NXP_SET_CONFIG_ALWAYS=0\n" + XiaomiNxpConfig.CORE_CONF_206,
                    "NXP_RF_CONF_BLK_12={20,02,05,01,A0,00,01,00}\n", null).reset;
            store.save(new XiaomiNfcController.Recovery("test-firmware", reset, false));
            XiaomiNfcController.Recovery reloaded = XiaomiNfcController.store(prefs).load();
            check(!reloaded.legacy && reset.equals(reloaded.reset), "Durable reset survives a new store");
            check(prefs.getInt("version", 0) == 2, "New recovery format persisted");
            check(prefs.edit().remove("reset_config").commit(), "Incomplete marker fixture committed");
            try { store.load(); throw new AssertionError("Missing reset must retain recovery and refuse writes"); }
            catch (IllegalStateException expected) { /* fail closed */ }
            check(prefs.getBoolean("pending", false), "Incomplete recovery stays pending");
            store.save(new XiaomiNfcController.Recovery("test-firmware", null, false));
            check(store.load().reset == null && !store.load().legacy, "CORE carrier stores explicit no-RF-reset mode");
            store.clear();
            check(store.load() == null && !prefs.contains("reset_config"), "Recovery cleared together");
        } finally {
            context.deleteSharedPreferences(name);
        }
    }

    static void tanzaniteSnapshot(Context context) {
        String baseline = "NXP_SET_CONFIG_ALWAYS=0x01\nNXP_CORE_CONF={20,02,25,0B,"
                + "28,01,00,21,01,00,33,04,01,02,03,04,54,01,06,5B,01,00,3E,01,00,"
                + "80,01,01,81,01,01,82,01,0E,18,01,01,68,01,01}\n";
        Map<Integer, byte[]> live = new LinkedHashMap<>();
        live.put(0x30, new byte[]{4}); live.put(0x31, new byte[]{0});
        live.put(0x32, new byte[]{0x20}); live.put(0x33, new byte[]{1, 2, 3, 4});
        live.put(0x58, new byte[]{0x41}); live.put(0x59, new byte[0]);
        live.put(0x85, new byte[]{1});
        String name = "tanzanite-recovery-test-" + UUID.randomUUID();
        SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
        try {
            for (int length : new int[]{4, 7, 10}) {
                byte[] uid = new byte[length];
                Arrays.fill(uid, (byte) 0x19);
                XiaomiNxpConfig.Plan plan = XiaomiNxpConfig.snapshotPlan(baseline, uid, live, null);
                check(Arrays.equals(uid, XiaomiNxpConfig.snapshotFromConfiguration(plan.activation).get(0x33)),
                        "Tanzanite selected UID survives ICU parsing");
                XiaomiNfcController.Store store = XiaomiNfcController.store(prefs, true);
                store.save(new XiaomiNfcController.Recovery("test-firmware", plan.reset, false));
                String reset = XiaomiNfcController.store(prefs, true).load().reset;
                for (Map.Entry<Integer, byte[]> entry : live.entrySet()) {
                    check(Arrays.equals(entry.getValue(), XiaomiNxpConfig.snapshotFromReset(reset).get(entry.getKey())),
                            "Tanzanite snapshot survives real storage: " + entry.getKey());
                }
                check(plan.reset.equals(XiaomiNxpConfig.snapshotPlan(baseline, uid, null, reset).reset),
                        "Switching cards preserves original snapshot");
            }
            check(prefs.edit().putString("reset_config", "").commit(), "Invalid snapshot fixture committed");
            try { XiaomiNfcController.store(prefs, true).load(); throw new AssertionError("Missing snapshot accepted"); }
            catch (IllegalArgumentException expected) { /* fail closed */ }
            check(prefs.getBoolean("pending", false), "Invalid snapshot retains recovery marker");
        } finally {
            context.deleteSharedPreferences(name);
        }
    }

    private static void reject(String input) {
        try { XiaomiNxpConfig.parse(input); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Malformed/duplicate configuration must be rejected");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
