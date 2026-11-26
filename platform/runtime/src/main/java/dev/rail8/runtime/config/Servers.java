package dev.rail8.runtime.config;

import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Settings;
import dev.rail8.runtime.refusal.RefusalCode;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Where a connection's secrets go. Pure: no clock, no I/O; the same inputs give the same digest on every node. The read,
 * the consent, the activation and a test all call it, so they never disagree.
 *
 * <p>A connection's server is the effective values of the fields its form marks {@code server()}, in the form's order,
 * joined by {@code :}. A field not set counts as its default, and as empty when it has none. A text that parses as an
 * absolute URI with a host counts as its host in lower case and its port, the scheme's default port filled in
 * ({@code http} 80, {@code https} 443, {@code sftp} 22); its scheme, user info, path, query and fragment do not count. Any
 * other text is a host, in lower case, and holds no {@code :} outside brackets, so an IPv6 literal is written
 * {@code [2001:db8::1]}. A whole number is written as it is. So {@code http}'s server and {@code sftp}'s are each
 * {@code host:port}.
 *
 * <p>Each entry of {@code allowedHosts} is a server of its own, {@code host:port}, its host in lower case and a {@code *.}
 * kept as written. An entry without a port is one server for each default port of the schemes the adapter's address
 * fields name.
 *
 * <p>No secret's value is read: a secret field holds {@code secret://<name>}, and only the name goes into a pair, an answer
 * or a refusal.
 */
final class Servers {

    /** The default port of each scheme whose port may be left out. */
    private static final Map<String, Integer> DEFAULT_PORTS = Map.of("http", 80, "https", 443, "sftp", 22);
    private static final String REFERENCE = "secret://";
    private static final String ALLOWED_HOSTS = "allowedHosts";
    private static final Pattern BRACKETED = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");
    private static final int MAX_PORT = 65535;

    private Servers() {}

    /**
     * A value that cannot be written as a server or a secret's name: answered 422 {@code invalid}, naming the field and
     * never echoing the value.
     */
    static final class Invalid extends IllegalArgumentException {

        private final String field;

        Invalid(String field, String why) {
            super("field '" + field + "': " + why, null);
            this.field = field;
        }

        /** The field whose value is refused, or {@code allowedHosts}. */
        String field() {
            return field;
        }

        /** The answer's HTTP status: 422. */
        int status() {
            return 422;
        }

        /** The answer's code: {@code invalid}. */
        String code() {
            return RefusalCode.INVALID.code();
        }
    }

    /**
     * What {@code values} send through a connection of {@code connectionForm}.
     *
     * @param connectionForm the adapter's connection form
     * @param values         the values as stored, a secret field as {@code secret://<name>}
     * @param allowedHosts   the platform's {@code allowedHosts}; empty until a whole address from the message adds it
     * @param addressSchemes the schemes the adapter's address fields name, which give an entry of {@code allowedHosts}
     *                       without a port its ports
     * @throws Invalid when a value cannot be written as a server, or a secret field holds no reference
     */
    static Sends of(Settings connectionForm, Map<String, Object> values, List<String> allowedHosts, Set<String> addressSchemes) {
        Objects.requireNonNull(connectionForm, "connectionForm");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(allowedHosts, "allowedHosts");
        Objects.requireNonNull(addressSchemes, "addressSchemes");
        SortedSet<String> servers = new TreeSet<>();
        List<String> marked = connectionForm.servers();
        if (!marked.isEmpty()) {
            Map<String, Object> defaults = new HashMap<>(connectionForm.defaults(Direction.IN));
            defaults.putAll(connectionForm.defaults(Direction.OUT));
            List<String> parts = new ArrayList<>();
            for (String name : marked) {
                Object value = values.get(name);
                parts.add(part(name, value != null ? value : defaults.get(name)));
            }
            servers.add(String.join(":", parts));
        }
        for (String entry : allowedHosts) servers.addAll(allowed(entry, addressSchemes));
        SortedSet<Send> pairs = new TreeSet<>();
        for (String name : connectionForm.secrets()) {
            Object value = values.get(name);
            if (value == null) continue;
            if (!(value instanceof String s) || !s.startsWith(REFERENCE) || s.length() == REFERENCE.length()) {
                throw new Invalid(name, "a secret field holds a reference secret://<name>");
            }
            String secret = s.substring(REFERENCE.length());
            for (String server : servers) pairs.add(new Send(secret, server));
        }
        return new Sends(servers, pairs);
    }

    /**
     * The pairs of {@code draft} that are not among {@code consented}, with their digest; empty when there are none.
     * {@code consented} is every pair that a verified version of the connection sent; it is empty when none verifies: then
     * every pair of the draft needs consent.
     */
    static Optional<Needed> needs(Sends draft, Set<Send> consented) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(consented, "consented");
        SortedSet<Send> needed = new TreeSet<>(draft.pairs());
        needed.removeAll(consented);
        if (needed.isEmpty()) return Optional.empty();
        return Optional.of(new Needed(needed, digest(needed)));
    }

    /** One value of a marked field as the server's text writes it. */
    private static String part(String field, Object value) {
        if (value == null) return "";
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return Long.toString(((Number) value).longValue());
        }
        if (!(value instanceof String text)) return String.valueOf(value);
        Optional<String> url = hostAndPort(field, text);
        if (url.isPresent()) return url.get();
        if (BRACKETED.matcher(text).replaceAll("").indexOf(':') >= 0) {
            throw new Invalid(field, "a host holds no ':' outside brackets; an IPv6 address is written in brackets");
        }
        return text.toLowerCase(Locale.ROOT);
    }

    /** {@code host:port} of {@code text} when it is an absolute URI with a host, else empty. */
    private static Optional<String> hostAndPort(String field, String text) {
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException notAUri) {
            return Optional.empty();
        }
        if (!uri.isAbsolute() || uri.getHost() == null) return Optional.empty();
        int port = uri.getPort();
        if (port < 0) {
            Integer byDefault = DEFAULT_PORTS.get(uri.getScheme().toLowerCase(Locale.ROOT));
            if (byDefault == null) throw new Invalid(field, "a URL of a scheme without a known default port writes its port");
            port = byDefault;
        }
        return Optional.of(uri.getHost().toLowerCase(Locale.ROOT) + ":" + port);
    }

    /** The servers of one entry of {@code allowedHosts}. */
    private static List<String> allowed(String entry, Set<String> addressSchemes) {
        Objects.requireNonNull(entry, "allowedHosts entry");
        String lower = entry.toLowerCase(Locale.ROOT);
        String host;
        String port;
        if (lower.startsWith("[")) {
            int close = lower.indexOf(']');
            if (close < 0) throw new Invalid(ALLOWED_HOSTS, "an IPv6 address is closed by ']'");
            host = lower.substring(0, close + 1);
            String rest = lower.substring(close + 1);
            if (!rest.isEmpty() && !rest.startsWith(":")) throw new Invalid(ALLOWED_HOSTS, "an entry is host or host:port");
            port = rest.isEmpty() ? null : rest.substring(1);
        } else {
            int colon = lower.indexOf(':');
            if (colon != lower.lastIndexOf(':')) throw new Invalid(ALLOWED_HOSTS, "an IPv6 address is written in brackets");
            host = colon < 0 ? lower : lower.substring(0, colon);
            port = colon < 0 ? null : lower.substring(colon + 1);
        }
        if (host.isEmpty() || host.equals("[]")) throw new Invalid(ALLOWED_HOSTS, "an entry names a host");
        if (port != null) {
            if (!PORT.matcher(port).matches() || Integer.parseInt(port) < 1 || Integer.parseInt(port) > MAX_PORT) {
                throw new Invalid(ALLOWED_HOSTS, "a port is a whole number from 1 to 65535");
            }
            return List.of(host + ":" + Integer.parseInt(port));
        }
        List<String> servers = new ArrayList<>();
        for (String scheme : new TreeSet<>(addressSchemes)) {
            Integer byDefault = DEFAULT_PORTS.get(scheme.toLowerCase(Locale.ROOT));
            if (byDefault != null && !servers.contains(host + ":" + byDefault)) servers.add(host + ":" + byDefault);
        }
        if (servers.isEmpty()) throw new Invalid(ALLOWED_HOSTS, "an entry writes its port: no scheme of the adapter has a known default port");
        return List.copyOf(servers);
    }

    /** The SHA-256 of the RFC 8785 JSON {@code {"sends": [{"secret", "server"}]}} of {@code pairs}, in their order. */
    static byte[] digest(SortedSet<Send> pairs) {
        StringBuilder json = new StringBuilder("{\"sends\":[");
        boolean first = true;
        for (Send p : pairs) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"secret\":");
            string(json, p.secret());
            json.append(",\"server\":");
            string(json, p.server());
            json.append('}');
        }
        json.append("]}");
        try {
            return MessageDigest.getInstance("SHA-256").digest(json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException every) {
            throw new IllegalStateException("every Java platform has SHA-256", every);
        }
    }

    /** {@code s} as an RFC 8785 string: quoted, {@code "} and {@code \} escaped, a control character as its short or \\u form. */
    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
