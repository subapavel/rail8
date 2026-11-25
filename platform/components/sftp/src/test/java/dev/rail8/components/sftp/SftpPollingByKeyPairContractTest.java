package dev.rail8.components.sftp;

import dev.rail8.api.Inbound;
import dev.rail8.api.Secret;

import java.security.KeyPair;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The polling suite with the login by a key pair with no certificate: {@code privateKey} a handle of kind key pair, no password. */
class SftpPollingByKeyPairContractTest extends SftpPollingContractTest {

    private final KeyPair key = keyOrFail();

    @Override protected Inbound inbound() {
        server().authorize(key.getPublic());
        Map<String, Object> login = new HashMap<>();
        login.put("password", null);
        login.put("privateKey", Secret.keyPair("secret://sftp-key", () -> new Secret.KeyPair(key.getPrivate(), key.getPublic(), List.of())));
        return server().endpoint("sftp-in", "/inbox", login);
    }

    /** A server started again takes the same key. */
    @Override protected void partnerDown(boolean down) {
        super.partnerDown(down);
        if (!down) server().authorize(key.getPublic());
    }

    private static KeyPair keyOrFail() {
        try {
            return SftpConsumerEdgeTest.ecKey();
        } catch (Exception e) {
            throw new IllegalStateException("no EC key generator on this JDK", e);
        }
    }
}
