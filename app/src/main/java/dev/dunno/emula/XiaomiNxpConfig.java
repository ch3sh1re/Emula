package dev.dunno.emula;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded parser for the firmware's standard NXP_CORE_CONF command. */
final class XiaomiNxpConfig {
    static final int MAX_CONFIG_BYTES = 65_536;
    // Exact 206 vendor artifact; used only when that exact firmware file cannot be read.
    static final String CORE_CONF_206 = "NXP_CORE_CONF={20,02,33,11,"
            + "28,01,00,21,01,00,30,01,04,31,01,00,32,01,60,38,01,01,33,00,"
            + "54,01,06,50,01,02,5B,01,00,3E,01,00,80,01,01,81,01,01,"
            + "82,01,0E,18,01,01,68,01,01,85,01,01}";
    // Android's ICU regex engine requires the literal closing brace to be escaped.
    private static final Pattern CORE = Pattern.compile("(?m)^\\s*NXP_CORE_CONF\\s*=\\s*\\{([^}]*)\\}");
    private static final Pattern ALWAYS = Pattern.compile("(?m)^\\s*NXP_SET_CONFIG_ALWAYS\\s*=\\s*(\\S+)\\s*$");
    private static final Pattern RF_KEY = Pattern.compile("(?m)^\\s*(NXP_RF_CONF_BLK_[^\\s=]+)\\s*=");
    private static final Pattern RF_RESET = Pattern.compile("\\s*NXP_RF_CONF_BLK_([1-9]|[12][0-9]|30)\\s*=\\s*\\{([^}]*)\\}\\s*");
    private static final Pattern CORE_RESET = Pattern.compile("\\s*NXP_CORE_CONF\\s*=\\s*\\{([^}]*)\\}\\s*");
    private static final int[] SNAPSHOT_IDS = {0x30, 0x31, 0x32, 0x33, 0x58, 0x59, 0x85};
    // Both inspected Xiaomi HALs iterate blocks 1..30. Never replace calibration blocks.
    private static final int MAX_RF_BLOCK = 30;

    static final class Plan {
        final String activation;
        final String reset;
        final String carrier;

        Plan(String activation, String reset, String carrier) {
            this.activation = activation;
            this.reset = reset;
            this.carrier = carrier;
        }
    }

    private XiaomiNxpConfig() {}

    static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("NFC configuration read interrupted");
            int count = input.read(buffer, 0, Math.min(buffer.length, MAX_CONFIG_BYTES + 1 - output.size()));
            if (count < 0) break;
            output.write(buffer, 0, count);
            if (output.size() > MAX_CONFIG_BYTES) throw new IOException("Firmware NFC configuration is too large");
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    static Map<Integer, byte[]> parse(String text) {
        return parse(text, true);
    }

    private static Map<Integer, byte[]> parse(String text, boolean requireRf) {
        if (text == null || text.length() > MAX_CONFIG_BYTES) throw invalid("missing or oversized file");
        String uncommented = text.replaceAll("(?m)#.*$", "");
        Matcher match = CORE.matcher(uncommented);
        if (!match.find()) throw invalid("NXP_CORE_CONF missing");
        String body = match.group(1).trim();
        if (match.find()) throw invalid("duplicate NXP_CORE_CONF");
        String[] parts = body.split("[,\\s]+");
        if (parts.length < 4 || parts.length > 258) throw invalid("command length");
        int[] bytes = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("[0-9a-fA-F]{2}")) throw invalid("non-hexadecimal command byte");
            bytes[i] = Integer.parseInt(parts[i], 16);
        }
        if (bytes[0] != 0x20 || bytes[1] != 0x02 || bytes[2] != bytes.length - 3) {
            throw invalid("not a complete CORE_SET_CONFIG command");
        }
        Map<Integer, byte[]> values = new LinkedHashMap<>();
        int offset = 4;
        for (int parameter = 0; parameter < bytes[3]; parameter++) {
            if (offset + 2 > bytes.length) throw invalid("truncated parameter");
            int id = bytes[offset++];
            int count = bytes[offset++];
            if (id >= 0xA0 || values.containsKey(id) || offset + count > bytes.length) {
                throw invalid("proprietary, repeated or truncated parameter");
            }
            byte[] value = new byte[count];
            for (int i = 0; i < count; i++) value[i] = (byte) bytes[offset++];
            values.put(id, value);
        }
        if (offset != bytes.length) throw invalid("trailing command bytes");
        for (int id : new int[] {0x30, 0x31, 0x32, 0x85}) {
            if ((requireRf && !values.containsKey(id))
                    || (values.containsKey(id) && values.get(id).length != 1)) {
                throw invalid("required RF parameter missing or malformed");
            }
        }
        byte[] uid = values.get(0x33);
        if (uid == null || (uid.length != 0 && !UidCodec.isSupportedLength(uid.length))) {
            throw invalid("NFCID1 missing or malformed");
        }
        return values;
    }

    static String configuration(String baseline, byte[] uid) {
        if (uid == null || !UidCodec.isSupportedLength(uid.length)) {
            throw new IllegalArgumentException("UID must contain 4, 7, or 10 bytes");
        }
        Map<Integer, byte[]> original = parse(baseline);
        return configuration(original, uid);
    }

    private static String configuration(Map<Integer, byte[]> original, byte[] uid) {
        if (uid == null || !UidCodec.isSupportedLength(uid.length)) {
            throw new IllegalArgumentException("UID must contain 4, 7, or 10 bytes");
        }
        Map<Integer, byte[]> values = new LinkedHashMap<>();
        values.put(0x85, new byte[] {0}); // Disable automatic RF updates before explicit NFCID1.
        original.forEach((id, value) -> {
            if (id != 0x85 && id != 0x33) values.put(id, value);
        });
        values.put(0x33, uid.clone());
        return command("NXP_CORE_CONF", values);
    }

    /** Tanzanite's sparse CORE_CONF is completed only from this device's GET snapshot. */
    static Plan snapshotPlan(String baseline, byte[] uid, Map<Integer, byte[]> liveSnapshot,
            String savedReset) {
        if (!setConfigAlways(baseline)) throw invalid("tanzanite requires always-applied CORE_CONF");
        Map<Integer, byte[]> defaults = parse(baseline, false);
        Map<Integer, byte[]> original = savedReset == null
                ? snapshotValues(liveSnapshot) : snapshotFromReset(savedReset);
        if (!Arrays.equals(new byte[] {1}, original.get(0x85))) {
            throw invalid("controller already has custom RF configuration");
        }
        for (int id : SNAPSHOT_IDS) {
            byte[] value = original.get(id);
            // Do not adopt a pre-existing UID/config override belonging to another app.
            if (defaults.containsKey(id) && !Arrays.equals(defaults.get(id), value)) {
                throw invalid("live snapshot differs from firmware defaults at 0x"
                        + Integer.toHexString(id));
            }
            defaults.put(id, value.clone());
        }
        String reset = command("NXP_CORE_CONF", defaults);
        if (savedReset != null && !savedReset.equals(reset)) {
            throw invalid("firmware configuration changed during the saved session");
        }
        return new Plan(uid == null ? null : configuration(defaults, uid), reset,
                "NXP_CORE_CONF (live RF snapshot; readback required)");
    }

    static Map<Integer, byte[]> snapshotFromReset(String reset) {
        if (reset == null || reset.length() > MAX_CONFIG_BYTES || !CORE_RESET.matcher(reset).matches()) {
            throw invalid("invalid saved CORE reset");
        }
        Map<Integer, byte[]> values = snapshotValues(parse(reset));
        if (!Arrays.equals(new byte[] {1}, values.get(0x85))) throw invalid("saved RF control is not system-managed");
        return values;
    }

    static Map<Integer, byte[]> snapshotFromConfiguration(String config) {
        return snapshotValues(parse(config));
    }

    static boolean matchesSnapshot(String config, Map<Integer, byte[]> actual) {
        Map<Integer, byte[]> expected = snapshotFromConfiguration(config);
        Map<Integer, byte[]> returned = snapshotValues(actual);
        for (int id : SNAPSHOT_IDS) {
            if (!Arrays.equals(expected.get(id), returned.get(id))) return false;
        }
        return true;
    }

    private static Map<Integer, byte[]> snapshotValues(Map<Integer, byte[]> input) {
        Map<Integer, byte[]> result = new LinkedHashMap<>();
        for (int id : SNAPSHOT_IDS) {
            byte[] value = input == null ? null : input.get(id);
            if (value == null || (id != 0x33 && id != 0x59 && value.length != 1)
                    || (id == 0x33 && value.length != 0 && !UidCodec.isSupportedLength(value.length))
                    || value.length > 255) {
                throw invalid("incomplete or malformed live RF snapshot");
            }
            result.put(id, value.clone());
        }
        return result;
    }

    static boolean setConfigAlways(String baseline) {
        Matcher match = ALWAYS.matcher(uncomment(baseline));
        if (!match.find()) throw invalid("NXP_SET_CONFIG_ALWAYS missing");
        String value = match.group(1);
        if (match.find()) throw invalid("duplicate NXP_SET_CONFIG_ALWAYS");
        if (value.matches("(?:0[xX]0*)?1")) return true;
        if (value.matches("(?:0[xX])?0+")) return false;
        throw invalid("NXP_SET_CONFIG_ALWAYS must be 0 or 1");
    }

    /** A transit CRC change triggers RF blocks even when unchanged CORE_CONF is skipped. */
    static Plan plan(String baseline, String rfBaseline, byte[] uid) {
        Map<Integer, byte[]> defaults = parse(baseline);
        String activation = uid == null ? null : configuration(baseline, uid);
        if (setConfigAlways(baseline)) return new Plan(activation, null, "NXP_CORE_CONF (always applied)");
        Set<Integer> occupied = rfBlocks(baseline);
        Set<Integer> rf = rfBlocks(rfBaseline);
        if (rf.isEmpty()) throw invalid("firmware RF blocks missing");
        occupied.addAll(rf);
        int last = occupied.stream().mapToInt(Integer::intValue).max().orElse(0);
        if (last >= MAX_RF_BLOCK) throw invalid("no unused trailing RF block");
        String key = "NXP_RF_CONF_BLK_" + (last + 1);
        return new Plan(activation == null ? null : activation.replace("NXP_CORE_CONF=", key + "="),
                command(key, defaults), key + " (transit update)");
    }

    /** Validate persisted firmware defaults before a recovery write, including after process death. */
    static void validateReset(String reset) {
        if (reset == null || reset.length() > MAX_CONFIG_BYTES) throw invalid("missing reset configuration");
        Matcher match = RF_RESET.matcher(reset);
        if (!match.matches()) throw invalid("invalid saved RF reset block");
        parse("NXP_CORE_CONF={" + match.group(2) + "}");
    }

    private static Set<Integer> rfBlocks(String text) {
        Matcher match = RF_KEY.matcher(uncomment(text));
        Set<Integer> result = new HashSet<>();
        while (match.find()) {
            String suffix = match.group(1).substring("NXP_RF_CONF_BLK_".length());
            if (!suffix.matches("[1-9]|[12][0-9]|30")) throw invalid("unsupported RF block number");
            if (!result.add(Integer.parseInt(suffix))) throw invalid("duplicate RF block");
        }
        return result;
    }

    private static String uncomment(String text) {
        if (text == null || text.length() > MAX_CONFIG_BYTES) throw invalid("missing or oversized file");
        return text.replaceAll("(?m)#.*$", "");
    }

    private static String command(String key, Map<Integer, byte[]> values) {
        int[] ids = values.keySet().stream().mapToInt(Integer::intValue).toArray();
        byte[] payload = RawNciCodec.configPayload(values, ids);
        if (payload.length > 255) throw invalid("configured command exceeds NCI length");
        byte[] command = new byte[payload.length + 3];
        command[0] = 0x20;
        command[1] = 0x02;
        command[2] = (byte) payload.length;
        System.arraycopy(payload, 0, command, 3, payload.length);
        return key + "={" + RawNciCodec.hex(command).replace(" ", ",") + "}\n";
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("Unsupported firmware NFC configuration: " + detail);
    }
}
