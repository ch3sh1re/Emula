package dev.dunno.emula;

/** User-facing error categories; detailed exceptions remain in the diagnostic report. */
enum WatchUiError {
    PERMISSION, NFC_OFF, NFC_UNAVAILABLE, OEM_UNAVAILABLE, RECOVERY, OTHER;

    static final class Condition extends IllegalStateException {
        final WatchUiError kind;

        Condition(WatchUiError kind, String detail) {
            super(detail);
            this.kind = kind;
        }
    }

    static WatchUiError from(Throwable failure, boolean recoveryPending, boolean permissionGranted) {
        // A missing prerequisite must be fixed before recovery can be attempted.
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof Condition) return ((Condition) cause).kind;
            if (cause instanceof SecurityException && !permissionGranted) return PERMISSION;
        }
        return recoveryPending ? RECOVERY : OTHER;
    }
}
