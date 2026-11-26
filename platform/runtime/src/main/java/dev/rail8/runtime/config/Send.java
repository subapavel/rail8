package dev.rail8.runtime.config;

import java.util.Comparator;
import java.util.Objects;

/**
 * One pair of what a connection sends: the name of a secret, without {@code secret://}, and a server it goes to, as
 * {@link Servers} writes it. Pairs sort by secret, then by server.
 *
 * @param secret the secret's name
 * @param server the server, such as {@code partner.example:443}
 */
record Send(String secret, String server) implements Comparable<Send> {

    private static final Comparator<Send> ORDER = Comparator.comparing(Send::secret).thenComparing(Send::server);

    Send {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(server, "server");
    }

    @Override
    public int compareTo(Send other) {
        return ORDER.compare(this, other);
    }
}
