package dev.rail8.core.flow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces secret values with their references in the texts the platform itself writes about a flow: a problem, a refusal, an
 * audit detail, a log line. It replaces the same forms as a flow's own scrubber: a plain value as text, URL-encoded and
 * JSON-escaped; credentials by their password in those forms and by the base64 of {@code user:password}; a key pair by the
 * base64 of its private key, whole and in each 64-character line. Longer forms are replaced first, in one pass over the text.
 */
public final class Scrubber {

    private final Map<String, String> forms;

    private Scrubber(Map<String, String> forms) {
        this.forms = forms;
    }

    /**
     * The scrubber of {@code values}: by the text each form is replaced with (a reference such as {@code secret://<name>}), the
     * values of that reference: strings, {@link dev.rail8.api.Secret.Credentials} and {@link dev.rail8.api.Secret.KeyPair}s;
     * anything else is ignored. Of two references with an equal form, the first one {@code values} iterates names it.
     */
    public static Scrubber of(Map<String, ? extends Collection<?>> values) {
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        values.forEach((reference, each) -> each.forEach(value -> {
            if (value != null) FlowSecrets.forms(value, reference, pairs);
        }));
        return new Scrubber(FlowSecrets.ordered(pairs));
    }

    /** {@code text} with every form replaced by its reference; null stays null. */
    public String text(String text) {
        return FlowSecrets.replaced(text, forms);
    }

    /** Each of {@code texts} as {@link #text} gives it. */
    public List<String> texts(List<String> texts) {
        return texts.stream().map(this::text).toList();
    }

    /**
     * {@code failure} itself when no message of it, its causes or its suppressed failures holds a form; otherwise a copy, to log
     * or to show, in which every one of those messages has its forms replaced, each link naming the class it copies; null stays
     * null.
     */
    public Throwable failure(Throwable failure) {
        if (failure == null || forms.isEmpty() || !FlowSecrets.holds(failure, forms, Collections.newSetFromMap(new IdentityHashMap<>()))) return failure;
        return FlowSecrets.copy(failure, forms, new IdentityHashMap<>());
    }
}
