package dev.rail8.runtime.access;

/** How a caller proved who it is, as the audit's {@code via} records it. */
public enum Via {
    /** A session of a logged-in user. */
    SESSION("session"),
    /** A token of a user. */
    TOKEN("token"),
    /** The emergency key. */
    EMERGENCY_KEY("emergency-key"),
    /** The shared token, while the bridge is open. */
    SHARED_TOKEN("shared-token"),
    /** No credential on a loopback bind, while the bridge is open. */
    LOCAL("local");

    private final String id;

    Via(String id) {
        this.id = id;
    }

    /** The id the audit stores. */
    public String id() {
        return id;
    }
}
