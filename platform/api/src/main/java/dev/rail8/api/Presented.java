package dev.rail8.api;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the caller of one message presented, as an adapter read it, handed to {@link Entry#enter}. Each record overrides
 * {@code toString}, which shows no user, password, key or token, so a record in a log line or a failure says only its
 * kind and how the request reached the node.
 *
 * @since 0.4
 */
public sealed interface Presented {

    /**
     * How the request reached the node.
     *
     * @since 0.4
     */
    Via via();

    /**
     * A name and a password, as {@code Authorization: Basic} carries them.
     *
     * @since 0.4
     */
    record Basic(String user, String password, Via via) implements Presented {
        public Basic {
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(password, "password");
            Objects.requireNonNull(via, "via");
        }

        @Override public String toString() { return "Basic[user=(hidden), password=(hidden), via=" + via + "]"; }
    }

    /**
     * An API key, as {@code X-API-Key} carries it.
     *
     * @since 0.4
     */
    record ApiKey(String key, Via via) implements Presented {
        public ApiKey {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(via, "via");
        }

        @Override public String toString() { return "ApiKey[key=(hidden), via=" + via + "]"; }
    }

    /**
     * A chain whose key the caller proved in a TLS handshake: the listener's own, or a trusted proxy's. Leaf first.
     *
     * @since 0.4
     */
    record Certificate(List<X509Certificate> chain, Via via) implements Presented {
        public Certificate {
            chain = List.copyOf(chain);
            Objects.requireNonNull(via, "via");
        }

        @Override public String toString() { return "Certificate[" + chain.size() + " certificates, via=" + via + "]"; }
    }

    /**
     * A token, as {@code Authorization: Bearer} carries it.
     *
     * @since 0.4
     */
    record Bearer(String token, Via via) implements Presented {
        public Bearer {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(via, "via");
        }

        @Override public String toString() { return "Bearer[token=(hidden), via=" + via + "]"; }
    }

    /**
     * No credential.
     *
     * @since 0.4
     */
    record Nothing(Via via) implements Presented {
        public Nothing { Objects.requireNonNull(via, "via"); }

        @Override public String toString() { return "Nothing[via=" + via + "]"; }
    }

    /**
     * How one request reached the node.
     *
     * @param remote       the caller's address: the peer, or the client a trusted proxy names; empty when the adapter has
     *                     none. Failures are counted by it.
     * @param secure       over the listener's own TLS, from a trusted proxy, or from loopback
     * @param browser      the request carries {@code Origin}, {@code Referer}, or {@code Sec-Fetch-Site} with any value but
     *                     {@code none}
     * @param senderChoice the one {@code Rail8-Sender} header
     *
     * @since 0.4
     */
    record Via(String remote, boolean secure, boolean browser, Optional<String> senderChoice) {
        public Via {
            Objects.requireNonNull(remote, "remote");
            Objects.requireNonNull(senderChoice, "senderChoice");
        }

        /** For a listener of an adapter's own, which reads no browser and no {@code Rail8-Sender}. */
        public static Via of(String remote, boolean secure) { return new Via(remote, secure, false, Optional.empty()); }
    }
}
