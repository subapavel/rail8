package dev.rail8.api.plugin;

import dev.rail8.api.Presented;
import dev.rail8.api.Refused;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The defaults of a port that is no node's ({@link HttpIngress#NONE}): it reads nothing presented and answers no refusal. */
class HttpIngressTest {

    @Test
    void aPortThatIsNoNodesPresentsNothingAndAnswersNoRefusal() {
        assertEquals(new Presented.Nothing(Presented.Via.of("", false)), HttpIngress.NONE.presented(null));
        assertThrows(IllegalStateException.class, () -> HttpIngress.NONE.refuse(null, new Refused(Refused.Reason.UNKNOWN, Optional.empty())));
    }
}
