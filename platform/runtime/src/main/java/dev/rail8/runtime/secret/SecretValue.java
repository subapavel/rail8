package dev.rail8.runtime.secret;

import dev.rail8.api.Secret;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * A secret's value as a node holds it opened: a plain value, credentials, or a key pair with the encodings it is stored in.
 * {@link #plain()} is the JSON object that is sealed. No {@code toString} here holds a value.
 */
sealed interface SecretValue {

    /** The kind of this value. */
    Secret.Kind kind();

    /** The JSON object that is sealed, in UTF-8: {@code {"value"}}, {@code {"user", "password"}} or the key pair's encodings. */
    byte[] plain();

    /** What the API's handle reads: a {@link String}, a {@link Secret.Credentials} or a {@link Secret.KeyPair}. */
    Object read();

    /** A plain value. */
    record Plain(String value) implements SecretValue {
        @Override public Secret.Kind kind() { return Secret.Kind.VALUE; }
        @Override public byte[] plain() { return bytes(new JsonObject().put("value", value)); }
        @Override public Object read() { return value; }
        @Override public String toString() { return "Plain[]"; }
    }

    /** Credentials. */
    record Login(Secret.Credentials credentials) implements SecretValue {
        @Override public Secret.Kind kind() { return Secret.Kind.CREDENTIALS; }
        @Override public byte[] plain() { return bytes(new JsonObject().put("user", credentials.user()).put("password", credentials.password())); }
        @Override public Object read() { return credentials; }
        @Override public String toString() { return "Login[]"; }
    }

    /**
     * A key pair: its keys and certificates as the JDK's objects, and the encodings they are stored in: the PKCS#8 bytes of the
     * private key, the X.509 SubjectPublicKeyInfo bytes of the public key and the DER of each certificate.
     */
    record Pair(Secret.KeyPair keyPair, byte[] pkcs8, byte[] spki, List<byte[]> certificates) implements SecretValue {
        @Override public Secret.Kind kind() { return Secret.Kind.KEY_PAIR; }

        @Override public byte[] plain() {
            Base64.Encoder b64 = Base64.getEncoder();
            JsonArray chain = new JsonArray();
            certificates.forEach(c -> chain.add(b64.encodeToString(c)));
            return bytes(new JsonObject().put("privateKey", b64.encodeToString(pkcs8)).put("publicKey", b64.encodeToString(spki)).put("certificates", chain));
        }

        @Override public Object read() { return keyPair; }

        /** The SHA-256 fingerprint of the public key's SubjectPublicKeyInfo, in lower-case hex. */
        String fingerprint() {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(spki));
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException("SHA-256 is not available", failure);
            }
        }

        /**
         * What the list shows of it, none of it secret: the algorithm and size, the leaf's subject, issuer and end, and the
         * fingerprint of the public key.
         */
        JsonObject facts() {
            JsonObject facts = new JsonObject().put("algorithm", algorithm()).put("size", size()).put("fingerprint", fingerprint());
            if (!keyPair.certificates().isEmpty()) {
                X509Certificate leaf = keyPair.certificates().getFirst();
                facts.put("subject", leaf.getSubjectX500Principal().getName()).put("issuer", leaf.getIssuerX500Principal().getName())
                        .put("notAfter", leaf.getNotAfter().toInstant().toString());
            }
            return facts;
        }

        private String algorithm() {
            return keyPair.publicKey() instanceof java.security.interfaces.EdECKey ed ? ed.getParams().getName() : keyPair.publicKey().getAlgorithm();
        }

        private int size() {
            if (keyPair.publicKey() instanceof RSAKey rsa) return rsa.getModulus().bitLength();
            if (keyPair.publicKey() instanceof ECKey ec) return ec.getParams().getCurve().getField().getFieldSize();
            return 255;
        }

        @Override public String toString() { return "Pair[" + keyPair.publicKey().getAlgorithm() + "]"; }
    }

    private static byte[] bytes(JsonObject json) {
        return json.encode().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The value of {@code kind} that the opened, padded {@code plain} text holds.
     *
     * @throws IllegalArgumentException it is not the JSON object of that kind
     */
    static SecretValue fromPlain(Secret.Kind kind, byte[] plain) {
        JsonObject json = new JsonObject(new String(plain, StandardCharsets.UTF_8).strip());
        return switch (kind) {
            case VALUE -> new Plain(required(json, "value"));
            case CREDENTIALS -> new Login(new Secret.Credentials(required(json, "user"), required(json, "password")));
            case KEY_PAIR -> {
                Base64.Decoder b64 = Base64.getDecoder();
                List<byte[]> chain = new ArrayList<>();
                for (Object c : json.getJsonArray("certificates", new JsonArray())) chain.add(b64.decode((String) c));
                yield KeyPairs.stored(b64.decode(required(json, "privateKey")), b64.decode(required(json, "publicKey")), chain);
            }
        };
    }

    private static String required(JsonObject json, String field) {
        String v = json.getString(field);
        if (v == null) throw new IllegalArgumentException("the opened value holds no " + field);
        return v;
    }
}
