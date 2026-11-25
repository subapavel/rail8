package dev.rail8.api.settings;

import dev.rail8.api.settings.Settings.Problem;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.rail8.api.settings.Direction.IN;
import static dev.rail8.api.settings.Direction.OUT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What {@link Settings#check} says about an endpoint's options: one {@link Problem} per broken rule, at the option's JSON
 * pointer, in the form's words and never quoting a value.
 */
class SettingsCheckTest {

    static final Tab POLLING = Tab.of("polling", "Polling");

    /** A small adapter in the shape of SFTP's: a choice that shows a field, two alternatives, a pattern, a range. */
    static final Settings DROP = Settings.of(
                    Field.text("host", "Host", "The server."),
                    Field.integer("port", "Port", "The server's port.").range(1, 65535).byDefault(22),
                    Field.text("user", "User", "The login.").required().tab(Tab.SECURITY),
                    Field.secret("password", "Password", "The password, or set privateKey.").tab(Tab.SECURITY),
                    Field.secret("privateKey", "Private key", "The private key, or set password.").tab(Tab.SECURITY),
                    Field.text("hostKey", "Host key", "The server key's fingerprint.").required().pattern("SHA256:[A-Za-z0-9+/]+=*").tab(Tab.SECURITY),
                    Field.choice("afterSuccess", "After success", "What happens to a delivered file.", Choice.of("delete", "Delete"), Choice.of("move", "Move")).byDefault("delete").tab(POLLING).in(),
                    Field.text("moveTo", "Move to", "Where a delivered file goes.").required().visibleWhen("afterSuccess", "move").tab(POLLING).in(),
                    Field.bool("archive", "Archive", "Whether a moved file is also archived.").visibleWhen("afterSuccess", "move").tab(POLLING).in(),
                    Field.text("archiveDir", "Archive dir", "Where archived files go.").required().visibleWhen("archive", true).tab(POLLING).in(),
                    Field.text("url", "Url", "Where a producer sends.").out())
            .exactlyOne("password", "privateKey");

    static final Map<String, Object> VALID = Map.of("user", "rail8", "password", "s3cret", "hostKey", "SHA256:zuCbj2cX0buHOs3U");

    static Map<String, Object> with(Map<String, Object> options, Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>(options);
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    static Map<String, Object> without(Map<String, Object> options, String key) {
        Map<String, Object> m = new LinkedHashMap<>(options);
        m.remove(key);
        return m;
    }

    static List<Problem> problems(Problem... expected) {
        return List.of(expected);
    }

    @Test
    void validOptionsHaveNoProblem() {
        assertEquals(List.of(), DROP.check(VALID, IN));
        assertEquals(List.of(), DROP.check(with(VALID, "host", "sftp", "port", 2222, "afterSuccess", "move", "moveTo", "/done"), IN));
        assertEquals(List.of(), DROP.check(with(without(VALID, "password"), "privateKey", "-----BEGIN KEY-----"), IN));
    }

    @Test
    void aRequiredFieldLeftOutIsNamed() {
        assertEquals(problems(new Problem("/user", Problem.REQUIRED, "is required")), DROP.check(without(VALID, "user"), IN));
    }

    @Test
    void aFieldShownByAnotherIsRequiredOnlyWhileShown() {
        assertEquals(problems(new Problem("/moveTo", Problem.REQUIRED, "is required when afterSuccess is move")), DROP.check(with(VALID, "afterSuccess", "move"), IN));
        assertEquals(List.of(), DROP.check(with(VALID, "afterSuccess", "delete"), IN), "hidden: not required");
        assertEquals(List.of(), DROP.check(VALID, IN), "the controller's default, delete, hides it");
    }

    @Test
    void visibilityIsTransitive() {
        Map<String, Object> archived = with(VALID, "archive", true);
        assertEquals(List.of(), DROP.check(archived, IN), "archive is hidden while afterSuccess is delete, so archiveDir is hidden too");
        assertEquals(problems(new Problem("/archiveDir", Problem.REQUIRED, "is required when archive is true")),
                DROP.check(with(archived, "afterSuccess", "move", "moveTo", "/done"), IN));
    }

    @Test
    void aHiddenFieldMayBeSetButIsStillChecked() {
        assertEquals(List.of(), DROP.check(with(VALID, "moveTo", "/done"), IN), "set while hidden: not refused");
        assertEquals(problems(new Problem("/moveTo", Problem.TYPE, "must be text")), DROP.check(with(VALID, "moveTo", 7), IN));
    }

    @Test
    void anUnknownKeyIsRefused() {
        assertEquals(problems(new Problem("/colour", Problem.UNKNOWN, "no such setting"), new Problem("/pasword", Problem.UNKNOWN, "no such setting")),
                DROP.check(with(VALID, "pasword", "typo", "colour", "red"), IN), "unknown keys in name order");
    }

    @Test
    void aKeyOfTheOtherDirectionIsRefused() {
        assertEquals(problems(new Problem("/url", Problem.DIRECTION, "a setting of out endpoints only")), DROP.check(with(VALID, "url", "http://x"), IN));
        assertEquals(problems(new Problem("/afterSuccess", Problem.DIRECTION, "a setting of in endpoints only")), DROP.check(with(VALID, "afterSuccess", "move"), OUT));
    }

    @Test
    void theKeysTheNodeSetsOnAnInEndpointAreNotTheDescriptionsBusiness() {
        assertEquals(List.of(), DROP.check(with(VALID, "flow", "orders/intake", "maxBodyBytes", 1_000_000), IN));
        assertEquals(problems(new Problem("/flow", Problem.UNKNOWN, "no such setting"), new Problem("/maxBodyBytes", Problem.UNKNOWN, "no such setting")),
                DROP.check(with(VALID, "flow", "orders/intake", "maxBodyBytes", 1_000_000), OUT), "on an out endpoint the node sets neither");
        Settings replies = Settings.of(Field.integer("maxBodyBytes", "Max body bytes", "The reply's limit.").required().min(1).out());
        assertEquals(List.of(), replies.check(Map.of("maxBodyBytes", 1_000_000, "flow", "orders/intake"), IN), "an out-only field of that name is skipped on in");
        assertEquals(problems(new Problem("/maxBodyBytes", Problem.REQUIRED, "is required")), replies.check(Map.of(), OUT));
    }

    @Test
    void exactlyOneOfAGroup() {
        assertEquals(problems(new Problem("", Problem.EXACTLY_ONE, "set exactly one of password, privateKey")), DROP.check(without(VALID, "password"), IN));
        assertEquals(problems(new Problem("", Problem.EXACTLY_ONE, "set exactly one of password, privateKey")), DROP.check(with(VALID, "privateKey", "k"), IN));
    }

    @Test
    void typesAreChecked() {
        assertEquals(problems(new Problem("/host", Problem.TYPE, "must be text")), DROP.check(with(VALID, "host", 7), IN));
        assertEquals(problems(new Problem("/port", Problem.TYPE, "must be a whole number from 1 to 65535")), DROP.check(with(VALID, "port", "22"), IN));
        assertEquals(problems(new Problem("/port", Problem.TYPE, "must be a whole number from 1 to 65535")), DROP.check(with(VALID, "port", 2.5), IN));
        assertEquals(problems(new Problem("/archive", Problem.TYPE, "must be true or false")), DROP.check(with(VALID, "archive", "yes"), IN));
        assertEquals(problems(new Problem("/afterSuccess", Problem.CHOICE, "must be one of delete, move")), DROP.check(with(VALID, "afterSuccess", "copy"), IN));
        assertEquals(problems(new Problem("/password", Problem.TYPE, "must be text")), DROP.check(with(VALID, "password", List.of("s3cret")), IN));
    }

    @Test
    void wholeNumbersAreCheckedAgainstTheirRange() {
        assertEquals(List.of(), DROP.check(with(VALID, "port", 1), IN));
        assertEquals(List.of(), DROP.check(with(VALID, "port", 65535L), IN));
        assertEquals(List.of(), DROP.check(with(VALID, "port", 22.0), IN), "a whole number written with a fraction of zero");
        assertEquals(List.of(), DROP.check(with(VALID, "port", BigInteger.valueOf(22)), IN));
        Map<Object, String> codes = new LinkedHashMap<>();
        codes.put(0, Problem.MIN);
        codes.put(-1, Problem.MIN);
        codes.put(65536, Problem.MAX);
        codes.put(Long.MAX_VALUE, Problem.MAX);
        codes.put(new BigInteger("100000000000000000000"), Problem.TYPE);
        codes.forEach((out, code) -> assertEquals(problems(new Problem("/port", code, "must be a whole number from 1 to 65535")),
                DROP.check(with(VALID, "port", out), IN), "port " + out));
        Settings bounds = Settings.of(Field.integer("least", "Least", "At least 1.").min(1), Field.integer("most", "Most", "At most 9.").max(9),
                Field.integer("any", "Any", "Any whole number."));
        assertEquals(problems(new Problem("/least", Problem.MIN, "must be a whole number of at least 1"), new Problem("/most", Problem.MAX, "must be a whole number of at most 9"),
                new Problem("/any", Problem.TYPE, "must be a whole number")), bounds.check(Map.of("least", 0, "most", 10, "any", 1.5), IN));
    }

    @Test
    void anEmptyStringIsNoValue() {
        assertEquals(problems(new Problem("/host", Problem.TYPE, "must not be empty")), DROP.check(with(VALID, "host", ""), IN));
        assertEquals(problems(new Problem("/user", Problem.TYPE, "must not be empty")), DROP.check(with(VALID, "user", ""), IN));
        assertEquals(problems(new Problem("/password", Problem.TYPE, "must not be empty")), DROP.check(with(VALID, "password", ""), IN));
    }

    @Test
    void nullIsAbsent() {
        Map<String, Object> options = new HashMap<>(VALID);
        options.put("host", null);
        assertEquals(List.of(), DROP.check(options, IN));
        options.put("user", null);
        assertEquals(problems(new Problem("/user", Problem.REQUIRED, "is required")), DROP.check(options, IN));
    }

    @Test
    void aPatternMatchesTheWholeValue() {
        assertEquals(problems(new Problem("/hostKey", Problem.PATTERN, "must match SHA256:[A-Za-z0-9+/]+=*")), DROP.check(with(VALID, "hostKey", "sha256:abc"), IN));
        assertEquals(problems(new Problem("/hostKey", Problem.PATTERN, "must match SHA256:[A-Za-z0-9+/]+=*")), DROP.check(with(VALID, "hostKey", "x SHA256:abc"), IN));
    }

    @Test
    void aSecretReferenceStandsInForAnyTextAndIsResolvedLater() {
        assertEquals(List.of(), DROP.check(with(VALID, "hostKey", "secret://sftp-host-key", "password", "secret://sftp.password_2"), IN),
                "the pattern is not asked of a reference");
        assertEquals(problems(new Problem("/hostKey", Problem.PATTERN, "must match SHA256:[A-Za-z0-9+/]+=*")), DROP.check(with(VALID, "hostKey", "secret://UPPER"), IN),
                "a malformed reference is plain text");
        assertEquals(problems(new Problem("/port", Problem.TYPE, "must be a whole number from 1 to 65535")), DROP.check(with(VALID, "port", "secret://port"), IN),
                "only text and secret settings take a reference");
    }

    @Test
    void problemsComeFieldsFirstThenUnknownKeysThenGroups() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("zebra", 1);
        bad.put("hostKey", "nope");
        bad.put("port", 0);
        assertEquals(problems(new Problem("/port", Problem.MIN, "must be a whole number from 1 to 65535"), new Problem("/user", Problem.REQUIRED, "is required"),
                new Problem("/hostKey", Problem.PATTERN, "must match SHA256:[A-Za-z0-9+/]+=*"), new Problem("/zebra", Problem.UNKNOWN, "no such setting"),
                new Problem("", Problem.EXACTLY_ONE, "set exactly one of password, privateKey")), DROP.check(bad, IN));
    }

    @Test
    void everyProblemCarriesTheStableCodeOfTheRuleItBreaks() {
        Map<String, Map<String, Object>> broken = new LinkedHashMap<>();
        broken.put(Problem.REQUIRED, without(VALID, "user"));
        broken.put(Problem.UNKNOWN, with(VALID, "colour", "red"));
        broken.put(Problem.MIN, with(VALID, "port", 0));
        broken.put(Problem.MAX, with(VALID, "port", 65536));
        broken.put(Problem.PATTERN, with(VALID, "hostKey", "md5:ab"));
        broken.put(Problem.CHOICE, with(VALID, "afterSuccess", "copy"));
        broken.put(Problem.TYPE, with(VALID, "archive", "yes"));
        broken.put(Problem.DIRECTION, with(VALID, "url", "http://x"));
        broken.put(Problem.EXACTLY_ONE, with(VALID, "privateKey", "k"));
        broken.forEach((code, options) -> {
            List<Problem> found = DROP.check(options, IN);
            assertEquals(1, found.size(), code + ": " + found);
            assertEquals(code, found.get(0).code(), found.toString());
        });
        assertEquals(List.of("required", "unknown", "min", "max", "pattern", "choice", "type", "direction", "exactlyOne"), List.copyOf(broken.keySet()),
                "the codes as the UI and the admin API read them");
    }

    @Test
    void aKeyIsAJsonPointer() {
        assertEquals(problems(new Problem("/a~1b~0c", Problem.UNKNOWN, "no such setting")), DROP.check(with(VALID, "a/b~c", 1), IN));
    }

    @Test
    void aDescriptionWithNoFieldsTakesNoOption() {
        assertEquals(List.of(), Settings.of().check(Map.of(), OUT));
        assertEquals(problems(new Problem("/url", Problem.UNKNOWN, "no such setting")), Settings.of().check(Map.of("url", "x"), OUT));
    }

    @Test
    void anUndescribedComponentIsNotJudgedHere() {
        assertEquals(List.of(), Settings.NONE.check(Map.of("anything", 1), IN));
    }
}
