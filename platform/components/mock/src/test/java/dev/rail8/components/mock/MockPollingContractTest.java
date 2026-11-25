package dev.rail8.components.mock;

import dev.rail8.api.Component;
import dev.rail8.api.Connection;
import dev.rail8.api.Inbound;
import dev.rail8.api.Secret;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.PollingConsumerContract;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The mock standing in for the {@code sftp} scheme passes the same polling suite as the real component (D-076). */
class MockPollingContractTest extends PollingConsumerContract {

    private final FakeRuntime rt = FakeRuntime.create().withTreeLimit(4096);   // bodies above 4 KiB must stream into the store
    private static final String ENDPOINT = "sftp-in";
    private final MockComponent sftp = new MockComponent("sftp", rt.blobs(), rt.timer());

    @Override protected Component component() { return sftp; }

    /**
     * A connection keyed by the endpoint's id and holding its config, as the node hands the mock every use: {@code sftp}'s
     * secret fields as handles of each kind they take, credentials for {@code password}, a key pair for {@code privateKey},
     * a value for {@code hostKey}, none of which the mock reads.
     */
    @Override protected Inbound inbound() {
        Map<String, Secret> secrets = Map.of(
                "password", Secret.credentials("secret://sftp-login", () -> new Secret.Credentials("rail8", "not-used-by-the-mock")),
                "privateKey", Secret.keyPair("secret://sftp-key", MockPollingContractTest::keyPair),
                "hostKey", Secret.of("secret://sftp-host-key", () -> "SHA256:bm90LXVzZWQtYnktdGhlLW1vY2s"));
        return new Inbound("contract", Optional.of(new Connection(ENDPOINT, Map.of(), secrets)), Map.of(), 1_000_000);
    }

    private static Secret.KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            return new Secret.KeyPair(pair.getPrivate(), pair.getPublic(), List.of());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no EC key generator on this JDK", e);
        }
    }

    @Override protected void drop(String name, ReadStream<Buffer> bytes) { sftp.drop(ENDPOINT, name, bytesOf(bytes)); }

    @Override protected Set<String> remaining() { return Set.copyOf(sftp.remaining(ENDPOINT)); }

    @Override protected void tick() { advanceToNextRound(rt.clock(), rt.timer()); }

    @Override protected void partnerDown(boolean down) { sftp.partnerDown(ENDPOINT, down); }
}
