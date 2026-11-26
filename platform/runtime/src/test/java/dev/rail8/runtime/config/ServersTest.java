package dev.rail8.runtime.config;

import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Settings;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a connection's secrets go: the servers and pairs of a body, which edits need the secret administrator's consent and
 * which do not, the digest of the pairs that do, and the text of a server.
 */
class ServersTest {

    /** An http-like form: the url marks the server; a path, a proxy, a pin and a switch of TLS do not. */
    static final Settings WEB = Settings.of(
            Field.text("url", "URL", "Where the partner is.").required().server(),
            Field.text("path", "Path", "Below the url."),
            Field.text("proxy", "Proxy", "Through which proxy."),
            Field.bool("verifyTls", "Verify TLS", "Whether the certificate is checked.").byDefault(true),
            Field.integer("timeoutMs", "Timeout (ms)", "How long.").byDefault(30_000),
            Field.secret("token", "Token", "The login."),
            Field.secret("pin", "Pinned certificate", "The partner's certificate."));

    /** An sftp-like form: the host and the port mark the server; two secrets log in, one pins the host key. */
    static final Settings SFTP = Settings.of(
            Field.text("host", "Host", "The server.").required().server(),
            Field.integer("port", "Port", "The server's port.").range(1, 65535).byDefault(22).server(),
            Field.text("user", "User", "Who."),
            Field.secret("password", "Password", "The password."),
            Field.secret("privateKey", "Private key", "The key."),
            Field.secret("hostKey", "Host key", "The server key's fingerprint."),
            Field.integer("connectTimeoutMs", "Connect timeout (ms)", "How long.").byDefault(10_000));

    static final Set<String> WEB_SCHEMES = Set.of("http", "https");

    static Map<String, Object> values(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    static Sends web(Map<String, Object> values, String... allowedHosts) {
        return Servers.of(WEB, values, List.of(allowedHosts), WEB_SCHEMES);
    }

    static Sends sftp(Map<String, Object> values) {
        return Servers.of(SFTP, values, List.of(), Set.of());
    }

    static final Map<String, Object> PARTNER = values("url", "https://partner.example/api", "token", "secret://partner.token",
            "pin", "secret://partner.pin");
    static final Map<String, Object> DROP = values("host", "files.example", "password", "secret://drop.password",
            "hostKey", "secret://drop.hostkey");

    /** {@code base} with {@code key} set to {@code value}, or removed when it is null. */
    static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        if (value == null) m.remove(key);
        else m.put(key, value);
        return m;
    }

    /** The pairs {@code draft} needs consent for, when {@code consented} is what a verified version sent. */
    static Set<Send> needed(Sends consented, Sends draft) {
        return Servers.needs(draft, consented.pairs()).map(Needed::pairs).orElse(new TreeSet<>());
    }

    @Test
    void aBodySendsEachSecretToEachServer() {
        Sends partner = web(PARTNER);
        assertEquals(Set.of("partner.example:443"), partner.servers());
        assertEquals(Set.of(new Send("partner.pin", "partner.example:443"), new Send("partner.token", "partner.example:443")), partner.pairs());
        Sends drop = sftp(DROP);
        assertEquals(Set.of("files.example:22"), drop.servers(), "the port's default, joined by ':' in the form's order");
        assertEquals(Set.of(new Send("drop.hostkey", "files.example:22"), new Send("drop.password", "files.example:22")), drop.pairs(),
                "a host key counts as any other secret");
    }

    @Test
    void eachOfTheseNeedsConsent() {
        Sends before = web(PARTNER);
        assertEquals(Set.of(new Send("partner.pin", "other.example:443"), new Send("partner.token", "other.example:443")),
                needed(before, web(with(PARTNER, "url", "https://other.example/api"))), "a new host");
        assertEquals(Set.of(new Send("partner.pin", "partner.example:8443"), new Send("partner.token", "partner.example:8443")),
                needed(before, web(with(PARTNER, "url", "https://partner.example:8443/api"))), "a new port");
        assertEquals(Set.of(new Send("partner.token2", "partner.example:443")),
                needed(before, web(with(PARTNER, "token", "secret://partner.token2"))), "a new secret");
        Sends unpinned = web(with(PARTNER, "pin", null));
        assertEquals(Set.of(new Send("partner.pin", "partner.example:443")), needed(unpinned, web(PARTNER)), "a new trust secret, a pin");
        assertEquals(Set.of(new Send("partner.pin", "cdn.example:443"), new Send("partner.token", "cdn.example:443")),
                needed(before, web(PARTNER, "cdn.example:443")), "an added allowed host");
        assertEquals(Set.of(new Send("drop.hostkey", "files.example:2222"), new Send("drop.password", "files.example:2222")),
                needed(sftp(DROP), sftp(with(DROP, "port", 2222))), "a new port of a port field");
    }

    @Test
    void noneOfTheseNeedsConsent() {
        Sends before = web(PARTNER, "cdn.example:443", "static.example:443");
        Map<String, Map<String, Object>> drafts = new LinkedHashMap<>();
        drafts.put("a new path", with(PARTNER, "path", "/orders"));
        drafts.put("a new proxy", with(PARTNER, "proxy", "http://proxy.internal:3128"));
        drafts.put("a trust removed", with(PARTNER, "pin", null));
        drafts.put("a TLS switch", with(PARTNER, "verifyTls", false));
        drafts.put("a field that is not marked", with(PARTNER, "timeoutMs", 5_000));
        drafts.put("a host in another case", with(PARTNER, "url", "https://Partner.EXAMPLE/api"));
        drafts.put("the default port written out", with(PARTNER, "url", "https://partner.example:443/api"));
        drafts.put("another path, query and user-free fragment of the url", with(PARTNER, "url", "HTTPS://partner.example/v2?x=1#top"));
        drafts.put("a sent secret taken away", with(PARTNER, "token", null));
        drafts.put("two secret fields swapped", values("url", "https://partner.example/api", "token", "secret://partner.pin",
                "pin", "secret://partner.token"));
        for (Map.Entry<String, Map<String, Object>> d : drafts.entrySet()) {
            Sends draft = web(d.getValue(), "cdn.example:443", "static.example:443");
            assertEquals(Optional.empty(), Servers.needs(draft, before.pairs()), d.getKey());
        }
        assertEquals(Optional.empty(), Servers.needs(web(PARTNER, "static.example:443", "cdn.example:443"), before.pairs()), "hosts reordered");
        assertEquals(Optional.empty(), Servers.needs(web(PARTNER, "static.example:443"), before.pairs()), "one removed");
        assertEquals(Optional.empty(), Servers.needs(sftp(with(DROP, "port", 22)), sftp(DROP).pairs()), "an unset field written out at its default");
        assertEquals(Optional.empty(), Servers.needs(sftp(with(DROP, "port", 22L)), sftp(DROP).pairs()), "a whole number as a long");
        assertEquals(Optional.empty(), Servers.needs(sftp(with(DROP, "user", "other")), sftp(DROP).pairs()), "a user is not the server");
    }

    @Test
    void aPairAmongThoseConsentedNeedsNoneAndANewOneStill() {
        Sends there = web(with(PARTNER, "url", "https://other.example/"));
        Set<Send> consented = new TreeSet<>(web(PARTNER).pairs());
        consented.addAll(there.pairs());
        assertEquals(Optional.empty(), Servers.needs(there, consented), "going back to a server a kept version sent");
        Sends third = web(with(PARTNER, "url", "https://third.example/"));
        assertEquals(2, Servers.needs(third, consented).orElseThrow().pairs().size());
    }

    @Test
    void withNothingConsentedEveryPairNeedsConsent() {
        Sends partner = web(PARTNER, "cdn.example");
        Needed needed = Servers.needs(partner, Set.of()).orElseThrow();
        assertEquals(partner.pairs(), needed.pairs());
        assertEquals(6, needed.pairs().size(), "two secrets, three servers: the url's and two of an entry without a port");
    }

    @Test
    void aDraftThatSendsNothingNeedsNoConsent() {
        assertEquals(Optional.empty(), Servers.needs(web(values("url", "https://partner.example/")), Set.of()), "no secret");
        assertEquals(Set.of("partner.example:443"), web(values("url", "https://partner.example/")).servers());
        Settings plain = Settings.of(Field.text("dir", "Directory", "Where."));
        Sends none = Servers.of(plain, values("dir", "/in"), List.of(), Set.of());
        assertEquals(Set.of(), none.servers(), "a form that marks no server has none");
        assertEquals(Optional.empty(), Servers.needs(none, Set.of()));
    }

    @Test
    void theDigestIsThatOfTheSortedPairsAndStableAcrossKeyOrderAndNodes() {
        Map<String, Object> reversed = new LinkedHashMap<>();
        List.copyOf(PARTNER.keySet()).reversed().forEach(k -> reversed.put(k, PARTNER.get(k)));
        Map<String, Object> hashed = new HashMap<>(PARTNER);
        Needed one = Servers.needs(web(PARTNER), Set.of()).orElseThrow();
        assertEquals(one, Servers.needs(web(reversed), Set.of()).orElseThrow());
        assertEquals(one, Servers.needs(web(hashed), Set.of()).orElseThrow());
        // SHA-256 of {"sends":[{"secret":"partner.pin","server":"partner.example:443"},{"secret":"partner.token","server":"partner.example:443"}]}
        assertEquals("e2dbec3a542a9ba5958d7bd5bb6da76af3765e230c15dd8b6e65c5bc276a715c", one.hex(), "the same on every node");
        assertEquals(32, one.digest().length);
        SortedSet<Send> escaped = new TreeSet<>(Set.of(new Send("a\"b\\c\u0001\n", "h:1")));
        // SHA-256 of {"sends":[{"secret":"a\"b\\c\u0001\n","server":"h:1"}]}
        assertEquals("a96ba6daa4d6a2682d2bc8fcd213072d0c522b8d939ea78088c9f15888f6af97",
                java.util.HexFormat.of().formatHex(Servers.digest(escaped)), "RFC 8785's escapes");
    }

    @Test
    void aServersTextWritesIPv6InBracketsAndRefusesAColonOutsideThem() {
        assertEquals(Set.of("[2001:db8::1]:443"), web(values("url", "https://[2001:DB8::1]/api")).servers());
        assertEquals(Set.of("[2001:db8::1]:22"), sftp(with(DROP, "host", "[2001:DB8::1]")).servers());
        for (String host : List.of("2001:db8::1", "files.example:2222", "secret://drop.host")) {
            Servers.Invalid refused = assertThrows(Servers.Invalid.class, () -> sftp(with(DROP, "host", host)), host);
            assertEquals("host", refused.field());
            assertEquals(422, refused.status());
            assertEquals("invalid", refused.code());
            assertFalse(refused.getMessage().contains(host), "the value is never echoed: " + refused.getMessage());
        }
    }

    @Test
    void aUrlOfASchemeWithoutAKnownDefaultPortWritesItsPort() {
        Servers.Invalid refused = assertThrows(Servers.Invalid.class, () -> web(values("url", "amqp://broker.example")));
        assertEquals("url", refused.field());
        assertEquals(Set.of("broker.example:5672"), web(values("url", "amqp://broker.example:5672")).servers());
        assertEquals(Set.of("files.example:22"), web(values("url", "sftp://files.example/in")).servers());
        assertEquals(Set.of("partner.example:80"), web(values("url", "http://partner.example")).servers());
    }

    @Test
    void anAllowedHostWithoutAPortIsOneServerForEachDefaultPortOfTheAddressSchemes() {
        assertEquals(Set.of("cdn.example:80", "cdn.example:443", "partner.example:443"), web(PARTNER, "CDN.example").servers(),
                "a form of two schemes: two servers");
        assertEquals(Set.of("cdn.example:443", "partner.example:443"),
                Servers.of(WEB, PARTNER, List.of("cdn.example"), Set.of("https")).servers(), "one scheme: one server");
        assertEquals(Set.of("*.example.com:443", "partner.example:443"), web(PARTNER, "*.Example.COM:443").servers(),
                "a wildcard kept as written, in lower case");
        assertEquals(Set.of("[2001:db8::1]:80", "[2001:db8::1]:443", "partner.example:443"), web(PARTNER, "[2001:DB8::1]").servers());
        Servers.Invalid noScheme = assertThrows(Servers.Invalid.class, () -> Servers.of(WEB, PARTNER, List.of("cdn.example"), Set.of()));
        assertEquals("allowedHosts", noScheme.field(), "no port can be named");
        for (String wrong : List.of("2001:db8::1", "cdn.example:0", "cdn.example:65536", "cdn.example:x", "", ":443", "[2001:db8::1")) {
            assertEquals("allowedHosts", assertThrows(Servers.Invalid.class, () -> web(PARTNER, wrong), wrong).field(), wrong);
        }
    }

    @Test
    void aSecretFieldHoldsAReferenceAndOnlyItsNameIsSent() {
        Servers.Invalid refused = assertThrows(Servers.Invalid.class, () -> web(with(PARTNER, "token", "hunter2")));
        assertEquals("token", refused.field());
        assertFalse(refused.getMessage().contains("hunter2"), "the value is never echoed");
        assertThrows(Servers.Invalid.class, () -> web(with(PARTNER, "token", "secret://")));
        assertTrue(web(PARTNER).pairs().stream().allMatch(p -> !p.secret().startsWith("secret://")));
    }

    @Test
    void anUnsetMarkedFieldWithoutADefaultCountsAsEmpty() {
        assertEquals(Set.of(":22"), sftp(with(DROP, "host", null)).servers(), "an incomplete draft still has a server");
    }
}
