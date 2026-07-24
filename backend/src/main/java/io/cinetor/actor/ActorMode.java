package io.cinetor.actor;

/**
 * How each per-show seat actor is created — the axis this phase of the project
 * compares.
 *
 * <ul>
 *   <li>{@link #MEMORY} — the original {@link ShowActor}: plain in-memory state,
 *       no journal, no backpressure. Fastest, but a restart loses all holds and
 *       bookings.</li>
 *   <li>{@link #STATEFUL} — {@link StatefulShowActor} with a Cajun message
 *       journal + snapshot store. Every command is written to disk before the
 *       reply, so seat state survives a restart. Durability at the cost of I/O
 *       latency.</li>
 *   <li>{@link #STATEFUL_BACKPRESSURE} — as {@link #STATEFUL}, plus a bounded,
 *       resizable mailbox with backpressure. Under overload the mailbox pushes
 *       back instead of growing without bound, trading some rejected/slowed
 *       sends for stable latency and memory.</li>
 * </ul>
 */
public enum ActorMode {
    MEMORY,
    STATEFUL,
    STATEFUL_BACKPRESSURE;

    /** True when this mode uses a persistent stateful actor (journal + snapshots). */
    public boolean isStateful() {
        return this == STATEFUL || this == STATEFUL_BACKPRESSURE;
    }

    /** True when this mode enables mailbox backpressure. */
    public boolean usesBackpressure() {
        return this == STATEFUL_BACKPRESSURE;
    }

    /**
     * Parses a mode from config, tolerant of case and {@code -}/{@code _}
     * separators (so {@code stateful-backpressure} and {@code STATEFUL_BACKPRESSURE}
     * both work). Falls back to {@link #MEMORY} for null/blank/unknown input,
     * preserving the demo's original behaviour when nothing is set.
     */
    public static ActorMode fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return MEMORY;
        }
        String normalized = raw.trim().toUpperCase().replace('-', '_');
        // A couple of friendly aliases.
        switch (normalized) {
            case "BACKPRESSURE", "BP", "STATEFUL_BP" -> {
                return STATEFUL_BACKPRESSURE;
            }
            case "PERSISTENT" -> {
                return STATEFUL;
            }
            default -> {
                // fall through to enum parsing below
            }
        }
        try {
            return ActorMode.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return MEMORY;
        }
    }

    /** Lower-case, hyphenated label used in config responses and logs. */
    public String label() {
        return name().toLowerCase().replace('_', '-');
    }
}
