package dev.rail8.ingress;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.PemKeyCertOptions;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.X509KeyManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A server's certificate and its private key for a Vert.x HTTPS listener, read once from two PEM files and checked when the
 * listener's configuration is read; a new certificate takes a restart. {@code <prefix>_CERT} names the file of the server's
 * certificate, its chain after it, each certificate's issuer right after it; {@code <prefix>_KEY} names the file of its unencrypted private key, PKCS#8
 * ({@code PRIVATE KEY}), PKCS#1 ({@code RSA PRIVATE KEY}) or SEC1 ({@code EC PRIVATE KEY}). The key must be the first
 * certificate's: the check signs a probe with the key and verifies it with the certificate's public key. The listener serves
 * the key that was checked, never a second read of the file.
 *
 * <p>A variable set to the empty string counts as not set. No refusal and no text of this class shows a file's content; each
 * refusal names the variable, and the path once it is an absolute one.
 *
 * @since 0.1
 */
public final class ServerCertificate {

    /** The most bytes either file may hold. */
    public static final long MAX_BYTES = 64L * 1024;

    private static final String KEY_FORMS = "unencrypted PEM private key (PRIVATE KEY, RSA PRIVATE KEY or EC PRIVATE KEY)";
    private static final byte[] PROBE = "rail8 checks that the key is the certificate's".getBytes(StandardCharsets.US_ASCII);

    private final String certVariable;
    private final Path certPath;
    private final String keyVariable;
    private final Path keyPath;
    private final X509Certificate leaf;
    private final KeyManagerFactory keys;

    private ServerCertificate(String certVariable, Path certPath, String keyVariable, Path keyPath, X509Certificate leaf, KeyManagerFactory keys) {
        this.certVariable = certVariable;
        this.certPath = certPath;
        this.keyVariable = keyVariable;
        this.keyPath = keyPath;
        this.leaf = leaf;
        this.keys = keys;
    }

    /**
     * The certificate {@code <prefix>_CERT} and {@code <prefix>_KEY} of {@code env} name, read and checked now; empty when
     * neither is set.
     *
     * @throws IllegalArgumentException one of the two is set without the other; a value is not an absolute path; a file is
     *                                  missing, cannot be read or holds more than {@value #MAX_BYTES} bytes; the certificate file
     *                                  holds no PEM certificate, or certificates whose issuer does not come right after them; the
     *                                  key file holds no unencrypted PEM private key of the first certificate's algorithm; or the
     *                                  key is not the first certificate's
     */
    public static Optional<ServerCertificate> fromEnv(Map<String, String> env, String prefix) {
        String certVariable = prefix + "_CERT";
        String keyVariable = prefix + "_KEY";
        String certValue = env.getOrDefault(certVariable, "");
        String keyValue = env.getOrDefault(keyVariable, "");
        if (certValue.isEmpty() && keyValue.isEmpty()) return Optional.empty();
        if (certValue.isEmpty() || keyValue.isEmpty()) {
            throw new IllegalArgumentException(certVariable + " and " + keyVariable + " are given both or neither: "
                    + (certValue.isEmpty() ? certVariable : keyVariable) + " is not set");
        }
        Path certPath = path(certVariable, certValue);
        Path keyPath = path(keyVariable, keyValue);
        byte[] certBytes = read(certVariable, certPath);
        X509Certificate leaf = first(certVariable, certPath, certBytes);
        byte[] keyBytes = read(keyVariable, keyPath);
        try {
            KeyManagerFactory keys;
            PrivateKey key;
            try {
                // the values are in memory, so no Vert.x instance is needed to read files
                keys = new PemKeyCertOptions().setCertValue(Buffer.buffer(certBytes)).setKeyValue(Buffer.buffer(keyBytes)).getKeyManagerFactory(null);
                key = privateKey(keys, leaf);
            } catch (Exception notAKey) {   // the cause is not kept: a parser's message is no place to risk a piece of the key
                throw new IllegalArgumentException(keyVariable + " holds no " + KEY_FORMS + " of the first certificate's "
                        + leaf.getPublicKey().getAlgorithm() + " key: " + keyPath);
            }
            if (!pair(key, leaf.getPublicKey())) {
                throw new IllegalArgumentException(keyVariable + " is not the key of the first certificate in " + certVariable + " ("
                        + leaf.getSubjectX500Principal().getName() + "; the server's own certificate comes first): " + keyPath);
            }
            return Optional.of(new ServerCertificate(certVariable, certPath, keyVariable, keyPath, leaf, keys));
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    /** {@code options} serving HTTPS with this certificate and the key that was checked. */
    public HttpServerOptions secure(HttpServerOptions options) {
        return options.setSsl(true).setKeyCertOptions(KeyCertOptions.wrap(keys));
    }

    /** The variables and their files, the certificate's subject and its expiry; never the key. */
    @Override
    public String toString() {
        return "ServerCertificate[" + certVariable + "=" + certPath + ", subject=" + leaf.getSubjectX500Principal().getName() + ", notAfter="
                + leaf.getNotAfter().toInstant() + ", " + keyVariable + "=" + keyPath + "]";
    }

    private static Path path(String variable, String value) {
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException notAPath) {
            path = null;
        }
        if (path == null || !path.isAbsolute()) {
            throw new IllegalArgumentException(variable + " must be the absolute path of a file (the value is not shown)");
        }
        return path;
    }

    private static byte[] read(String variable, Path path) {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(path)) {
            bytes = in.readNBytes(Math.toIntExact(MAX_BYTES + 1));
        } catch (NoSuchFileException missing) {
            throw new IllegalArgumentException(variable + " names a file that does not exist: " + path, missing);
        } catch (IOException | RuntimeException unreadable) {
            throw new IllegalArgumentException(variable + " names a file that cannot be read: " + path, unreadable);
        }
        if (bytes.length > MAX_BYTES) {
            Arrays.fill(bytes, (byte) 0);
            throw new IllegalArgumentException(variable + " names a file of more than " + MAX_BYTES + " bytes: " + path);
        }
        return bytes;
    }

    /** The first certificate of the file, the server's own, once each certificate's issuer comes right after it. */
    private static X509Certificate first(String variable, Path path, byte[] pem) {
        List<X509Certificate> all = new ArrayList<>();
        try {
            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem))) {
                if (c instanceof X509Certificate x509) all.add(x509);
            }
        } catch (CertificateException notACertificate) {
            all.clear();
        }
        if (all.isEmpty()) throw new IllegalArgumentException(variable + " holds no PEM certificate: " + path);
        for (int i = 0; i + 1 < all.size(); i++) {
            if (!all.get(i).getIssuerX500Principal().equals(all.get(i + 1).getSubjectX500Principal())) {
                throw new IllegalArgumentException(variable + " holds its certificates out of order (the server's own first, each one's"
                        + " issuer right after it): " + path);
            }
        }
        return all.getFirst();
    }

    /** The private key Vert.x read for the certificate's algorithm. */
    private static PrivateKey privateKey(KeyManagerFactory keys, X509Certificate leaf) {
        for (KeyManager manager : keys.getKeyManagers()) {
            if (!(manager instanceof X509KeyManager x509)) continue;
            String[] aliases = x509.getServerAliases(leaf.getPublicKey().getAlgorithm(), null);
            if (aliases != null && aliases.length > 0) return x509.getPrivateKey(aliases[0]);
        }
        throw new IllegalStateException("no private key of the certificate's algorithm");
    }

    /** Whether {@code key} signs what {@code certified} verifies. */
    private static boolean pair(PrivateKey key, PublicKey certified) {
        String algorithm = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> key.getAlgorithm();   // EdDSA keys name their own signature
        };
        try {
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(PROBE);
            byte[] signed = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(certified);
            verifier.update(PROBE);
            return verifier.verify(signed);
        } catch (GeneralSecurityException notThePair) {
            return false;
        }
    }
}
