package dev.dunno.emula;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small, pure helpers shared by raw NFC execution, diagnostics, and unit tests. */
final class RawNciCodec {
    private RawNciCodec() {}

    static byte[] uidOnlyPayload(byte[] uid) {
        if (uid == null || !UidCodec.isSupportedLength(uid.length)) {
            throw new IllegalArgumentException("UID must contain 4, 7, or 10 bytes");
        }
        return nfcid1Payload(uid);
    }

    static byte[] legacyPayload(byte[] uid, byte bitFrameSdd, byte platformConfig,
            byte selInfo, byte rats) {
        // LA_BIT_FRAME_SDD is the profile's bit frame; the controller derives UID size
        // and cascade levels from LA_NFCID1. Keep existing profile values for every size.
        if (uid == null || !UidCodec.isSupportedLength(uid.length)) {
            throw new IllegalArgumentException("UID must contain 4, 7, or 10 bytes");
        }
        Map<Integer, byte[]> values = new LinkedHashMap<>();
        values.put(0x85, new byte[] {0});
        values.put(0x30, new byte[] {bitFrameSdd});
        values.put(0x31, new byte[] {platformConfig});
        values.put(0x32, new byte[] {selInfo});
        values.put(0x33, uid);
        values.put(0x58, new byte[] {rats});
        return configPayload(values, 0x85, 0x30, 0x31, 0x32, 0x33, 0x58);
    }

    static byte[] nfcid1Payload(byte[] value) {
        if (value == null || value.length > 0xFF) {
            throw new IllegalArgumentException("Invalid LA_NFCID1 value");
        }
        byte[] payload = new byte[value.length + 3];
        payload[0] = 1;
        payload[1] = 0x33;
        payload[2] = (byte) value.length;
        System.arraycopy(value, 0, payload, 3, value.length);
        return payload;
    }

    static byte[] configPayload(Map<Integer, byte[]> values, int... parameterIds) {
        if (values == null || parameterIds == null || parameterIds.length > 0xFF) {
            throw new IllegalArgumentException("Invalid NFC configuration");
        }
        int length = 1;
        for (int id : parameterIds) {
            byte[] value = values.get(id);
            if (id < 0 || id > 0xFF || value == null || value.length > 0xFF) {
                throw new IllegalArgumentException(String.format(Locale.US,
                        "Missing or invalid parameter 0x%02X", id));
            }
            length += value.length + 2;
        }
        byte[] payload = new byte[length];
        payload[0] = (byte) parameterIds.length;
        int offset = 1;
        for (int id : parameterIds) {
            byte[] value = values.get(id);
            payload[offset++] = (byte) id;
            payload[offset++] = (byte) value.length;
            System.arraycopy(value, 0, payload, offset, value.length);
            offset += value.length;
        }
        return payload;
    }

    static String encodeConfiguration(Map<Integer, byte[]> values) {
        if (values == null) throw new IllegalArgumentException("Configuration is missing");
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<Integer, byte[]> entry : values.entrySet()) {
            int id = entry.getKey();
            byte[] value = entry.getValue();
            if (id < 0 || id > 0xFF || value == null) {
                throw new IllegalArgumentException("Invalid NFC configuration entry");
            }
            if (encoded.length() > 0) encoded.append(';');
            encoded.append(String.format(Locale.US, "%02X=", id));
            for (byte item : value) {
                encoded.append(String.format(Locale.US, "%02X", item & 0xFF));
            }
        }
        return encoded.toString();
    }

    static Map<Integer, byte[]> decodeConfiguration(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("Configuration snapshot is missing");
        }
        Map<Integer, byte[]> values = new LinkedHashMap<>();
        for (String entry : encoded.split(";")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator != entry.lastIndexOf('=')) {
                throw new IllegalArgumentException("Malformed configuration snapshot");
            }
            try {
                int id = Integer.parseInt(entry.substring(0, separator), 16);
                String hex = entry.substring(separator + 1);
                if (id < 0 || id > 0xFF || (hex.length() & 1) != 0
                        || values.containsKey(id)) {
                    throw new IllegalArgumentException("Malformed configuration snapshot");
                }
                byte[] value = new byte[hex.length() / 2];
                for (int i = 0; i < value.length; i++) {
                    value[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                }
                values.put(id, value);
            } catch (NumberFormatException malformed) {
                throw new IllegalArgumentException("Malformed configuration snapshot", malformed);
            }
        }
        return values;
    }

    static String frameworkStatusName(int status) {
        switch (status) {
            case 0: return "SUCCESS";
            case 1: return "REJECTED";
            case 2: return "MESSAGE_CORRUPTED";
            case 3: return "FAILED";
            default: return "UNKNOWN_FRAMEWORK_STATUS_" + status;
        }
    }

    static String nciStatusName(int status) {
        switch (status) {
            case 0x00: return "OK";
            case 0x01: return "REJECTED";
            case 0x03: return "FAILED";
            case 0x04: return "NOT_INITIALIZED";
            case 0x05: return "SYNTAX_ERROR";
            case 0x06: return "SEMANTIC_ERROR";
            case 0x09: return "INVALID_PARAM";
            case 0x0A: return "MESSAGE_SIZE_EXCEEDED";
            default:
                return String.format(Locale.US, "UNKNOWN_NCI_STATUS_0x%02X", status);
        }
    }

    static String parameterName(int id) {
        switch (id) {
            case 0x30: return "LA_BIT_FRAME_SDD";
            case 0x31: return "LA_PLATFORM_CONFIG";
            case 0x32: return "LA_SEL_INFO";
            case 0x33: return "LA_NFCID1";
            case 0x58: return "LI_A_RATS_TB1";
            case 0x59: return "LI_A_HIST_BY";
            case 0x85: return "NFCC_CONFIG_CONTROL";
            default: return "UNKNOWN_PARAMETER";
        }
    }

    static List<Integer> parseRejectedParameterIds(byte[] response) {
        List<Integer> result = new ArrayList<>();
        if (response == null || response.length < 2) return result;
        int count = response[1] & 0xFF;
        if (response.length < count + 2) return result;
        for (int i = 0; i < count; i++) {
            result.add(response[i + 2] & 0xFF);
        }
        return result;
    }

    static Map<Integer, byte[]> parseGetConfigResponse(byte[] response) {
        if (response == null || response.length == 0) {
            throw new IllegalArgumentException("empty response");
        }
        int status = response[0] & 0xFF;
        if (status != 0) {
            throw new IllegalArgumentException(String.format(Locale.US,
                    "%s (0x%02X)", nciStatusName(status), status));
        }
        if (response.length < 2) {
            throw new IllegalArgumentException("truncated response");
        }

        int count = response[1] & 0xFF;
        int offset = 2;
        Map<Integer, byte[]> values = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            if (offset + 2 > response.length) {
                throw new IllegalArgumentException("truncated TLV header");
            }
            int id = response[offset++] & 0xFF;
            int length = response[offset++] & 0xFF;
            if (offset + length > response.length) {
                throw new IllegalArgumentException(String.format(Locale.US,
                        "truncated value for parameter 0x%02X", id));
            }
            values.put(id, Arrays.copyOfRange(response, offset, offset + length));
            offset += length;
        }
        return values;
    }

    static String hex(byte[] bytes) {
        if (bytes == null) return "no response";
        if (bytes.length == 0) return "empty";
        StringBuilder result = new StringBuilder(bytes.length * 3 - 1);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) result.append(' ');
            result.append(String.format(Locale.US, "%02X", bytes[i] & 0xFF));
        }
        return result.toString();
    }
}
