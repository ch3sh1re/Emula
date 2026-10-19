package dev.dunno.emula;

import java.util.Locale;

/** One local query shared by the catalog and tile chooser; never changes records. */
final class CardSearch {
    private final String name;
    private final String uid;

    CardSearch(String query) {
        name = query.trim().toLowerCase(Locale.ROOT);
        String candidate = name;
        if (candidate.startsWith("uid") && candidate.length() > 3
                && (Character.isWhitespace(candidate.charAt(3)) || candidate.charAt(3) == ':')) {
            candidate = candidate.substring(3);
        }
        StringBuilder hex = new StringBuilder();
        boolean valid = true;
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) hex.append(c);
            else if (!Character.isWhitespace(c) && !Character.isSpaceChar(c) && c != ':' && c != '-') {
                valid = false;
                break;
            }
        }
        uid = valid && hex.length() > 0 ? hex.toString() : null;
    }

    boolean matches(SavedCard card) {
        if (name.isEmpty() || card.name.toLowerCase(Locale.ROOT).contains(name)) return true;
        if (uid == null) return false;
        StringBuilder hex = new StringBuilder(card.uid.length * 2);
        for (byte value : card.uid) {
            hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return hex.indexOf(uid) >= 0;
    }
}
