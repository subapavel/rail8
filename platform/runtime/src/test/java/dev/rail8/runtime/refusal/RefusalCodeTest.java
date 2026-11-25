package dev.rail8.runtime.refusal;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefusalCodeTest {

    /** Lower-case words of letters and digits joined by single hyphens. */
    private static final String KEBAB = "[a-z0-9]+(-[a-z0-9]+)*";

    @Test
    void everyCodeIsKebabCase() {
        for (RefusalCode code : RefusalCode.values()) {
            assertTrue(code.code().matches(KEBAB), code.name() + " is " + code.code());
        }
    }

    @Test
    void noTwoRefusalsShareACode() {
        List<String> codes = Arrays.stream(RefusalCode.values()).map(RefusalCode::code).toList();
        Set<String> seen = new HashSet<>();
        List<String> twice = codes.stream().filter(code -> !seen.add(code)).toList();
        assertEquals(List.of(), twice);
    }

    @Test
    void aCodeIsWrittenAsItsText() {
        assertEquals("no-credential", RefusalCode.NO_CREDENTIAL.toString());
    }
}
