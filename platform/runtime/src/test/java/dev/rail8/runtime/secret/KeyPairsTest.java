package dev.rail8.runtime.secret;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The checks of a key pair at the write: the algorithms and sizes taken, for the key and each certificate, and the chain. */
class KeyPairsTest {

    @TempDir
    static Path dir;

    static String openssl(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("openssl"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException(command + ": " + out);
        return out;
    }

    static String read(String file) throws IOException {
        return Files.readString(dir.resolve(file));
    }

    static String key(String file, String... options) throws Exception {
        List<String> args = new ArrayList<>(List.of("genpkey"));
        args.addAll(List.of(options));
        args.addAll(List.of("-out", file));
        openssl(args.toArray(String[]::new));
        return read(file);
    }

    static String selfSigned(String key, String cn, String out) throws Exception {
        openssl("req", "-x509", "-new", "-key", key, "-subj", "/CN=" + cn, "-days", "2", "-out", out);
        return read(out);
    }

    @BeforeAll
    static void keys() throws Exception {
        key("rsa2048.pem", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048");
        key("rsa1024.pem", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:1024");
        key("p256.pem", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256");
        key("p384.pem", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-384");
        key("p521.pem", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-521");
        key("k256.pem", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:secp256k1");
        key("ed25519.pem", "-algorithm", "ED25519");
        key("ed448.pem", "-algorithm", "ED448");
    }

    static SecretRefused refused(String key, List<String> certificates) {
        return assertThrows(SecretRefused.class, () -> KeyPairs.parse(key, certificates));
    }

    @Test
    void theAlgorithmsTakenAreRead() throws Exception {
        for (String file : List.of("rsa2048.pem", "p256.pem", "p384.pem", "p521.pem", "ed25519.pem")) {
            SecretValue.Pair pair = KeyPairs.parse(read(file), List.of());
            assertTrue(pair.keyPair().privateKey() != null && pair.keyPair().publicKey() != null, file);
            SecretValue.Pair again = KeyPairs.stored(pair.pkcs8(), pair.spki(), pair.certificates());
            assertArrayEquals(pair.keyPair().publicKey().getEncoded(), again.keyPair().publicKey().getEncoded(), file + ": its stored form reads back");
            assertArrayEquals(pair.keyPair().privateKey().getEncoded(), again.keyPair().privateKey().getEncoded(), file);
        }
    }

    @Test
    void otherAlgorithmsAndSizesAreRefused() throws Exception {
        for (String file : List.of("rsa1024.pem", "k256.pem", "ed448.pem")) {
            SecretRefused r = refused(read(file), List.of());
            assertEquals("invalid", r.code(), file);
            assertEquals("privateKey", r.values().getString("field"), file);
            assertEquals(422, r.status());
        }
    }

    @Test
    void otherFormsAreRefusedWithTheConversion() throws Exception {
        openssl("rsa", "-in", "rsa2048.pem", "-traditional", "-out", "pkcs1.pem");
        openssl("ec", "-in", "p256.pem", "-out", "sec1.pem");
        openssl("pkcs8", "-topk8", "-in", "p256.pem", "-passout", "pass:pw", "-out", "encrypted.pem");
        assertTrue(refused(read("pkcs1.pem"), List.of()).getMessage().contains(KeyPairs.CONVERT), "a PKCS#1 key names the conversion");
        assertTrue(refused(read("sec1.pem"), List.of()).getMessage().contains(KeyPairs.CONVERT), "a SEC1 key names the conversion");
        assertTrue(refused(read("encrypted.pem"), List.of()).getMessage().contains("encrypted"));
        assertEquals("invalid", refused("not a key", List.of()).code());
        assertEquals("invalid", refused(read("p256.pem") + read("p384.pem"), List.of()).code(), "two keys");
    }

    @Test
    void anOpenSshKeyIsTakenUnencryptedOnly() throws Exception {
        Ed25519PrivateKeyParameters key = new Ed25519PrivateKeyParameters(new SecureRandom());
        byte[] blob = OpenSSHPrivateKeyUtil.encodePrivateKey(key);
        SecretValue.Pair pair = KeyPairs.parse(pem("OPENSSH PRIVATE KEY", blob), List.of());
        assertEquals("Ed25519", pair.facts().getString("algorithm"));
        assertEquals(255, pair.facts().getInteger("size"));
        assertArrayEquals(key.generatePublicKey().getEncoded(), Arrays.copyOfRange(pair.spki(), pair.spki().length - 32, pair.spki().length));

        byte[] ciphered = blob.clone();
        int at = indexOf(ciphered, "none".getBytes(StandardCharsets.US_ASCII));
        System.arraycopy("aes1".getBytes(StandardCharsets.US_ASCII), 0, ciphered, at, 4);
        assertTrue(refused(pem("OPENSSH PRIVATE KEY", ciphered), List.of()).getMessage().contains("encrypted"), "a container with a cipher is refused");
    }

    @Test
    void aKeyWhosePartsDisagreeIsRefused() throws Exception {
        RSAPrivateCrtKey good = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(der(read("rsa2048.pem"))));
        RSAPrivateCrtKey bad = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new RSAPrivateCrtKeySpec(good.getModulus(),
                good.getPublicExponent(), good.getPrivateExponent(), good.getPrimeP(), good.getPrimeQ(), good.getPrimeExponentP().add(BigInteger.TWO),
                good.getPrimeExponentQ(), good.getCrtCoefficient()));
        SecretRefused r = refused(pem("PRIVATE KEY", bad.getEncoded()), List.of());
        assertTrue(r.getMessage().contains("does not verify"), r.getMessage());
    }

    @Test
    void aChainIsTheLeafFirstEachSignedByTheNext() throws Exception {
        String ca = selfSigned("p384.pem", "ca", "ca.pem");
        openssl("req", "-new", "-key", "p256.pem", "-subj", "/CN=leaf", "-out", "leaf.csr");
        openssl("x509", "-req", "-in", "leaf.csr", "-CA", "ca.pem", "-CAkey", "p384.pem", "-CAcreateserial", "-days", "2", "-out", "leaf.pem");
        String leaf = read("leaf.pem");

        SecretValue.Pair pair = KeyPairs.parse(read("p256.pem"), List.of(leaf, ca));
        assertEquals(2, pair.keyPair().certificates().size());
        assertEquals("CN=leaf", pair.facts().getString("subject"));
        assertEquals("CN=ca", pair.facts().getString("issuer"));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pair.spki())), pair.facts().getString("fingerprint"));
        assertEquals(KeyPairs.parse(read("p256.pem"), List.of(leaf + ca)).keyPair().certificates().size(), 2, "one text may hold the chain");

        assertTrue(refused(read("p256.pem"), List.of(ca, leaf)).getMessage().contains("leaf first"), "out of order");
        String stranger = selfSigned("p521.pem", "stranger", "stranger.pem");
        assertTrue(refused(read("p256.pem"), List.of(leaf, stranger)).getMessage().contains("did not sign"), "a certificate that did not sign the one before");
        assertTrue(refused(read("p384.pem"), List.of(leaf)).getMessage().contains("leaf first"), "a leaf of another key");
    }

    @Test
    void aCertificatesKeyFollowsTheSameRules() throws Exception {
        String weak = selfSigned("rsa1024.pem", "weak", "weak.pem");
        openssl("req", "-new", "-key", "p256.pem", "-subj", "/CN=leaf", "-out", "weak-leaf.csr");
        openssl("x509", "-req", "-in", "weak-leaf.csr", "-CA", "weak.pem", "-CAkey", "rsa1024.pem", "-CAcreateserial", "-days", "2", "-out", "weak-leaf.pem");
        SecretRefused r = refused(read("p256.pem"), List.of(read("weak-leaf.pem"), weak));
        assertEquals("certificates", r.values().getString("field"));
    }

    @Test
    void theCertificatesHaveTheirLimits() throws Exception {
        String own = selfSigned("p256.pem", "own", "own.pem");
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) eleven.add(own);
        assertTrue(refused(read("p256.pem"), eleven).getMessage().contains("more than 10"));
        assertTrue(refused(read("p256.pem"), List.of(own + " ".repeat(KeyPairs.MAX_CERTIFICATE_BYTES))).getMessage().contains("bytes"));
        assertEquals("certificates", refused(read("p256.pem"), List.of(read("p384.pem"))).values().getString("field"), "a key is not a certificate");
    }

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    static byte[] der(String pem) {
        return Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
    }

    static int indexOf(byte[] in, byte[] part) {
        for (int i = 0; i + part.length <= in.length; i++) if (Arrays.equals(in, i, i + part.length, part, 0, part.length)) return i;
        throw new IllegalArgumentException("not found");
    }
}
