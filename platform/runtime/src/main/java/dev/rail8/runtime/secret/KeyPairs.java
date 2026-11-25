package dev.rail8.runtime.secret;

import dev.rail8.api.Secret;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A key pair as a secret administrator writes it, checked: the private key in PEM, PKCS#8 ({@code BEGIN PRIVATE KEY}) or
 * unencrypted OpenSSH ({@code BEGIN OPENSSH PRIVATE KEY}), and 0 to {@value #MAX_CERTIFICATES} certificates in PEM, the leaf
 * first, {@value #MAX_CERTIFICATE_BYTES} bytes together. The keys taken are RSA of 2,048 to 8,192 bits, EC on secp256r1, secp384r1 or
 * secp521r1, and Ed25519, for the pair and for each certificate's key. bcprov only unpacks the OpenSSH container; every key object
 * comes from the JDK's {@link KeyFactory} and every certificate from its {@link CertificateFactory}. The public key is derived
 * from the private key, and a test signature made with the private key must verify with it. The leaf's key is the pair's
 * public key, and each further certificate signed the one before it. Neither trust nor dates are checked. Every refusal is 422
 * {@code invalid}, names its field and never shows a key.
 */
final class KeyPairs {

    static final int MAX_CERTIFICATES = 10;
    static final int MAX_CERTIFICATE_BYTES = 32 * 1024;

    /** How a PKCS#1 or SEC1 key is converted to the PKCS#8 form taken. */
    static final String CONVERT = "openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem";

    private static final Pattern PEM = Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]*)-----END \\1-----");
    private static final byte[] OPENSSH_MAGIC = "openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII);
    /** The DER before an Ed25519 public key's 32 bytes in its SubjectPublicKeyInfo. */
    private static final byte[] ED25519_SPKI = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final Map<String, String> CURVES = Map.of("P-256", "secp256r1", "P-384", "secp384r1", "P-521", "secp521r1");

    private KeyPairs() {}

    /**
     * The key pair of {@code privateKey} and {@code certificates}, checked.
     *
     * @throws SecretRefused 422 {@code invalid} naming {@code privateKey} or {@code certificates}
     */
    static SecretValue.Pair parse(String privateKey, List<String> certificates) throws SecretRefused {
        List<Block> keyBlocks = blocks(privateKey, "privateKey");
        if (keyBlocks.size() != 1) throw SecretRefused.invalid("privateKey", "give exactly one PEM private key");
        Block key = keyBlocks.getFirst();
        byte[] pkcs8 = switch (key.type()) {
            case "PRIVATE KEY" -> key.der();
            case "OPENSSH PRIVATE KEY" -> openSsh(key.der());
            case "ENCRYPTED PRIVATE KEY" -> throw SecretRefused.invalid("privateKey", "an encrypted key is not taken: give it unencrypted, PKCS#8");
            case "RSA PRIVATE KEY", "EC PRIVATE KEY" -> throw SecretRefused.invalid("privateKey",
                    "a " + key.type() + " (PKCS#1 or SEC1) is not taken: convert it to PKCS#8 with " + CONVERT);
            default -> throw SecretRefused.invalid("privateKey", "a PEM " + key.type() + " is not taken: give a PRIVATE KEY (PKCS#8) or an OPENSSH PRIVATE KEY");
        };
        PrivateKey priv = privateKey(pkcs8).orElseThrow(() -> SecretRefused.invalid("privateKey",
                "the key is not RSA, EC or Ed25519 in a form the node reads: convert a PKCS#1 or SEC1 key with " + CONVERT));
        algorithmTaken(priv, "privateKey");
        PublicKey pub = derived(priv);
        if (!signs(priv, pub)) throw SecretRefused.invalid("privateKey", "a signature made with the key does not verify with its public key");

        int bytes = 0;
        List<Block> certBlocks = new ArrayList<>();
        for (String text : certificates) {
            bytes += text.getBytes(StandardCharsets.UTF_8).length;
            certBlocks.addAll(blocks(text, "certificates"));
        }
        if (bytes > MAX_CERTIFICATE_BYTES) throw SecretRefused.invalid("certificates", "the certificates take more than " + MAX_CERTIFICATE_BYTES + " bytes");
        if (certBlocks.size() > MAX_CERTIFICATES) throw SecretRefused.invalid("certificates", "more than " + MAX_CERTIFICATES + " certificates");
        List<X509Certificate> chain = new ArrayList<>();
        List<byte[]> ders = new ArrayList<>();
        for (int i = 0; i < certBlocks.size(); i++) {
            Block b = certBlocks.get(i);
            if (!b.type().equals("CERTIFICATE")) throw SecretRefused.invalid("certificates", "block " + (i + 1) + " is a " + b.type() + ", not a CERTIFICATE");
            X509Certificate cert = certificate(b.der()).orElseThrow(() -> SecretRefused.invalid("certificates", "certificate " + (chain.size() + 1) + " cannot be read"));
            algorithmTaken(cert.getPublicKey(), "certificates");
            chain.add(cert);
            ders.add(b.der());
        }
        if (!chain.isEmpty() && !Arrays.equals(chain.getFirst().getPublicKey().getEncoded(), pub.getEncoded())) {
            throw SecretRefused.invalid("certificates", "the first certificate does not hold the key's public key: give the leaf first");
        }
        for (int i = 1; i < chain.size(); i++) {
            try {
                chain.get(i - 1).verify(chain.get(i).getPublicKey());
            } catch (GeneralSecurityException notSigned) {
                throw SecretRefused.invalid("certificates", "certificate " + (i + 1) + " did not sign certificate " + i + ": give the chain in order, the leaf first");
            }
        }
        return new SecretValue.Pair(new Secret.KeyPair(priv, pub, chain), pkcs8, pub.getEncoded(), List.copyOf(ders));
    }

    /**
     * The key pair stored as {@code pkcs8}, {@code spki} and the DER of each certificate, rebuilt by the JDK's factories.
     *
     * @throws IllegalArgumentException an encoding does not read
     */
    static SecretValue.Pair stored(byte[] pkcs8, byte[] spki, List<byte[]> certificates) {
        PrivateKey priv = privateKey(pkcs8).orElseThrow(() -> new IllegalArgumentException("the stored private key does not read"));
        PublicKey pub;
        try {
            pub = KeyFactory.getInstance(family(priv)).generatePublic(new X509EncodedKeySpec(spki));
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("the stored public key does not read");
        }
        List<X509Certificate> chain = new ArrayList<>();
        for (byte[] der : certificates) chain.add(certificate(der).orElseThrow(() -> new IllegalArgumentException("a stored certificate does not read")));
        return new SecretValue.Pair(new Secret.KeyPair(priv, pub, chain), pkcs8.clone(), spki.clone(), List.copyOf(certificates));
    }

    private record Block(String type, byte[] der) { }

    private static List<Block> blocks(String text, String field) throws SecretRefused {
        List<Block> out = new ArrayList<>();
        Matcher m = PEM.matcher(text);
        int end = 0;
        while (m.find()) {
            if (!text.substring(end, m.start()).isBlank()) throw SecretRefused.invalid(field, "it holds text outside its PEM blocks");
            try {
                out.add(new Block(m.group(1), Base64.getMimeDecoder().decode(m.group(2).strip())));
            } catch (IllegalArgumentException notBase64) {
                throw SecretRefused.invalid(field, "a PEM block is not base64");
            }
            end = m.end();
        }
        if (!text.substring(end).isBlank()) throw SecretRefused.invalid(field, "it is not PEM");
        return out;
    }

    /** The PKCS#8 bytes of the key in an unencrypted OpenSSH container. */
    private static byte[] openSsh(byte[] blob) throws SecretRefused {
        if (blob.length < OPENSSH_MAGIC.length || !Arrays.equals(Arrays.copyOf(blob, OPENSSH_MAGIC.length), OPENSSH_MAGIC)) {
            throw SecretRefused.invalid("privateKey", "the OPENSSH PRIVATE KEY is not an openssh-key-v1 container");
        }
        ByteBuffer in = ByteBuffer.wrap(blob, OPENSSH_MAGIC.length, blob.length - OPENSSH_MAGIC.length);
        if (in.remaining() < 4 || in.getInt(in.position()) < 0 || in.getInt(in.position()) > in.remaining() - 4) {
            throw SecretRefused.invalid("privateKey", "the OPENSSH PRIVATE KEY is not an openssh-key-v1 container");
        }
        byte[] cipher = new byte[in.getInt()];
        in.get(cipher);
        if (!new String(cipher, StandardCharsets.US_ASCII).equals("none")) {
            throw SecretRefused.invalid("privateKey", "an encrypted key is not taken: remove its passphrase with ssh-keygen -p, or give it as PKCS#8");
        }
        try {
            AsymmetricKeyParameter key = OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(blob);
            return PrivateKeyInfoFactory.createPrivateKeyInfo(key).getEncoded();
        } catch (IOException | RuntimeException unread) {
            throw SecretRefused.invalid("privateKey", "the OPENSSH PRIVATE KEY cannot be read");
        }
    }

    /** The private key of {@code pkcs8} as the first of the JDK's RSA, EC and Ed25519 factories reads it. */
    private static Optional<PrivateKey> privateKey(byte[] pkcs8) {
        for (String family : List.of("RSA", "EC", "Ed25519")) {
            try {
                return Optional.of(KeyFactory.getInstance(family).generatePrivate(new PKCS8EncodedKeySpec(pkcs8)));
            } catch (GeneralSecurityException | RuntimeException notThis) {
                // the next family
            }
        }
        return Optional.empty();
    }

    private static Optional<X509Certificate> certificate(byte[] der) {
        try {
            return Optional.of((X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der)));
        } catch (GeneralSecurityException | RuntimeException unread) {
            return Optional.empty();
        }
    }

    private static String family(java.security.Key key) {
        return switch (key.getAlgorithm()) {
            case "RSA" -> "RSA";
            case "EC" -> "EC";
            default -> "Ed25519";
        };
    }

    /** Refuses a key that is not RSA of 2,048 to 8,192 bits, EC on secp256r1, secp384r1 or secp521r1, or Ed25519. */
    private static void algorithmTaken(java.security.Key key, String field) throws SecretRefused {
        switch (key) {
            case RSAPrivateCrtKey rsa -> rsaSize(rsa.getModulus(), field);
            case RSAPublicKey rsa -> rsaSize(rsa.getModulus(), field);
            case ECPrivateKey ec -> curve(ec.getParams(), field);
            case ECPublicKey ec -> curve(ec.getParams(), field);
            case java.security.interfaces.EdECKey ed when ed.getParams().getName().equals("Ed25519") -> { }
            default -> throw SecretRefused.invalid(field, "a key of " + key.getAlgorithm()
                    + (key instanceof java.security.interfaces.RSAKey ? " without its CRT parts" : "") + " is not taken: RSA, EC on P-256, P-384 or P-521, or Ed25519");
        }
    }

    private static void rsaSize(BigInteger modulus, String field) throws SecretRefused {
        int bits = modulus.bitLength();
        if (bits < 2048 || bits > 8192) throw SecretRefused.invalid(field, "an RSA key of " + bits + " bits is not taken: 2,048 to 8,192");
    }

    /** The name of the curve {@code params} is, of secp256r1, secp384r1 and secp521r1; refused otherwise. */
    private static String curve(ECParameterSpec params, String field) throws SecretRefused {
        for (Map.Entry<String, String> c : CURVES.entrySet()) {
            ECNamedCurveParameterSpec named = ECNamedCurveTable.getParameterSpec(c.getValue());
            if (named.getN().equals(params.getOrder()) && named.getG().normalize().getAffineXCoord().toBigInteger().equals(params.getGenerator().getAffineX())) {
                return c.getValue();
            }
        }
        throw SecretRefused.invalid(field, "an EC key of this curve is not taken: P-256, P-384 or P-521");
    }

    /** The public key of {@code priv}, from the JDK's factory. */
    private static PublicKey derived(PrivateKey priv) throws SecretRefused {
        try {
            return switch (priv) {
                case RSAPrivateCrtKey rsa -> KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
                case ECPrivateKey ec -> {
                    ECNamedCurveParameterSpec named = ECNamedCurveTable.getParameterSpec(curve(ec.getParams(), "privateKey"));
                    org.bouncycastle.math.ec.ECPoint q = named.getG().multiply(ec.getS()).normalize();
                    yield KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(
                            new ECPoint(q.getAffineXCoord().toBigInteger(), q.getAffineYCoord().toBigInteger()), ec.getParams()));
                }
                case EdECPrivateKey ed -> {
                    byte[] raw = ed.getBytes().orElseThrow(() -> SecretRefused.invalid("privateKey", "the Ed25519 key holds no bytes"));
                    byte[] pub = new Ed25519PrivateKeyParameters(raw).generatePublicKey().getEncoded();
                    byte[] spki = Arrays.copyOf(ED25519_SPKI, ED25519_SPKI.length + pub.length);
                    System.arraycopy(pub, 0, spki, ED25519_SPKI.length, pub.length);
                    yield KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
                }
                default -> throw SecretRefused.invalid("privateKey", "its public key cannot be derived");
            };
        } catch (GeneralSecurityException failure) {
            throw SecretRefused.invalid("privateKey", "its public key cannot be derived");
        }
    }

    /** Whether a signature that {@code priv} makes verifies with {@code pub}. */
    private static boolean signs(PrivateKey priv, PublicKey pub) {
        String algorithm = switch (priv.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> "Ed25519";
        };
        byte[] probe = "rail8 key pair check".getBytes(StandardCharsets.US_ASCII);
        try {
            Signature s = Signature.getInstance(algorithm);
            s.initSign(priv);
            s.update(probe);
            byte[] signature = s.sign();
            s.initVerify(pub);
            s.update(probe);
            return s.verify(signature);
        } catch (GeneralSecurityException failure) {
            return false;
        }
    }
}
