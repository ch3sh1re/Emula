package dev.dunno.emula;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private static final Pattern CORE = Pattern.compile("(?m)^\\s*NXP_CORE_CONF\\s*=\\s*\\{([^}]*)}");

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
            if (!values.containsKey(id) || values.get(id).length != 1) throw invalid("required RF parameter missing");
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
        Map<Integer, byte[]> values = new LinkedHashMap<>();
        values.put(0x85, new byte[] {0}); // Disable automatic RF updates before explicit NFCID1.
        original.forEach((id, value) -> {
            if (id != 0x85 && id != 0x33) values.put(id, value);
        });
        values.put(0x33, uid.clone());
        int[] ids = values.keySet().stream().mapToInt(Integer::intValue).toArray();
        byte[] payload = RawNciCodec.configPayload(values, ids);
        if (payload.length > 255) throw invalid("configured command exceeds NCI length");
        byte[] command = new byte[payload.length + 3];
        command[0] = 0x20;
        command[1] = 0x02;
        command[2] = (byte) payload.length;
        System.arraycopy(payload, 0, command, 3, payload.length);
        return "NXP_CORE_CONF={" + RawNciCodec.hex(command).replace(" ", ",") + "}\n";
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("Unsupported firmware NFC configuration: " + detail);
    }
}
