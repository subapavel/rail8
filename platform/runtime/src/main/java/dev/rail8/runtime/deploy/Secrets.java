package dev.rail8.runtime.deploy;

import dev.rail8.api.Secret;
import dev.rail8.runtime.secret.SecretStore;
import dev.rail8.runtime.secret.StoreKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Resolves a secret reference, {@code secret://<name>}, the name {@code [a-z0-9][a-z0-9._-]*} of at most 128 characters.
 *
 * <p>On a node ({@link #fromEnv}, then {@link #withStore}) the secret store is the only source: a name reads its row, as this
 * node opened it; a row it could not open counts as set but unreadable and gives no value; a name the store does not hold is not
 * set. Nothing here asks Postgres: the store answers from the node's memory. The node's {@code RAIL8_SECRET_<NAME>} variables,
 * the name upper-cased, {@code .} and {@code -} as {@code _}, are kept only to be imported into the store, once
 * ({@link #importable}); a variable set to the empty string counts as not set. Off the node ({@link #placeholder}: {@code rail8 validate},
 * {@code rail8 test}) every well-formed reference reads a placeholder of its kind: a plain value {@code https://secret.invalid/},
 * credentials user {@code rail8} and password {@code invalid}, and a key pair the testkit's EC secp256r1 pair named invalid.
 *
 * <p>Every method refuses a malformed reference, and one under {@code rail8.}, which belongs to the platform, with an
 * {@link IllegalArgumentException} naming the reference. No text of this class carries a value.
 *
 * @since 0.1
 */
public final class Secrets {

    /** What a reference starts with. */
    static final String SCHEME = "secret://";
    /** What {@link #placeholder()} resolves every well-formed reference of a plain value to: a URL, so a url option validates. */
    static final String PLACEHOLDER = "https://secret.invalid/";
    /** The placeholder credentials. */
    static final Secret.Credentials PLACEHOLDER_CREDENTIALS = new Secret.Credentials("rail8", "invalid");

    private static final String VARIABLE = "RAIL8_SECRET_";
    private static final int MAX_NAME = 128;
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Set<Secret.Kind> VALUE = EnumSet.of(Secret.Kind.VALUE);
    private static final Secrets PLACEHOLDERS = new Secrets(null, StoreKey.NONE, null);

    /** The {@code RAIL8_SECRET_*} variables by name, which only an import reads; null for the placeholder. */
    private final Map<String, String> variables;
    private final StoreKey key;
    /** The node's store; null before {@link #withStore}, and for the placeholder. */
    private final SecretStore store;

    private Secrets(Map<String, String> variables, StoreKey key, SecretStore store) {
        this.variables = variables;
        this.key = key;
        this.store = store;
    }

    /**
     * The node's: every {@code RAIL8_SECRET_*} variable of {@code env}, read now, to be imported, and the secret store's key;
     * nothing else of it is kept. The store's rows join with {@link #withStore}.
     *
     * @throws IllegalArgumentException the store's key is malformed; the message names the variable, never a value
     */
    public static Secrets fromEnv(Map<String, String> env) {
        Map<String, String> variables = new HashMap<>();
        env.forEach((key, value) -> {
            if (key.startsWith(VARIABLE)) variables.put(key, value);
        });
        return new Secrets(Map.copyOf(variables), StoreKey.fromEnv(env), null);
    }

    /** The dry build's: syntax checked, every well-formed reference read as its kind's placeholder, no env read. */
    public static Secrets placeholder() {
        return PLACEHOLDERS;
    }

    /** These variables and key, with {@code store}, which every read answers from. */
    public Secrets withStore(SecretStore store) {
        if (variables == null) throw new IllegalStateException("the placeholder reads no store");
        return new Secrets(variables, key, Objects.requireNonNull(store, "store"));
    }

    /** The secret store's key the node was started with; {@link StoreKey#NONE} without one. */
    public StoreKey key() {
        return key;
    }

    /**
     * The names of the {@code RAIL8_SECRET_*} variables set to a value that are the variable of a name of {@code everHeld}: no
     * longer read, since the store holds or held that name. Names only, sorted.
     */
    public List<String> importedVariables(Set<String> everHeld) {
        if (variables == null) return List.of();
        Set<String> of = new TreeSet<>();
        everHeld.forEach(name -> of.add(SecretStore.variable(name)));
        return variables.entrySet().stream().filter(e -> !e.getValue().isEmpty() && of.contains(e.getKey())).map(Map.Entry::getKey).sorted().toList();
    }

    /**
     * The value of {@code reference}'s variable, for its import into the store: empty when the variable is not set, for the
     * placeholder, and once the store holds the name, which no variable then changes.
     *
     * @throws IllegalArgumentException {@code reference} is malformed or under {@code rail8.}; the message names the reference
     */
    public Optional<String> importable(String reference) {
        String name = name(reference);
        if (variables == null || store == null || store.held(name).isPresent()) return Optional.empty();
        String value = variables.get(SecretStore.variable(name));
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** The node's store, once joined. */
    public Optional<SecretStore> store() {
        return Optional.ofNullable(store);
    }

    /**
     * The plain value of {@code reference} now: the store's row of kind {@code value}; empty when the store does not hold it,
     * its row does not open, or its row is of another kind.
     *
     * @throws IllegalArgumentException {@code reference} is malformed or under {@code rail8.}; the message names the reference
     */
    public Optional<String> resolve(String reference) {
        String name = name(reference);
        if (variables == null) return Optional.of(PLACEHOLDER);
        return Optional.ofNullable((String) read(name, Secret.Kind.VALUE));
    }

    /**
     * The values the scrubber of the platform's own texts replaces for {@code references}, by reference: the value of each that
     * the store holds readable now, of whatever kind, and else the value of its variable a deploy would import ({@link #importable});
     * none for a malformed reference, and none for the placeholder.
     */
    public Map<String, List<Object>> scrubbed(Collection<String> references) {
        Map<String, List<Object>> values = new LinkedHashMap<>();
        if (variables == null) return values;
        for (String reference : references) {
            String name;
            try {
                name = name(reference);
            } catch (IllegalArgumentException malformed) {
                continue;
            }
            List<Object> each = new ArrayList<>();
            if (store != null) store.held(name).map(SecretStore.Held::value).ifPresent(each::add);
            if (each.isEmpty()) importable(reference).ifPresent(each::add);
            if (!each.isEmpty()) values.put(reference, each);
        }
        return values;
    }

    /**
     * Why {@code reference} cannot be read as one of {@code kinds} now, naming the reference, never a value; empty when it can.
     *
     * @throws IllegalArgumentException {@code reference} is malformed or under {@code rail8.}; the message names the reference
     */
    public Optional<String> problem(String reference, Set<Secret.Kind> kinds) {
        String name = name(reference);
        if (variables == null) return Optional.empty();
        Optional<SecretStore.Held> held = store == null ? Optional.empty() : store.held(name);
        if (held.isPresent()) {
            SecretStore.Held h = held.get();
            if (!kinds.contains(h.kind())) {
                return Optional.of("secret '" + reference + "' is a secret of kind " + h.kind().id() + " in the secret store, taken here as "
                        + ids(kinds));
            }
            if (!h.readable()) return Optional.of("secret '" + reference + "' does not open on this node: it is sealed under the key " + h.keyId());
            return Optional.empty();
        }
        return Optional.of("secret '" + reference + "' is not set: the secret store holds no such secret");
    }

    /**
     * A live handle of {@code reference}, a secret of one of {@code kinds}: each read follows the store's row now, no value once
     * it is gone.
     *
     * @throws IllegalArgumentException {@code reference} is malformed, under {@code rail8.}, not set, unreadable or of a kind
     *                                  {@code kinds} does not name; the message names the reference
     */
    public Secret handle(String reference, Set<Secret.Kind> kinds) {
        String name = name(reference);
        if (kinds.isEmpty()) throw new IllegalArgumentException("no kind of secret is asked for " + reference);
        if (variables == null) {
            if (kinds.contains(Secret.Kind.VALUE)) return Secret.of(reference, () -> PLACEHOLDER);
            if (kinds.contains(Secret.Kind.CREDENTIALS)) return Secret.credentials(reference, () -> PLACEHOLDER_CREDENTIALS);
            Secret.KeyPair pair = SecretStore.placeholderKeyPair();
            return Secret.keyPair(reference, () -> pair);
        }
        Optional<String> problem = problem(reference, kinds);
        if (problem.isPresent()) throw new IllegalArgumentException(problem.get());
        Secret.Kind kind = store.held(name).orElseThrow().kind();
        return switch (kind) {
            case VALUE -> Secret.of(reference, () -> (String) read(name, Secret.Kind.VALUE));
            case CREDENTIALS -> Secret.credentials(reference, () -> (Secret.Credentials) read(name, Secret.Kind.CREDENTIALS));
            case KEY_PAIR -> Secret.keyPair(reference, () -> (Secret.KeyPair) read(name, Secret.Kind.KEY_PAIR));
        };
    }

    /** A plain value's handle of {@code reference}: {@link #handle} of the kind {@code value}. */
    public Secret handle(String reference) {
        return handle(reference, VALUE);
    }

    /** The value of {@code name} as {@code kind} now: the store's readable row of that kind, else null. */
    private Object read(String name, Secret.Kind kind) {
        Optional<SecretStore.Held> held = store == null ? Optional.empty() : store.held(name);
        return held.isPresent() && held.get().kind() == kind ? held.get().value() : null;
    }

    /**
     * The name of {@code reference}.
     *
     * @throws IllegalArgumentException it is not {@code secret://} and a name, or the name is under {@code rail8.}
     */
    static String name(String reference) {
        String name = reference.startsWith(SCHEME) ? reference.substring(SCHEME.length()) : "";
        if (!NAME.matcher(name).matches() || name.length() > MAX_NAME) {
            throw new IllegalArgumentException("'" + reference + "' is not a secret reference: secret://<name>, the name [a-z0-9][a-z0-9._-]*"
                    + " of at most " + MAX_NAME + " characters");
        }
        if (name.startsWith(SecretStore.RESERVED)) {
            throw new IllegalArgumentException("'" + reference + "' names a secret of the platform: no flow, endpoint or step references a name under "
                    + SecretStore.RESERVED);
        }
        return name;
    }

    /**
     * The environment variable {@code reference} names: {@code RAIL8_SECRET_} and its name upper-cased, {@code .} and {@code -}
     * as {@code _}.
     *
     * @throws IllegalArgumentException {@code reference} is malformed or under {@code rail8.}; the message names the reference
     */
    static String variable(String reference) {
        return SecretStore.variable(name(reference));
    }

    private static List<String> ids(Set<Secret.Kind> kinds) {
        return new TreeSet<>(kinds).stream().map(Secret.Kind::id).toList();
    }

    /** Equal when both are the placeholder, or both hold the same variables, key and store. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Secrets s && Objects.equals(variables, s.variables) && key.equals(s.key) && store == s.store;
    }

    @Override
    public int hashCode() {
        return Objects.hash(variables, key);
    }

    /** The names of the variables held, the store's key ids when it has a key, never a value. */
    @Override
    public String toString() {
        return variables == null ? "Secrets[placeholder " + PLACEHOLDER + "]"
                : "Secrets[env " + new TreeSet<>(variables.keySet()) + (key.present() ? ", " + key : "") + (store == null ? "" : ", store") + "]";
    }
}
