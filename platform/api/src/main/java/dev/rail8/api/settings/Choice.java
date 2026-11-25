package dev.rail8.api.settings;

import java.util.Objects;

/**
 * One value of a {@link Field#choice} and the label the form shows for it.
 *
 * @param value what the setting holds when this choice is taken: 1 to 256 characters
 * @param label what the form shows, in English: 1 to 64 characters
 * @since 0.4
 */
public record Choice(String value, String label) {

    private static final int MAX_VALUE = 256;
    private static final int MAX_LABEL = 64;

    /** @throws IllegalArgumentException naming the value when the value or the label is out of its bounds */
    public Choice {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(label, "label");
        if (value.isEmpty() || value.length() > MAX_VALUE) throw new IllegalArgumentException("a choice's value is 1 to 256 characters");
        if (label.isBlank() || label.length() > MAX_LABEL) throw new IllegalArgumentException("choice '" + value + "': a label of 1 to 64 characters");
    }

    /** The choice {@code value}, shown as {@code label}. */
    public static Choice of(String value, String label) {
        return new Choice(value, label);
    }
}
