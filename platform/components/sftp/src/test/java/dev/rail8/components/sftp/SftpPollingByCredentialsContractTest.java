package dev.rail8.components.sftp;

import dev.rail8.api.Inbound;
import dev.rail8.api.Secret;

import java.util.HashMap;
import java.util.Map;

/** The polling suite with the login by credentials: {@code password} a handle of kind credentials, no {@code user}. */
class SftpPollingByCredentialsContractTest extends SftpPollingContractTest {

    @Override protected Inbound inbound() {
        Map<String, Object> login = new HashMap<>();
        login.put("user", null);
        login.put("password", Secret.credentials("secret://sftp-login", () -> new Secret.Credentials(EmbeddedSftp.USER, EmbeddedSftp.PASSWORD)));
        return server().endpoint("sftp-in", "/inbox", login);
    }
}
