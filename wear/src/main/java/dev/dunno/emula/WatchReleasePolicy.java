package dev.dunno.emula;

/** Scope backed by the SM-L355F OEM / four-byte UID reader confirmation. */
final class WatchReleasePolicy {
    private WatchReleasePolicy() {}

    static boolean supportsDevice(String manufacturer, String model) {
        return "samsung".equalsIgnoreCase(manufacturer)
                && "SM-L355F".equalsIgnoreCase(model);
    }

    static byte[] parseUid(String input) {
        int length = input.length();
        if ((length != 8 && length != 14 && length != 20) || !input.matches("[0-9A-Fa-f]+")) return null;
        String[] bytes = new String[length / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = input.substring(index * 2, index * 2 + 2);
        }
        return UidCodec.parse(bytes);
    }
}
