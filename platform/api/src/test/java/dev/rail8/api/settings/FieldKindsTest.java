package dev.rail8.api.settings;

import dev.rail8.api.Secret;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FieldKindsTest {

    @Test
    void aSecretFieldTakesAPlainValueUnlessItNamesKinds() {
        assertEquals(Set.of(Secret.Kind.VALUE), Field.secret("password", "Password", "The password.").kinds());
        assertEquals(List.of(), List.copyOf(Field.text("user", "User", "The login.").kinds()));
    }

    @Test
    void theKindsKeepTheOrderTheFieldNamesThem() {
        Field f = Field.secret("password", "Password", "The password.", Secret.Kind.CREDENTIALS, Secret.Kind.VALUE);
        assertEquals(List.of(Secret.Kind.CREDENTIALS, Secret.Kind.VALUE), List.copyOf(f.kinds()));
        assertEquals(List.of("credentials", "value"), f.toJson().get("kinds"));
        Settings s = Settings.of(f.tab(Tab.SECURITY));
        assertEquals(List.of(Secret.Kind.CREDENTIALS, Secret.Kind.VALUE), List.copyOf(s.kinds("password")));
        assertEquals(Set.of(), s.kinds("nothing"));
    }

    @Test
    void aSecretFieldNamesAtLeastOneKindAndEachOnce() {
        assertEquals("field 'password': a secret field takes at least one kind",
                assertThrows(IllegalArgumentException.class, () -> Field.secret("password", "Password", "The password.", new Secret.Kind[0])).getMessage());
        assertEquals("field 'password': the kind value is named twice",
                assertThrows(IllegalArgumentException.class,
                        () -> Field.secret("password", "Password", "The password.", Secret.Kind.VALUE, Secret.Kind.VALUE)).getMessage());
    }

    @Test
    void aFieldWithoutAPlainValueTakesOnlyAReference() {
        Settings s = Settings.of(Field.secret("key", "Key", "The key pair.", Secret.Kind.KEY_PAIR, Secret.Kind.CREDENTIALS).required());
        assertEquals(List.of(new Settings.Problem("/key", Settings.Problem.TYPE,
                        "must name a secret, secret://<name>, of kind key-pair or credentials: it takes no plain value")),
                s.check(Map.of("key", "-----BEGIN PRIVATE KEY-----"), Direction.IN));
        assertEquals(List.of(), s.check(Map.of("key", "secret://sftp.key"), Direction.IN));
    }

    @Test
    void aFieldWithAPlainValueTakesALiteralAndAReference() {
        Settings s = Settings.of(Field.secret("password", "Password", "The password.", Secret.Kind.VALUE, Secret.Kind.CREDENTIALS));
        assertEquals(List.of(), s.check(Map.of("password", "s3cret"), Direction.IN));
        assertEquals(List.of(), s.check(Map.of("password", "secret://sftp.login"), Direction.IN));
    }
}
