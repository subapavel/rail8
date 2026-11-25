package dev.rail8.runtime.deploy;

import java.util.List;

/**
 * The record of a package's secret uses was not written: a name its flows use is not set, or not of a kind its use takes, by
 * the rows in Postgres as its write saw them. Each problem names the flow, the secret and its source, never a value.
 */
final class SecretUsesRefused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> problems;

    SecretUsesRefused(List<String> problems) {
        super("the secrets the flows use changed since the deploy checked them: " + problems);
        this.problems = List.copyOf(problems);
    }

    /** One line per problem. */
    List<String> problems() {
        return problems;
    }
}
