package dev.rail8.cli;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * A node's admin API as every command reaches it: the one place of the command line that builds an HTTP client. {@link #of}
 * decides everything before anything is sent, each refusal a {@link Cli.Usage}:
 * <ul>
 *   <li>the URL is {@code --admin}, else {@code RAIL8_ADMIN}, else {@value #DEFAULT}; it is an {@code http} or {@code https} URL
 *       with a host;</li>
 *   <li>{@code RAIL8_ADMIN_TLS} is {@code off} or not set (empty counts as not set);</li>
 *   <li>a plain {@code http://} URL is taken only for a loopback host — {@code localhost} in any case, an IPv4 literal in
 *       127.0.0.0/8, or {@code ::1}, as written and never resolved — unless {@code RAIL8_ADMIN_TLS=off} states that the network
 *       to the node is protected another way;</li>
 *   <li>an {@code https://} URL trusts the JVM's default trust store, or, when {@code RAIL8_ADMIN_CA} names a PEM file of one
 *       or more certificates (at most {@value #CA_MAX_BYTES} bytes), those certificates only. {@code off} never turns the
 *       certificate's check or the host name's check off.</li>
 * </ul>
 * Each request carries the bearer its command gives, or none, and a redirect is never followed. {@link #token} and {@link #key}
 * choose that bearer from the environment.
 */
final class AdminApi {

    static final String DEFAULT = "http://localhost:8081";
    /** The most bytes {@code RAIL8_ADMIN_CA} may hold. */
    static final long CA_MAX_BYTES = 64L * 1024;
    private static final Duration CONNECT = Duration.ofSeconds(10);
    /** The prefix of the emergency key's form. */
    private static final String KEY_PREFIX = "rail8k_";

    private final String url;
    private final Optional<SSLContext> trust;

    private AdminApi(String url, Optional<SSLContext> trust) {
        this.url = url;
        this.trust = trust;
    }

    /**
     * The bearer of every command but {@code rail8 user} and {@code rail8 bootstrap}: {@code RAIL8_TOKEN}, else, during the
     * bridge, the shared token in {@code RAIL8_ADMIN_TOKEN}, else none. A blank value counts as not set. A value that starts
     * with {@code rail8k_}, the emergency key's form, is never sent.
     *
     * @throws Cli.Usage the bearer it would choose starts with {@code rail8k_}; the message starts with {@code command} and never
     *                   holds the value
     */
    static Optional<String> token(String command, Map<String, String> env) {
        Optional<String> token = set(env, "RAIL8_TOKEN");
        if (token.isPresent()) {
            if (token.get().startsWith(KEY_PREFIX)) {
                throw new Cli.Usage(command + ": RAIL8_TOKEN holds a value of the emergency key's form, so nothing is sent: it takes a token"
                        + " of an account, rail8t_…");
            }
            return token;
        }
        Optional<String> shared = set(env, "RAIL8_ADMIN_TOKEN");
        if (shared.isPresent() && shared.get().startsWith(KEY_PREFIX)) {
            throw new Cli.Usage(command + ": RAIL8_ADMIN_TOKEN holds the emergency key, which only rail8 user and rail8 bootstrap send,"
                    + " so nothing is sent: set RAIL8_TOKEN to a token of an account");
        }
        return shared;
    }

    /**
     * The bearer of {@code rail8 user} and {@code rail8 bootstrap}: {@code RAIL8_ADMIN_TOKEN}, the emergency key, or during the
     * bridge the shared token.
     *
     * @throws Cli.Usage {@code RAIL8_ADMIN_TOKEN} is not set or blank; the message starts with {@code command}
     */
    static String key(String command, Map<String, String> env) {
        return set(env, "RAIL8_ADMIN_TOKEN")
                .orElseThrow(() -> new Cli.Usage(command + ": RAIL8_ADMIN_TOKEN is not set: it holds the emergency key"));
    }

    /** The value of {@code name} in {@code env}, empty when it is not set or blank. */
    private static Optional<String> set(Map<String, String> env, String name) {
        return Optional.ofNullable(env.get(name)).filter(v -> !v.isBlank());
    }

    /**
     * The admin API {@code command} calls: {@code flag} ({@code --admin}) or the environment's.
     *
     * @throws Cli.Usage the URL is no http or https URL with a host; {@code RAIL8_ADMIN_TLS} is neither {@code off} nor unset; a
     *                   plain {@code http://} URL to a host other than loopback without {@code off}; or, for {@code https://},
     *                   {@code RAIL8_ADMIN_CA} names a file that is missing, cannot be read, holds more than
     *                   {@value #CA_MAX_BYTES} bytes or no PEM certificate. Each message starts with {@code command}.
     */
    static AdminApi of(String command, Optional<String> flag, Map<String, String> env) {
        String given = flag.orElse(env.getOrDefault("RAIL8_ADMIN", DEFAULT));
        String url = given.replaceAll("/+$", "");
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException wrong) {
            throw new Cli.Usage(command + ": the admin URL '" + given + "' is not an http or https URL: " + wrong.getMessage());
        }
        String scheme = Optional.ofNullable(uri.getScheme()).map(s -> s.toLowerCase(Locale.ROOT)).orElse("");
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            throw new Cli.Usage(command + ": the admin URL '" + given + "' is not an http or https URL with a host");
        }
        String tls = env.getOrDefault("RAIL8_ADMIN_TLS", "");
        if (!tls.isEmpty() && !tls.equals("off")) throw new Cli.Usage(command + ": RAIL8_ADMIN_TLS must be off or not set, was '" + tls + "'");
        if (scheme.equals("http") && !tls.equals("off") && !loopback(uri.getHost())) {
            throw new Cli.Usage(command + ": the admin URL '" + given + "' is plain HTTP to a host other than loopback, so nothing is sent:"
                    + " use https://, or RAIL8_ADMIN_TLS=off where the network to the node is protected another way");
        }
        Optional<SSLContext> trust = scheme.equals("https")
                ? Optional.ofNullable(env.get("RAIL8_ADMIN_CA")).filter(ca -> !ca.isEmpty()).map(ca -> trusting(command, ca))
                : Optional.empty();
        return new AdminApi(url, trust);
    }

    /** The URL, without a trailing slash. */
    String url() {
        return url;
    }

    /**
     * {@code POST <url><pathAndQuery>} with {@code json} as {@code application/json} and {@code Authorization: Bearer <bearer>}
     * when {@code bearer} is present, waiting at most {@code answer} for the answer.
     *
     * @throws NoAnswer the node could not be reached or did not answer in time, or the TLS handshake failed
     */
    HttpResponse<String> post(String pathAndQuery, byte[] json, Optional<String> bearer, Duration answer) throws NoAnswer {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + pathAndQuery)).timeout(answer)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json));
        bearer.ifPresent(t -> request.header("Authorization", "Bearer " + t));
        HttpClient.Builder client = HttpClient.newBuilder().connectTimeout(CONNECT).followRedirects(HttpClient.Redirect.NEVER);
        trust.ifPresent(client::sslContext);
        try (HttpClient http = client.build()) {
            return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).join();
        } catch (CompletionException unreachable) {
            throw new NoAnswer("no answer from " + url + ": " + Cli.oneLine(String.valueOf(unreachable.getCause())));
        }
    }

    /** No answer from the node: unreachable, too slow, or a TLS handshake that failed. Its message names the URL, never the token. */
    static final class NoAnswer extends Exception {
        NoAnswer(String message) {
            super(message, null, false, false);
        }
    }

    /** Whether {@code host}, as the URL writes it, is loopback: {@code localhost}, an IPv4 literal in 127.0.0.0/8 or {@code ::1}. */
    private static boolean loopback(String host) {
        if (host.equalsIgnoreCase("localhost")) return true;
        String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try {
            return InetAddress.ofLiteral(literal).isLoopbackAddress();
        } catch (IllegalArgumentException aHostName) {
            return false;
        }
    }

    /** A TLS context that trusts the certificates of the file {@code ca} names, and only those. */
    private static SSLContext trusting(String command, String ca) {
        String variable = command + ": RAIL8_ADMIN_CA";
        Path path;
        try {
            path = Path.of(ca);
        } catch (InvalidPathException notAPath) {
            throw new Cli.Usage(variable + " is not a path: " + ca);
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(path)) {
            bytes = in.readNBytes(Math.toIntExact(CA_MAX_BYTES + 1));
        } catch (NoSuchFileException missing) {
            throw new Cli.Usage(variable + " names a file that does not exist: " + path);
        } catch (IOException unreadable) {
            throw new Cli.Usage(variable + " names a file that cannot be read: " + path);
        }
        if (bytes.length > CA_MAX_BYTES) throw new Cli.Usage(variable + " names a file of more than " + CA_MAX_BYTES + " bytes: " + path);
        Collection<? extends Certificate> certificates;
        try {
            certificates = CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes));
        } catch (CertificateException notACertificate) {
            certificates = List.of();
        }
        if (certificates.isEmpty()) throw new Cli.Usage(variable + " holds no PEM certificate: " + path);
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int i = 0;
            for (Certificate certificate : certificates) store.setCertificateEntry("ca-" + i++, certificate);
            TrustManagerFactory trusted = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trusted.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trusted.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException unexpected) {   // an empty in-memory store of the JDK's own types
            throw new IllegalStateException("a trust store of RAIL8_ADMIN_CA could not be made", unexpected);
        }
    }
}
