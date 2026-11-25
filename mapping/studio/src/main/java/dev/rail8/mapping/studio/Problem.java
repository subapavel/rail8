package dev.rail8.mapping.studio;

import java.util.Map;

/**
 * The RFC 9457 body of a refused call.
 *
 * @param type {@code urn:rail8:mapping:problem:<slug>}
 * @param title the slug's fixed summary
 * @param status the HTTP status, fixed by the slug
 * @param detail what was wrong with this request
 * @param code the slug of {@code type}
 * @param values the values {@code detail} names, by name; none for the studio's own problems
 */
record Problem(
    String type,
    String title,
    int status,
    String detail,
    String code,
    Map<String, Object> values) {}
