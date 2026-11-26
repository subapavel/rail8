package dev.rail8.components.http;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The cookies the consumer keeps out of a flow are the ones the UI's server names, no more and no fewer. */
class PlatformCookiesTest {

    /** The UI's server, which names its session's and its device's cookies, from this module's directory. */
    static final Path SESSION_TS = Path.of("../../../ui/server/session.ts");

    /** A quoted name of one of the UI's cookies: {@code rail8-} and a word, with or without {@code __Host-}. */
    static final Pattern COOKIE = Pattern.compile("'((?:__Host-)?rail8-[a-z]+)'");

    @Test
    void theNamesAreTheOnesTheUisServerSets() throws IOException {
        Set<String> inTheUi = COOKIE.matcher(Files.readString(SESSION_TS)).results().map(m -> m.group(1)).collect(Collectors.toSet());
        assertEquals(inTheUi, HttpComponent.PLATFORM_COOKIES, "the cookies named in " + SESSION_TS);
    }
}
