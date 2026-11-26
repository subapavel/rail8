package dev.rail8.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One step of a kind's chain of upgrades: it reads a body of format {@link #from()} and answers the same body in format
 * {@code from() + 1}. A step is pure: it reads nothing but its argument, changes nothing outside its answer, and gives the same
 * answer every time. The chain reads files of an import and stored bodies alike, so a node reads every format of a kind up to
 * its own.
 *
 * @since 0.4
 */
public interface FormatUpgrade {

    /** The format this step reads. */
    int from();

    /** {@code body}, a body of format {@link #from()}, in format {@code from() + 1}; {@code body} is not changed. */
    JsonNode apply(JsonNode body);
}
