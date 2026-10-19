package dev.dunno.emula;

/** Pure backspace state transition; the keyboard connection stays on the whole grid. */
final class UidByteDeletion {
    static final class Result {
        final String[] bytes;
        final int activeByte;
        final int cursor;

        Result(String[] bytes, int activeByte, int cursor) {
            this.bytes = bytes;
            this.activeByte = activeByte;
            this.cursor = cursor;
        }
    }

    static Result delete(String[] bytes, int visibleLength, int activeByte,
            int selectionStart, int selectionEnd, int count) {
        if (visibleLength <= 0 || visibleLength > bytes.length
                || activeByte < 0 || activeByte >= visibleLength || count <= 0) {
            throw new IllegalArgumentException("Invalid byte deletion position");
        }
        String[] result = bytes.clone();
        String value = result[activeByte];
        int start = Math.max(0, Math.min(value.length(), Math.min(selectionStart, selectionEnd)));
        int end = Math.max(start, Math.min(value.length(), Math.max(selectionStart, selectionEnd)));
        int cursor = start;
        if (start != end) {
            // A hardware Backspace clears its selection without deleting extra characters.
            result[activeByte] = value.substring(0, start) + value.substring(end);
        } else {
            while (count > 0) {
                value = result[activeByte];
                int removed = Math.min(cursor, count);
                if (removed > 0) {
                    result[activeByte] = value.substring(0, cursor - removed)
                            + value.substring(cursor);
                    cursor -= removed;
                    count -= removed;
                }
                if (count == 0 || activeByte == 0) break;
                activeByte--;
                cursor = result[activeByte].length();
            }
        }
        while (result[activeByte].isEmpty() && activeByte > 0) {
            activeByte--;
            cursor = result[activeByte].length();
        }
        return new Result(result, activeByte, cursor);
    }
}
