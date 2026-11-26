package dev.dunno.emula;

/** The target of each displayed button is explicit, even if Wear delivers an old tap. */
final class WatchTileAction {
    enum Kind { START, STOP, SELECT, TIMER }
    final Kind kind;
    final long revision;
    final String target;

    WatchTileAction(Kind kind, long revision, String target) {
        this.kind = kind;
        this.revision = revision;
        this.target = target;
    }

    String id() {
        return kind.name() + ":" + revision + ":" + target;
    }

    static WatchTileAction parse(String id) {
        if (id == null || id.isEmpty()) return null;
        String[] fields = id.split(":", 3);
        if (fields.length != 3 || fields[2].isEmpty()) return null;
        try {
            return new WatchTileAction(Kind.valueOf(fields[0]), Long.parseLong(fields[1]), fields[2]);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    boolean applies(long currentRevision, String selected, WatchNfcSession.Pending pending) {
        if (currentRevision != revision) return false;
        if (kind == Kind.STOP) return pending != null && pending.cardId.equals(target);
        if (pending != null) return false;
        return kind == Kind.SELECT || target.equals(selected);
    }

    static int nextDuration(int seconds) {
        return switch (seconds) {
            case 0 -> 3;
            case 3 -> 5;
            case 5 -> 10;
            case 10 -> 30;
            default -> 0;
        };
    }
}
