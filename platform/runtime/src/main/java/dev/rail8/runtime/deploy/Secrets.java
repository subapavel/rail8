package dev.rail8.runtime.deploy;

import dev.rail8.api.Secret;
import dev.rail8.runtime.secret.SecretStore;
import dev.rail8.runtime.secret.StoreKey;

import java.util.Collection;
import java.util.EnumSet;
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
 * <p>On a node ({@link #fromEnv}, then {@link #withStore}) the secret store is the only source: a name the node's store holds
 * reads its row, as this node opened it; a row it could not open counts as set but unreadable and gives no value; a name it
 * does not hold is not set. No variable is read here: the node's {@code RAIL8_SECRET_*} variables are only the
 * {@link SecretStore.Import} of a store never written. Nothing here asks Postgres: the store answers from the node's memory.
 * Off the node ({@link #placeholder}: {@code rail8 validate}, {@code rail8 test}) every well-formed reference reads a
 * placeholder of its kind: a plain value {@code https://secret.invalid/}, credentials user {@code rail8} and password
 * {@code invalid}, and a key pair the testkit's EC secp256r1 pair named invalid. A node's resolver never reads the placeholder.
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

    private static final int MAX_NAME = 128;
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Set<Secret.Kind> VALUE = EnumSet.of(Secret.Kind.VALUE);
    private static final Secrets PLACEHOLDERS = new Secrets(true, null, SecretStore.Import.none(), null);

    /** Whether this is the dry build's placeholder, which reads every reference as its kind's placeholder. */
    private final boolean placeholder;
    /** The secret store's key the node was started with; null without one, and for the placeholder. */
    private final StoreKey key;
    private final SecretStore.Import imports;
    /** The node's store; null before {@link #withStore}, and for the placeholder. */
    private final SecretStore store;

    private Secrets(boolean placeholder, StoreKey key, SecretStore.Import imports, SecretStore store) {
        this.placeholder = placeholder;
        this.key = key;
        this.imports = imports;
        this.store = store;
    }

    /**
     * The node's: the secret store's key of {@code env}, and the {@link SecretStore.Import} of its variables, read now; nothing
     * else of it is kept. Until {@link #withStore} no reference is set.
     *
     * @throws IllegalArgumentException the store's key is malformed; the message names the variable, never a value
     */
    public static Secrets fromEnv(Map<String, String> env) {
        return of(StoreKey.fromEnv(env), SecretStore.Import.fromEnv(env));
    }

    /** The node's: the secret store's {@code key}, and the variables {@code imports} the join of a store never written imports. */
    public static Secrets of(Optional<StoreKey> key, SecretStore.Import imports) {
        return new Secrets(false, key.orElse(null), Objects.requireNonNull(imports, "imports"), null);
    }

    /** The dry build's: syntax checked, every well-formed reference read as its kind's placeholder, no env read. */
    public static Secrets placeholder() {
        return PLACEHOLDERS;
    }

    /** This node's resolver over the rows of {@code store} alone. */
    public Secrets withStore(SecretStore store) {
        if (placeholder) throw new IllegalStateException("the placeholder reads no store");
        return new Secrets(false, key, imports, Objects.requireNonNull(store, "store"));
    }

    /** The secret store's key the node was started with; empty without one. */
    public Optional<StoreKey> key() {
        return Optional.ofNullable(key);
    }

    /** The variables the join of a store never written imports. */
    public SecretStore.Import imports() {
        return imports;
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
        if (placeholder) return Optional.of(PLACEHOLDER);
        return Optional.ofNullable((String) read(name, Secret.Kind.VALUE));
    }

    /**
     * The values the scrubber of the platform's own texts replaces for {@code references}, by reference: the value of each that
     * the store holds readable now, of whatever kind; none for a malformed reference, and none for the placeholder.
     */
    public Map<String, List<Object>> scrubbed(Collection<String> references) {
        Map<String, List<Object>> values = new LinkedHashMap<>();
        if (store == null) return values;
        for (String reference : references) {
            String name;
            try {
                name = name(reference);
            } catch (IllegalArgumentException malformed) {
                continue;
            }
            store.held(name).map(SecretStore.Held::value).ifPresent(v -> values.put(reference, List.of(v)));
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
        if (placeholder) return Optional.empty();
        Optional<SecretStore.Held> held = store == null ? Optional.empty() : store.held(name);
        if (held.isEmpty()) return Optional.of("secret '" + reference + "' is not set: enter it in the secret store");
        SecretStore.Held h = held.get();
        if (!kinds.contains(h.kind())) {
            return Optional.of("secret '" + reference + "' is a secret of kind " + h.kind().id() + " in the secret store, taken here as " + ids(kinds));
        }
        if (!h.readable()) return Optional.of("secret '" + reference + "' does not open on this node: it is sealed under the key " + h.keyId());
        return Optional.empty();
    }

    /**
     * A live handle of {@code reference}, a secret of one of {@code kinds}: each read follows the store's row now, else no value.
     *
     * @throws IllegalArgumentException {@code reference} is malformed, under {@code rail8.}, not set, unreadable or of a kind
     *                                  {@code kinds} does not name; the message names the reference
     */
    public Secret handle(String reference, Set<Secret.Kind> kinds) {
        String name = name(reference);
        if (kinds.isEmpty()) throw new IllegalArgumentException("no kind of secret is asked for " + reference);
        if (placeholder) {
            if (kinds.contains(Secret.Kind.VALUE)) return Secret.of(reference, () -> PLACEHOLDER);
            if (kinds.contains(Secret.Kind.CREDENTIALS)) return Secret.credentials(reference, () -> PLACEHOLDER_CREDENTIALS);
            Secret.KeyPair pair = SecretStore.placeholderKeyPair();
            return Secret.keyPair(reference, () -> pair);
        }
        Optional<String> problem = problem(reference, kinds);
        if (problem.isPresent()) throw new IllegalArgumentException(problem.get());
        return typed(reference, name, store.held(name).map(SecretStore.Held::kind).orElseThrow());
    }

    /**
     * A live handle of {@code reference}, a secret of one of {@code kinds}, as {@link #handle} gives it but never refused for a
     * value it lacks now: of the kind the store holds it as when that is one of {@code kinds}, else {@code value} when
     * {@code kinds} names it, else the first of {@code kinds}. A read of a secret not set fails, and so does one of another kind,
     * naming both kinds.
     *
     * @throws IllegalArgumentException {@code reference} is malformed or under {@code rail8.}, or {@code kinds} is empty
     */
    public Secret live(String reference, Set<Secret.Kind> kinds) {
        String name = name(reference);
        if (placeholder || kinds.isEmpty()) return handle(reference, kinds);
        Secret.Kind kind = store == null ? null : store.held(name).map(SecretStore.Held::kind).filter(kinds::contains).orElse(null);
        if (kind == null) kind = kinds.contains(Secret.Kind.VALUE) ? Secret.Kind.VALUE : kinds.iterator().next();
        return typed(reference, name, kind);
    }

    /** The live handle of {@code reference}, whose name is {@code name}, of {@code kind}. */
    private Secret typed(String reference, String name, Secret.Kind kind) {
        return switch (kind) {
            case VALUE -> Secret.of(reference, () -> (String) readAs(reference, name, Secret.Kind.VALUE));
            case CREDENTIALS -> Secret.credentials(reference, () -> (Secret.Credentials) readAs(reference, name, Secret.Kind.CREDENTIALS));
            case KEY_PAIR -> Secret.keyPair(reference, () -> (Secret.KeyPair) readAs(reference, name, Secret.Kind.KEY_PAIR));
        };
    }

    /**
     * The value of {@code name} as {@code kind}, the kind its handle was built with, now.
     *
     * @throws IllegalStateException the store holds it now as another kind; the message names the reference and both kinds
     */
    private Object readAs(String reference, String name, Secret.Kind kind) {
        Optional<SecretStore.Held> held = store == null ? Optional.empty() : store.held(name);
        if (held.isPresent() && held.get().kind() != kind) {
            throw new IllegalStateException(reference + " is now a secret of kind " + held.get().kind().id() + " in the secret store, and this flow took it as "
                    + kind.id() + ": the flow's next build takes it as its new kind");
        }
        return read(name, kind);
    }

    /** Whether this is the placeholder of {@code rail8 validate} and {@code rail8 test}, rather than a node's. */
    boolean placeholders() {
        return placeholder;
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

    private static List<String> ids(Set<Secret.Kind> kinds) {
        return new TreeSet<>(kinds).stream().map(Secret.Kind::id).toList();
    }

    /** Equal when both are the placeholder, or both hold the same key, the same variables' names and the same store. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Secrets s && placeholder == s.placeholder && Objects.equals(key, s.key) && imports.equals(s.imports) && store == s.store;
    }

    /** Of the variables' names only. */
    @Override
    public int hashCode() {
        return Objects.hash(placeholder, imports);
    }

    /** The variables' names, the store's key ids when it has a key, never a value. */
    @Override
    public String toString() {
        return placeholder ? "Secrets[placeholder " + PLACEHOLDER + "]"
                : "Secrets[" + imports + (key == null ? "" : ", " + key) + (store == null ? "" : ", store") + "]";
    }
}
