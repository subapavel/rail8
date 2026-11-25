package dev.rail8.api.settings;

/**
 * How a template in a text field of the flow form is escaped where it is put: {@code PATH} as a path segment of a URL,
 * {@code QUERY} as a query parameter's value, {@code NAME} as a file name, which may not name a directory, and
 * {@code NONE} not at all. A field takes a template only when {@link Field#expression} marks it.
 *
 * @since 0.4
 */
public enum Escape {
    PATH, QUERY, NAME, NONE
}
