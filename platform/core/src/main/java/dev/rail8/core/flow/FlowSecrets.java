package dev.rail8.core.flow;

import dev.rail8.api.Cache;
import dev.rail8.api.Fault;
import dev.rail8.api.NackException;
import dev.rail8.api.Pipeline;
import dev.rail8.api.Secret;
import dev.rail8.api.Services;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.core.node.FencedException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

/**
 * The secrets of one build of a flow. While the flow builds, every whole string {@code secret://...} in a step's config is
 * checked ({@link #check}), and a factory takes the secrets its options name through {@link #services} (the reference is
 * resolved now, so one that is not set, or of a kind the factory does not take, fails the build). Once the flow has built
 * ({@link #built()}) no secret is handed out. A stage wrapped by {@link #scrubbing} throws its own failures with every value
 * handed out in the flow replaced by its reference, in each message of the failure, its causes and its suppressed failures.
 * {@link #scrubber} does the same to a text.
 *
 * <p>What it replaces: each handle's current value, and up to {@value #EARLIER_PER_HANDLE} earlier values of each handle that a
 * read saw before the value changed, within {@value #EARLIER_BYTES} bytes of earlier values for the flow, the oldest dropped
 * first; a current value is never dropped. A plain value is replaced as text, URL-encoded and JSON-escaped; credentials by
 * their password in the same three forms and by the base64 of {@code user:password}; a key pair by the base64 of its private
 * key's PKCS#8 bytes, whole and in each 64-character line of its PEM. The plain values the flow's endpoints hold in their
 * options ({@link #FlowSecrets(SecretSource, Map)}) join them. Longer texts are replaced first, in one pass over the text.
 */
final class FlowSecrets {

    /** The earlier values kept of one handle. */
    static final int EARLIER_PER_HANDLE = 7;
    /** The earlier values kept of one flow together, in bytes. */
    static final long EARLIER_BYTES = 256 * 1024;

    private static final String SCHEME = "secret://";
    private static final Set<Secret.Kind> EVERY_KIND = Collections.unmodifiableSet(EnumSet.allOf(Secret.Kind.class));

    private final SecretSource source;
    /** The plain values the flow's endpoints hold, by reference. */
    private final Map<String, String> held;
    private final List<Handle> handedOut = new CopyOnWriteArrayList<>();
    /** Every earlier value kept, the oldest first; guarded by itself. */
    private final Deque<Earlier> earlier = new ArrayDeque<>();
    private long earlierBytes;   // guarded by earlier
    private volatile boolean built;

    /**
     * Secrets handed out by {@code source}, which refuses with an {@link IllegalArgumentException} naming the reference and where
     * the node looks for it, never a value; {@code held} are the plain values, by reference, that the flow's endpoints hold in
     * their options, which the scrubber replaces too.
     */
    FlowSecrets(SecretSource source, Map<String, String> held) {
        this.source = Objects.requireNonNull(source, "source");
        this.held = Map.copyOf(held);
    }

    /**
     * Resolves every string of {@code step}'s config, at any depth of its maps and lists, that starts with {@code secret://}.
     *
     * @throws IllegalArgumentException naming the step, the option's path and the source's refusal
     */
    void check(StepConfig step) {
        step.config().forEach((key, value) -> check(step, key, value));
    }

    private void check(StepConfig step, String path, Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> check(step, path + "." + key, item));
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) check(step, path + "[" + i + "]", list.get(i));
        } else if (value instanceof String reference && reference.startsWith(SCHEME)) {
            try {
                source.handle(reference, EVERY_KIND);
            } catch (IllegalArgumentException refused) {
                throw new IllegalArgumentException(step + ": '" + path + "': " + refused.getMessage(), refused);
            }
        }
    }

    /** The services of {@code step}: its secrets from this flow's source, and {@code cache}. */
    Services services(StepConfig step, Cache cache) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(cache, "cache");
        return new Services() {
            @Override
            public Secret secret(StepConfig of, String key, Secret.Kind... kinds) {
                return FlowSecrets.this.secret(of, key, kinds);
            }

            @Override
            public Cache cache() {
                return cache;
            }

            @Override
            public String toString() {
                return "Services[" + step + "]";
            }
        };
    }

    /** Ends the build: from now on {@link Services#secret} refuses. */
    void built() {
        built = true;
    }

    private Secret secret(StepConfig step, String key, Secret.Kind... kinds) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kinds, "kinds");
        if (built) throw new IllegalStateException(step + ": '" + key + "': a secret is taken while the flow builds, in StepFactory.create");
        if (kinds.length == 0) throw new IllegalArgumentException(step + ": '" + key + "': no kind of secret is asked for");
        String reference = step.requireString(key);
        if (!reference.startsWith(SCHEME)) {
            throw new IllegalArgumentException(step + ": '" + key + "' must name a secret, secret://<name>; its value is not shown");
        }
        Secret inner;
        try {
            inner = source.handle(reference, EnumSet.copyOf(Arrays.asList(kinds)));
        } catch (IllegalArgumentException refused) {
            throw new IllegalArgumentException(step + ": '" + key + "': " + refused.getMessage(), refused);
        }
        Handle handle = new Handle(inner);
        handedOut.add(handle);
        return switch (inner.kind()) {
            case VALUE -> Secret.of(reference, () -> handle.read(inner::value));
            case CREDENTIALS -> Secret.credentials(reference, () -> handle.read(inner::credentials));
            case KEY_PAIR -> Secret.keyPair(reference, () -> handle.read(inner::keyPair));
        };
    }

    /** A handle handed out: its source's handle and the last value a read of it saw. */
    private final class Handle {
        private final Secret inner;
        private Object current;   // guarded by earlier
        private int kept;         // the earlier values kept of it; guarded by earlier

        Handle(Secret inner) {
            this.inner = inner;
        }

        /** The value {@code read} gives now, seen: a value other than the last one seen keeps the last one as an earlier value. */
        <T> T read(java.util.function.Supplier<T> read) {
            T value = read.get();
            saw(value);
            return value;
        }

        /** The value now, seen; null when the source has none or cannot answer. */
        Object now() {
            try {
                Object value = switch (inner.kind()) {
                    case VALUE -> inner.value();
                    case CREDENTIALS -> inner.credentials();
                    case KEY_PAIR -> inner.keyPair();
                };
                saw(value);
                return value;
            } catch (RuntimeException unreadable) {
                return null;
            }
        }

        private void saw(Object value) {
            if (value == null) return;
            synchronized (earlier) {
                if (current != null && !current.equals(value)) keep(this, current);
                current = value;
            }
        }
    }

    /** An earlier value of {@code handle}, and its size counted against {@link #EARLIER_BYTES}. */
    private record Earlier(Handle handle, Object value, long bytes) { }

    /** Keeps {@code value} as an earlier value of {@code handle}: within the limits, the oldest dropped first. Under {@link #earlier}. */
    private void keep(Handle handle, Object value) {
        Earlier e = new Earlier(handle, value, bytes(value));
        earlier.addLast(e);
        earlierBytes += e.bytes();
        handle.kept++;
        if (handle.kept > EARLIER_PER_HANDLE) {
            for (java.util.Iterator<Earlier> it = earlier.iterator(); it.hasNext(); ) {
                Earlier old = it.next();
                if (old.handle() == handle) {
                    drop(it, old);
                    break;
                }
            }
        }
        while (earlierBytes > EARLIER_BYTES && !earlier.isEmpty()) {
            java.util.Iterator<Earlier> it = earlier.iterator();
            drop(it, it.next());
        }
    }

    private void drop(java.util.Iterator<Earlier> at, Earlier e) {
        at.remove();
        earlierBytes -= e.bytes();
        e.handle().kept--;
    }

    private static long bytes(Object value) {
        return switch (value) {
            case String s -> s.getBytes(StandardCharsets.UTF_8).length;
            case Secret.Credentials c -> c.user().getBytes(StandardCharsets.UTF_8).length + (long) c.password().getBytes(StandardCharsets.UTF_8).length;
            case Secret.KeyPair k -> k.privateKey().getEncoded() == null ? 0 : k.privateKey().getEncoded().length;
            default -> 0;
        };
    }

    /**
     * {@code stage}, whose own failures leave with every value handed out in this flow replaced by its reference. A failure
     * that came out of the rest of the pipeline leaves as it came: the step it came from scrubbed it. A failure that holds no
     * value leaves as thrown, and so does an {@link Error}, an {@link InterruptedException}, a {@link FencedException} and a
     * {@link RunStoppedException}. Otherwise the failure is a copy: a {@link NackException} stays one with the same retry
     * answer, a {@link Fault} keeps its kind, any other failure stays unclassified, and every link of its causes and
     * suppressed failures is copied the same way, each with its class name shown and its stack frames kept.
     */
    Stage scrubbing(Stage stage) {
        Objects.requireNonNull(stage, "stage");
        return (e, rest) -> {
            if (handedOut.isEmpty() && held.isEmpty()) return stage.apply(e, rest);
            Set<Throwable> passedUp = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
            Pipeline watched = out -> {
                try {
                    return rest.apply(out);
                } catch (Throwable failure) {
                    passedUp.add(failure);
                    throw failure;
                }
            };
            try {
                return stage.apply(e, watched);
            } catch (Throwable failure) {
                if (passedUp.contains(failure)) throw failure;
                throw FlowSecrets.<RuntimeException>undeclared(scrubbed(failure));
            }
        };
    }

    /** {@code failure}, or its copy with every value handed out replaced by its reference when it holds one. */
    Throwable scrubbed(Throwable failure) {
        if (failure instanceof Error || failure instanceof InterruptedException || failure instanceof FencedException
                || failure instanceof RunStoppedException) {
            return failure;
        }
        Map<String, String> values = values();
        if (values.isEmpty() || !holds(failure, values, Collections.newSetFromMap(new IdentityHashMap<>()))) return failure;
        return copy(failure, values, new IdentityHashMap<>());
    }

    /**
     * The text with every value handed out in this flow replaced by its reference, the longest value first; the values are
     * read once, now. Without a value handed out it is the identity.
     */
    UnaryOperator<String> scrubber() {
        if (handedOut.isEmpty() && held.isEmpty()) return UnaryOperator.identity();
        Map<String, String> values = values();
        return text -> replaced(text, values);
    }

    /**
     * Each form of each value to replace, longest first, to its reference: the current value of each handle read now, the
     * earlier values kept and the values the endpoints hold; a handle that cannot be read now gives only its earlier values.
     */
    private Map<String, String> values() {
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        held.forEach((reference, value) -> forms(value, reference, pairs));
        for (Handle h : handedOut) {
            Object now = h.now();
            if (now != null) forms(now, h.inner.reference(), pairs);
        }
        synchronized (earlier) {
            for (Earlier e : earlier) forms(e.value(), e.handle().inner.reference(), pairs);
        }
        pairs.sort(Comparator.comparingInt((Map.Entry<String, String> p) -> p.getKey().length()).reversed());
        Map<String, String> values = new LinkedHashMap<>();
        pairs.forEach(p -> values.putIfAbsent(p.getKey(), p.getValue()));
        return values;
    }

    /** The forms of {@code value} the scrubber replaces, each paired with {@code reference}; an empty form is left out. */
    static void forms(Object value, String reference, List<Map.Entry<String, String>> into) {
        switch (value) {
            case String s -> plain(s, reference, into);
            case Secret.Credentials c -> {
                plain(c.password(), reference, into);
                add(Base64.getEncoder().encodeToString((c.user() + ":" + c.password()).getBytes(StandardCharsets.UTF_8)), reference, into);
            }
            case Secret.KeyPair k -> {
                byte[] encoded = k.privateKey().getEncoded();
                if (encoded == null) return;
                String base64 = Base64.getEncoder().encodeToString(encoded);
                add(base64, reference, into);
                for (int i = 0; i < base64.length(); i += 64) add(base64.substring(i, Math.min(base64.length(), i + 64)), reference, into);
            }
            default -> { }
        }
    }

    private static void plain(String value, String reference, List<Map.Entry<String, String>> into) {
        add(value, reference, into);
        add(URLEncoder.encode(value, StandardCharsets.UTF_8), reference, into);
        add(jsonEscaped(value), reference, into);
    }

    private static void add(String form, String reference, List<Map.Entry<String, String>> into) {
        if (!form.isEmpty()) into.add(Map.entry(form, reference));
    }

    /** {@code value} as the inside of a JSON string: a quote, a backslash and every control character escaped. */
    static String jsonEscaped(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static boolean holds(Throwable t, Map<String, String> values, Set<Throwable> seen) {
        if (t == null || !seen.add(t)) return false;
        String text = t.toString();
        for (String value : values.keySet()) if (text.contains(value)) return true;
        if (holds(t.getCause(), values, seen)) return true;
        for (Throwable s : t.getSuppressed()) if (holds(s, values, seen)) return true;
        return false;
    }

    private static Throwable copy(Throwable t, Map<String, String> values, Map<Throwable, Throwable> copies) {
        if (t == null) return null;
        Throwable done = copies.get(t);
        if (done != null) return done;
        Throwable copy;
        if (t instanceof NackException nack) {
            copy = new NackException(replaced(nack.reason(), values), nack.retryable());
        } else if (t instanceof Fault fault) {
            copy = new ScrubbedFault(t.getClass().getName(), replaced(t.getLocalizedMessage(), values), fault.kind());
        } else {
            copy = new ScrubbedException(t.getClass().getName(), replaced(t.getLocalizedMessage(), values));
        }
        copies.put(t, copy);
        copy.setStackTrace(t.getStackTrace());
        Throwable cause = copy(t.getCause(), values, copies);
        if (cause != null) copy.initCause(cause);
        for (Throwable s : t.getSuppressed()) copy.addSuppressed(copy(s, values, copies));
        return copy;
    }

    /**
     * {@code text} with each form of {@code values} replaced by its reference, in one pass over the original text: at each
     * position the longest form that starts there is replaced, and a reference put in is never searched again.
     */
    private static String replaced(String text, Map<String, String> values) {
        if (text == null || values.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        outer:
        while (i < text.length()) {
            for (Map.Entry<String, String> v : values.entrySet()) {   // longest first
                if (text.startsWith(v.getKey(), i)) {
                    out.append(v.getValue());
                    i += v.getKey().length();
                    continue outer;
                }
            }
            out.append(text.charAt(i++));
        }
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T undeclared(Throwable failure) throws T {
        throw (T) failure;
    }

    /** A copy of an unclassified failure: its class name and its message, values replaced. */
    static class ScrubbedException extends RuntimeException {
        private final String type;

        ScrubbedException(String type, String message) {
            super(message);
            this.type = type;
        }

        /** As the failure's own: its class name, then its message. */
        @Override
        public String toString() {
            String message = getLocalizedMessage();
            return message == null ? type : type + ": " + message;
        }
    }

    /** A copy of a {@link Fault}: its kind kept. */
    static final class ScrubbedFault extends ScrubbedException implements Fault {
        private final Kind kind;

        ScrubbedFault(String type, String message, Kind kind) {
            super(type, message);
            this.kind = kind;
        }

        @Override
        public Kind kind() {
            return kind;
        }
    }
}
