package dev.rail8.testkit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The arguments of an ICU message by name and type, sub-messages included; a message that is none is refused. */
class IcuArgumentsTest {

    @Test
    void plainTextAndMarkupHoldNoArgument() {
        assertEquals(Map.of(), IcuArguments.of("Host"));
        assertEquals(Map.of(), IcuArguments.of("<b>Host</b> & more"));
        assertEquals(Map.of(), IcuArguments.of(""));
    }

    @Test
    void eachArgumentHasItsType() {
        assertEquals(Map.of("name", "none", "size", "number", "at", "date", "when", "time"),
                IcuArguments.of("{name} is {size, number, integer} bytes on {at, date, short} at {when,time}"));
    }

    @Test
    void aChoiceHoldsTheArgumentsOfItsSubMessages() {
        assertEquals(Map.of("count", "plural", "dir", "none"),
                IcuArguments.of("{count, plural, offset:1 =0 {no file in {dir}} one {# file} other {# files in {dir}}}"));
        assertEquals(Map.of("mode", "select", "path", "none"), IcuArguments.of("{mode, select, move {to {path}} other {deleted}}"));
        assertEquals(Map.of("n", "selectordinal"), IcuArguments.of("{n, selectordinal, one {#st} two {#nd} few {#rd} other {#th}}"));
    }

    @Test
    void anApostropheQuotesSyntaxAndTwoStandForOne() {
        assertEquals(Map.of(), IcuArguments.of("Use '{name}' literally"));
        assertEquals(Map.of("x", "none"), IcuArguments.of("It''s {x}"));
        assertEquals(Map.of(), IcuArguments.of("l'adresse"));
        assertEquals(Map.of("n", "plural"), IcuArguments.of("{n, plural, other {'#' is # and '{'}}"));
    }

    @Test
    void aMessageThatIsNoneIsRefused() {
        for (String broken : List.of("{", "{name", "}", "a } b", "{}", "{name, unknown}", "{n, plural, one {x}}", "{n, plural, other {x}",
                "{n, plural, other x}", "{n, select}", "{n, number, {x}}", "{n, plural, offset: other {x}}", "{n, plural, *x {a} other {b}}",
                "{a {b}}", "{n plural}")) {
            assertThrows(IllegalArgumentException.class, () -> IcuArguments.of(broken), broken);
        }
    }

    @Test
    void nestingDeeperThan32IsRefused() {
        String deep = "{n, select, other {".repeat(33) + "x" + "}}".repeat(33);
        assertThrows(IllegalArgumentException.class, () -> IcuArguments.of(deep));
        String fine = "{n, select, other {".repeat(32) + "x" + "}}".repeat(32);
        assertEquals(Map.of("n", "select"), IcuArguments.of(fine));
    }
}
