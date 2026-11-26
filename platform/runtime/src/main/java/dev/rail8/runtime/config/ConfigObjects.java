package dev.rail8.runtime.config;

import dev.rail8.api.Secret;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import dev.rail8.runtime.refusal.RefusalCode;
import dev.rail8.runtime.secret.SecretRefused;
import dev.rail8.runtime.secret.SecretStore;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * The configuration store: every rule of {@link ConfigStore} over its rows, in Postgres ({@link #postgres}) or in memory.
 *
 * <p>Every body in force, every activation of the history and every consent is bound under the secret store's key: an
 * HMAC-SHA256 tag of the RFC 8785 array {@code ["rail8 config v1", kind, key, incarnation, version, activation, format,
 * body]}, or {@code ["rail8 consent v1", kind, key, incarnation, draft id, activation in force or null, digest]}, each integer
 * a decimal string. A body whose tag does not verify is never used: it is left out of every node's state and consents to
 * nothing. Only a pair of secret and server that a verified version of a connection sent counts as consented.
 *
 * <p>{@code etag} is base64url without padding of the SHA-256 of {@code ["rail8 etag v1", incarnation, revision, draft id,
 * consent, consent time]}, the consent in hex and its time as ISO-8601, nulls where the row has none; {@code run} is the
 * incarnation and the run counter in hex, joined by {@code .}. Ids, incarnations and stamps are drawn from the injected
 * {@link RandomGenerator}, never zero and never one another row holds; times come from the injected clock, to the
 * microsecond, as Postgres keeps them.
 *
 * @since 0.4
 */
public final class ConfigObjects implements ConfigStore {

    /**
     * What the node knows of an adapter: its forms, and the schemes its address fields name, which give an entry of
     * {@code allowedHosts} without a port its ports. An adapter whose address schemes are empty takes no {@code allowedHosts}.
     */
    public record Adapter(Forms forms, Set<String> addressSchemes) {
        public Adapter {
            Objects.requireNonNull(forms, "forms");
            addressSchemes = Set.copyOf(addressSchemes);
        }
    }

    /** The format of every body this store writes. */
    static final int FORMAT = 1;
    /** The channel every node listens on; the payload is the new generation. */
    public static final String CHANNEL = "rail8_config";

    static final int MAX_CONNECTIONS = 20_000;
    static final int MAX_SYSTEMS = 4_096;
    static final long MAX_BODY_BYTES = 64L * 1024;
    static final int MAX_FIELDS = 128;
    static final long MAX_BODIES_BYTES = 64L * 1024 * 1024;
    static final int MAX_HISTORY = 1_000;
    static final int KEPT_ACTIVATIONS = 100;
    static final int MAX_NOTE = 200;
    static final int MAX_REFUSED = 100;
    static final int MAX_ALLOWED_HOSTS = 256;
    static final int MAX_INSTANCE_NAME = 128;
    /** The rows a batch of binding again binds at most. */
    static final int BATCH = 100;

    private static final String REFERENCE = "secret://";
    private static final String ALLOWED_HOSTS = "allowedHosts";
    private static final Pattern SECRET_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final Pattern DELAY = Pattern.compile("([0-9]{1,5})(ms|s)");
    private static final Set<String> SHAPE_CODES = Set.of(Settings.Problem.TYPE, Settings.Problem.MIN, Settings.Problem.MAX,
            Settings.Problem.PATTERN, Settings.Problem.CHOICE, Settings.Problem.UNKNOWN);
    private static final HexFormat HEX = HexFormat.of();
    private static final String EMPTY_ETAG = etag(null);
    private static final System.Logger LOG = System.getLogger(ConfigObjects.class.getName());

    private final ConfigRows rows;
    private final SecretStore secrets;
    private final Function<String, Optional<Adapter>> adapters;
    private final Clock clock;
    private final RandomGenerator random;
    private final Consumer<String> warn;
    private volatile Limits limits = new Limits(MAX_CONNECTIONS, MAX_SYSTEMS, MAX_HISTORY, KEPT_ACTIVATIONS);

    /** The limits of the objects' counts and of each object's history, lower in a test than in production. */
    record Limits(int connections, int systems, int history, int keptActivations) { }

    ConfigObjects(ConfigRows rows, SecretStore secrets, Function<String, Optional<Adapter>> adapters, Clock clock, RandomGenerator random,
                  Consumer<String> warn) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.warn = Objects.requireNonNull(warn, "warn");
        secrets.register(bound());
    }

    /**
     * The store over Postgres's {@code pool}, binding under {@code secrets}' key and registered with it, so its tick binds the
     * rows again after a change of the key. {@code adapters} gives what the node knows of each adapter by its scheme.
     */
    public static ConfigObjects postgres(Pool pool, SecretStore secrets, Function<String, Optional<Adapter>> adapters, Clock clock,
                                         RandomGenerator random) {
        return new ConfigObjects(new PgConfigRows(pool, secrets, PgConfigRows.LOCK_WAIT), secrets, adapters, clock, random,
                text -> LOG.log(System.Logger.Level.WARNING, text));
    }

    /** Sets the limits, for a test that reaches them with few objects and few changes. */
    void limits(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    // ---------------------------------------------------------------------------------------------------------------- reads

    @Override
    public ObjectView read(Ref ref) {
        Objects.requireNonNull(ref, "ref");
        return rows.read(tx -> {
            Optional<ConfigRows.ObjectRow> row = tx.row(ref, false);
            if (row.isEmpty() && !named(tx, ref)) {
                throw new ConfigRefusal(404, RefusalCode.NOT_FOUND, "no configuration of " + ref + " is held",
                        values(ref).put("etag", EMPTY_ETAG));
            }
            return view(tx, ref, row);
        });
    }

    @Override
    public Page<ObjectView> list(Query query) {
        Objects.requireNonNull(query, "query");
        if (query.limit() < 1 || query.limit() > MAX_PAGE) throw badRequest("a page holds 1 to " + MAX_PAGE + " objects");
        return rows.read(tx -> {
            ConfigRows.Head head = tx.head(false);
            Overview overview = new Overview(head.generation(), head.stamp(), head.mocksAllowed(), tx.nodes());
            List<ObjectView> items = new ArrayList<>();
            String after = query.after().orElse("");
            boolean more = false;
            while (true) {
                List<ConfigRows.ObjectRow> page = tx.rows(query.kind(), query.prefix().orElse(""), after, MAX_PAGE);
                for (ConfigRows.ObjectRow r : page) {
                    after = r.ref.key();
                    if (query.needs().isPresent() && !waits(tx, r, query.needs().get())) continue;
                    if (items.size() == query.limit()) {
                        more = true;
                        break;
                    }
                    items.add(view(tx, r.ref, Optional.of(r), overview));
                }
                if (more || page.size() < MAX_PAGE) break;
            }
            return new Page<>(items, more ? Optional.of(items.getLast().json().getString("key")) : Optional.empty());
        });
    }

    @Override
    public State state(Held held) {
        Objects.requireNonNull(held, "held");
        return rows.read(tx -> {
            ConfigRows.Head head = tx.head(false);
            if (held.generation() == head.generation() && held.stamp() == head.stamp()) {
                return new State(head.generation(), head.stamp(), head.mocksAllowed(), List.of(), Set.of(), Refused.none());
            }
            List<InForce> changed = new ArrayList<>();
            Set<Ref> verified = new HashSet<>();
            List<Ref> refused = new ArrayList<>();
            int count = 0;
            for (ConfigRows.ObjectRow r : tx.inForce()) {
                if (!verifies(r)) {
                    count++;
                    if (refused.size() < MAX_REFUSED) refused.add(r.ref);
                    continue;
                }
                verified.add(r.ref);
                Holding holding = held.objects().get(r.ref);
                Optional<ConnectionState.Stop> stopped = r.stoppedAt == null ? Optional.empty()
                        : Optional.of(new ConnectionState.Stop(r.stoppedBy, r.stoppedAt));
                if (holding == null || holding.activation() != r.activation || !holding.stopped().equals(stopped)) {
                    changed.add(new InForce(r.ref, r.activation, r.format, r.body, stopped));
                }
            }
            Set<Ref> gone = new HashSet<>(held.objects().keySet());
            gone.removeAll(verified);
            changed.sort(Comparator.comparing((InForce f) -> f.ref().kind()).thenComparing(f -> f.ref().key()));
            return new State(head.generation(), head.stamp(), head.mocksAllowed(), changed, gone, new Refused(refused, count));
        });
    }

    @Override
    public void holds(String node, long epoch, long generation, long stamp, Refused refused) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(refused, "refused");
        rows.holds(node, epoch, generation, stamp, refused);
    }

    @Override
    public Overview overview() {
        return rows.read(tx -> {
            ConfigRows.Head head = tx.head(false);
            return new Overview(head.generation(), head.stamp(), head.mocksAllowed(), tx.nodes());
        });
    }

    @Override
    public Page<HistoryRow> history(Ref ref, OptionalLong after, int limit) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(after, "after");
        if (limit < 1 || limit > MAX_PAGE) throw badRequest("a page holds 1 to " + MAX_PAGE + " rows");
        return rows.read(tx -> {
            List<ConfigRows.History> page = tx.history(ref, after, limit + 1);
            boolean more = page.size() > limit;
            if (more) page = page.subList(0, limit);
            Optional<Instant> until = Optional.empty();
            if (after.isPresent()) {
                List<ConfigRows.History> before = tx.history(ref, OptionalLong.of(after.getAsLong() + 1), 1);
                if (!before.isEmpty() && before.getFirst().id == after.getAsLong()) until = Optional.of(before.getFirst().at);
            }
            Optional<Instant> from = more ? Optional.of(page.getLast().at) : Optional.empty();
            Set<String> names = new TreeSet<>();
            for (ConfigRows.History h : tx.activations(ref)) names.addAll(secretNames(h.body));
            tx.row(ref, false).ifPresent(r -> names.addAll(secretNames(r.body)));
            List<ConfigRows.SecretAct> acts = names.isEmpty() ? List.of() : tx.secretActs(names, from, until);
            List<HistoryRow> items = new ArrayList<>();
            int a = 0;
            for (ConfigRows.History h : page) {
                while (a < acts.size() && acts.get(a).at().isAfter(h.at)) items.add(act(acts.get(a++)));
                items.add(historyRow(h));
            }
            while (a < acts.size()) items.add(act(acts.get(a++)));
            return new Page<>(items, more ? Optional.of(Long.toString(page.getLast().id)) : Optional.empty());
        });
    }

    // ---------------------------------------------------------------------------------------------------------------- writes

    @Override
    public ObjectView write(Actor actor, Ref ref, Change change) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(change, "change");
        return switch (change) {
            case Change.Save s -> {
                connectionOnly(ref, "a save of values");
                yield draft(actor, ref, s.expect(), connectionBody(s.adapter(), s.values(), s.mock()), "save");
            }
            case Change.SaveSystem s -> {
                if (ref.kind() != Ref.Kind.SYSTEM) throw badRequest("an instance name is a system's");
                yield draft(actor, ref, s.expect(), systemBody(s.instanceName()), "save");
            }
            case Change.Remove r -> draft(actor, ref, r.expect(), null, "save");
            case Change.GoBack g -> goBack(actor, ref, g);
            case Change.Discard d -> discard(actor, ref, d.expect());
            case Change.Consent c -> consent(actor, ref, c);
            case Change.Activate a -> activate(actor, ref, a);
            case Change.Stop s -> stopOrStart(actor, ref, s.run(), true);
            case Change.Start s -> stopOrStart(actor, ref, s.run(), false);
            case Change.Mocks m -> throw badRequest("the switch of mocks has no key: it is written by writeMocks");
        };
    }

    /**
     * A draft of {@code ref}: {@code body}, or a removal when it is null. A save that creates a row counts the rows under
     * {@code config_store}'s lock; a save equal to the version in force drops the draft; one over a draft keeps its id, and its
     * consent while what it sends is unchanged.
     */
    private ObjectView draft(Actor actor, Ref ref, String expect, JsonObject body, String change) {
        requireExpect(expect);
        JsonObject draft = body == null ? new JsonObject().put("removed", true) : body;
        Instant now = now();
        return rows.write(tx -> {
            Optional<ConfigRows.ObjectRow> row = tx.row(ref, false);
            boolean headLocked = false;
            if (row.isEmpty()) {
                tx.head(true);
                headLocked = true;
            }
            row = tx.row(ref, true);
            if (row.isEmpty() && !headLocked) {
                tx.head(true);
                row = tx.row(ref, true);
            }
            expect(tx, ref, row, expect);
            if (body == null && row.isEmpty()) throw notFound(tx, ref);
            if (ref.kind() == Ref.Kind.CONNECTION && body != null) adapterDiffers(tx, ref, body.getString("adapter"));
            Set<Long> drawn = new HashSet<>();
            ConfigRows.ObjectRow r;
            if (row.isEmpty()) {
                long most = ref.kind() == Ref.Kind.CONNECTION ? limits.connections() : limits.systems();
                if (tx.count(ref.kind()) >= most) {
                    throw new ConfigRefusal(409, RefusalCode.CONFIG_FULL, "the configuration holds " + most + " objects of kind "
                            + ref.kind().id() + ", the most it holds", values(ref).put("most", most));
                }
                r = new ConfigRows.ObjectRow();
                r.ref = ref;
                r.incarnation = draw(tx, drawn);
            } else {
                r = row.get().copy();
            }
            JsonObject from = r.draft != null ? r.draft : inForceBody(r);
            if (sameAsInForce(r, draft)) {
                r.dropDraft();
            } else {
                if (r.draft == null) {
                    r.draftId = draw(tx, drawn);
                } else if (!pairs(r.ref, r.draft).equals(pairs(r.ref, draft))) {
                    r.dropConsent();
                }
                r.draftFormat = FORMAT;
                r.draft = draft.copy();
                r.draftAt = now;
                r.draftBy = actor.who().name();
            }
            r.revision++;
            tx.put(r);
            history(tx, r, actor, now, change, diff(from, r.draft != null ? r.draft : inForceBody(r)), null, null);
            return view(tx, ref, Optional.of(r));
        });
    }

    private ObjectView goBack(Actor actor, Ref ref, Change.GoBack g) {
        requireExpect(g.expect());
        ConfigRows.History kept = rows.read(tx -> tx.version(ref, g.fromVersion())).orElseThrow(() -> new ConfigRefusal(404,
                RefusalCode.NOT_FOUND, "version " + g.fromVersion() + " of " + ref + " is not kept in its history",
                values(ref).put("version", g.fromVersion())));
        if (kept.body == null) return draft(actor, ref, g.expect(), null, "save");
        JsonObject body = ref.kind() == Ref.Kind.CONNECTION ? connectionBody(kept.body) : systemBody(kept.body.getString("instanceName"));
        return draft(actor, ref, g.expect(), body, "save");
    }

    private ObjectView discard(Actor actor, Ref ref, String expect) {
        requireExpect(expect);
        Instant now = now();
        return rows.write(tx -> {
            Optional<ConfigRows.ObjectRow> row = tx.row(ref, true);
            expect(tx, ref, row, expect);
            if (row.isEmpty() || row.get().draft == null) throw noDraft(ref);
            ConfigRows.ObjectRow r = row.get().copy();
            JsonObject from = r.draft;
            r.dropDraft();
            r.revision++;
            tx.put(r);
            history(tx, r, actor, now, "discard", diff(from, inForceBody(r)), null, null);
            return view(tx, ref, Optional.of(r));
        });
    }

    /**
     * The secret administrator's consent to the pairs the draft needs, bound to the row, the draft and the version in force; or
     * its withdrawal. A digest that is not the draft's as it is now is 412 {@code changed}.
     */
    private ObjectView consent(Actor actor, Ref ref, Change.Consent c) {
        requireExpect(c.expect());
        Objects.requireNonNull(c.digest(), "digest");
        Instant now = now();
        return rows.write(tx -> {
            SecretStore.Binder binder = binder(tx);
            Optional<ConfigRows.ObjectRow> row = tx.row(ref, true);
            expect(tx, ref, row, c.expect());
            if (row.isEmpty() || row.get().draft == null) throw nothingToConsent(ref, "has no draft");
            ConfigRows.ObjectRow r = row.get().copy();
            JsonObject detail = new JsonObject().put("kind", ref.kind().id()).put("key", ref.key());
            if (c.digest().isEmpty()) {
                if (r.consent == null) throw nothingToConsent(ref, "holds no consent to withdraw");
                r.dropConsent();
                r.consentAt = now;
                r.consentBy = actor.who().name();
                detail.put("withdrawn", true);
            } else {
                Optional<Needed> needed = needed(tx, r);
                if (needed.isEmpty()) throw nothingToConsent(ref, "sends nothing that needs consent");
                if (!needed.get().hex().equals(c.digest().get())) {
                    throw changed(tx, ref, Optional.of(r), "the digest is not that of the draft of " + ref + " as it is now");
                }
                r.consent = needed.get().digest();
                byte[] bytes = Jcs.bytes(consentArray(r, needed.get().hex()));
                SecretStore.Binding binding = bind(binder, bytes);
                r.consentKeyId = binding.keyId();
                r.consentTag = binding.tag();
                r.consentAt = now;
                r.consentBy = actor.who().name();
                detail.put("digest", needed.get().hex()).put("sends", sends(needed.get().pairs()));
            }
            tx.put(r);
            tx.audit(actor.who(), now, "config.consent", ref.subject(), withRemote(actor, detail));
            return view(tx, ref, Optional.of(r));
        });
    }

    /**
     * Puts the draft in force, checked against the state of its own transaction: the adapter the deployed system names, the
     * switch of mocks, completeness, consent, the one confirmation of the uses it would leave unserved, an instance name in use,
     * and the key every live node holds. Then it binds the body, writes the next version under a new activation, the history
     * row with the same binding, the uses of the secrets it names, the audit row and a new generation.
     */
    private ObjectView activate(Actor actor, Ref ref, Change.Activate a) {
        requireExpect(a.expect());
        Objects.requireNonNull(a.confirm(), "confirm");
        Objects.requireNonNull(a.note(), "note");
        if (a.note().isPresent() && a.note().get().length() > MAX_NOTE) {
            throw new ConfigRefusal(422, RefusalCode.INVALID, "a note holds at most " + MAX_NOTE + " characters",
                    values(ref).put("field", "note").put("max", MAX_NOTE));
        }
        Instant now = now();
        return rows.write(tx -> {
            Optional<ConfigRows.ObjectRow> peeked = tx.row(ref, false);
            Map<String, Set<Secret.Kind>> uses = peeked.map(r -> secretUses(r.draft)).orElse(Map.of());
            List<String> problems = ref.kind() == Ref.Kind.CONNECTION && peeked.isPresent() ? tx.replaceUses(ref, uses) : List.of();
            SecretStore.Binder binder = binder(tx);
            ConfigRows.Head head = tx.head(true);
            Optional<ConfigRows.ObjectRow> row = tx.row(ref, true);
            expect(tx, ref, row, a.expect());
            if (row.isEmpty() || row.get().draft == null) throw noDraft(ref);
            ConfigRows.ObjectRow r = row.get().copy();
            if (peeked.isEmpty() || !Objects.equals(r.draftId, peeked.get().draftId) || !Jcs.write(r.draft).equals(Jcs.write(peeked.get().draft))) {
                throw changed(tx, ref, Optional.of(r), "the draft of " + ref + " changed while it was activated");
            }
            boolean removal = r.draft.getBoolean("removed", false);
            JsonObject detail = new JsonObject().put("kind", ref.kind().id()).put("key", ref.key());
            if (ref.kind() == Ref.Kind.CONNECTION) {
                checkConnection(tx, r, removal, head, uses, problems, actor, a.confirm(), detail);
            } else if (removal) {
                List<ConfigRows.Use> used = tx.usesOfSystem(ref.key());
                if (!used.isEmpty()) {
                    List<String> flows = used.stream().map(ConfigRows.Use::flow).distinct().sorted().toList();
                    throw new ConfigRefusal(409, RefusalCode.NAME_IN_USE, "the instance name of " + ref + " is used by the flows " + flows
                            + ": change it instead of removing it", values(ref), new JsonObject().put("flows", new JsonArray(flows)), null);
                }
            }
            JsonObject from = inForceBody(r);
            JsonObject body = removal ? null : r.draft.copy();
            if (body != null) {
                long bytes = Jcs.bytes(body).length;
                if (tx.bodyBytes(ref) + bytes > MAX_BODIES_BYTES) {
                    throw new ConfigRefusal(409, RefusalCode.CONFIG_FULL, "the bodies in force would take more than " + MAX_BODIES_BYTES
                            + " bytes, the most they take", values(ref).put("most", MAX_BODIES_BYTES));
                }
            }
            Set<Long> drawn = new HashSet<>();
            r.version++;
            r.activation = draw(tx, drawn);
            r.format = body == null ? null : FORMAT;
            r.body = body;
            SecretStore.Binding binding = bind(binder, Jcs.bytes(bodyArray(r.ref, r.incarnation, r.version, r.activation, r.format, r.body)));
            r.keyId = binding.keyId();
            r.tag = binding.tag();
            r.activatedAt = now;
            r.activatedBy = actor.who().name();
            r.dropDraft();
            if (removal) {
                r.stoppedAt = null;
                r.stoppedBy = null;
                r.run++;
            }
            r.revision++;
            tx.put(r);
            long generation = raise(tx, head, head.mocksAllowed(), drawn);
            JsonArray diff = diff(from, inForceBody(r));
            history(tx, r, actor, now, "activate", diff, a.note().orElse(null), generation);
            List<String> fields = new ArrayList<>();
            for (Object d : diff) fields.add(((JsonObject) d).getString("field"));
            detail.put("version", r.version).put("generation", generation).put("changed", new JsonArray(fields));
            a.confirm().ifPresent(c -> detail.put("confirm", c));
            a.note().ifPresent(n -> detail.put("note", n));
            tx.audit(actor.who(), now, "config.activate", ref.subject(), withRemote(actor, detail));
            tx.notifyGeneration(generation);
            return view(tx, ref, Optional.of(r));
        });
    }

    /** The checks of a connection's activation, in order; {@code detail} gains the pairs consented by the same act. */
    private void checkConnection(ConfigRows.Tx tx, ConfigRows.ObjectRow r, boolean removal, ConfigRows.Head head, Map<String, Set<Secret.Kind>> uses,
                                 List<String> problems, Actor actor, Optional<String> confirm, JsonObject detail) {
        Ref ref = r.ref;
        List<ConfigRows.Use> used = tx.uses(ref.key());
        if (removal) {
            confirm(ref, used.stream().map(u -> new Unserved(u, List.of(new JsonObject().put("lacks", "removed")))).toList(), confirm);
            return;
        }
        Body body = Body.of(r.draft);
        adapterDiffers(tx, ref, body.adapter());
        Adapter adapter = adapter(body.adapter());
        if (body.mock() != null) {
            if (!head.mocksAllowed()) {
                throw new ConfigRefusal(409, RefusalCode.MOCKS_REFUSED, "the environment does not allow mocks, so the mocked " + ref
                        + " is not activated", values(ref));
            }
            throw new ConfigRefusal(422, RefusalCode.INVALID, "the platform's built-in mock is not available yet, so the mocked " + ref
                    + " is not activated", values(ref).put("field", "mock"));
        }
        Map<String, Secret.Kind> kinds = tx.kinds(uses.keySet());
        Map<Direction, List<JsonObject>> lines = lines(adapter, body, kinds);
        boolean complete = problems.isEmpty() && lines.values().stream().anyMatch(List::isEmpty);
        if (!complete) {
            JsonArray all = new JsonArray();
            lines.values().forEach(l -> l.forEach(all::add));
            for (String p : problems) if (all.isEmpty()) all.add(new JsonObject().put("field", "").put("code", "secret").put("direction", "").put("text", p));
            throw new ConfigRefusal(422, RefusalCode.INCOMPLETE, "the values of " + ref + " are not complete for any direction its adapter"
                    + " connects", values(ref), new JsonObject().put("lines", all), null);
        }
        Sends sends = sendsOf(adapter, body, "draft");
        Optional<Needed> needed = Servers.needs(sends, consented(tx, r));
        if (needed.isPresent()) {
            boolean given = r.consent != null && needed.get().hex().equals(HEX.formatHex(r.consent)) && consentVerifies(r);
            if (!given && !actor.consents()) {
                throw new ConfigRefusal(409, RefusalCode.CONSENT_NEEDED, "the draft of " + ref + " sends " + needed.get().pairs().size()
                        + " pairs of secret and server that need the secret administrator's consent", values(ref),
                        new JsonObject().put("digest", needed.get().hex()).put("sends", sends(needed.get().pairs())), null);
            }
            detail.put("sends", sends(needed.get().pairs()));
            if (!given) detail.put("consentedByThisAct", true);
        }
        List<Unserved> unserved = new ArrayList<>();
        List<?> hosts = body.allowedHosts();
        for (ConfigRows.Use u : used) {
            List<JsonObject> lacks = new ArrayList<>();
            Direction d = u.direction().equals("in") ? Direction.IN : Direction.OUT;
            if (!adapter.forms().connects(d)) lacks.add(new JsonObject().put("lacks", "direction"));
            else for (JsonObject line : lines.get(d)) lacks.add(line.copy().put("lacks", "incomplete"));
            if (u.address() && hosts.isEmpty()) lacks.add(new JsonObject().put("lacks", "allowedHosts"));
            if (!lacks.isEmpty()) unserved.add(new Unserved(u, lacks));
        }
        confirm(ref, unserved, confirm);
    }

    /** A use the change would leave unserved, and what it would lack. */
    private record Unserved(ConfigRows.Use use, List<JsonObject> lacks) { }

    /** Refuses with 409 {@code in-use} when some use would be unserved and {@code confirm} is not the digest of those uses. */
    private static void confirm(Ref ref, List<Unserved> unserved, Optional<String> confirm) {
        if (unserved.isEmpty()) return;
        String digest = usesDigest(unserved.stream().map(Unserved::use).toList());
        if (confirm.isPresent() && confirm.get().equals(digest)) return;
        JsonArray uses = new JsonArray();
        for (Unserved u : unserved) {
            uses.add(new JsonObject().put("flow", u.use().flow()).put("step", u.use().step()).put("direction", u.use().direction())
                    .put("address", u.use().address()).put("lacks", new JsonArray(new ArrayList<>(u.lacks()))));
        }
        throw new ConfigRefusal(409, RefusalCode.IN_USE, "the change would leave " + unserved.size() + " uses of " + ref
                + " unserved: confirm with the digest of the uses", values(ref), new JsonObject().put("uses", uses).put("digest", digest), null);
    }

    /** The hex SHA-256 of the RFC 8785 array of the uses {@code {flow, step, direction, address}}, sorted by flow, step and direction. */
    static String usesDigest(List<ConfigRows.Use> uses) {
        List<Map<String, Object>> sorted = new ArrayList<>();
        uses.stream().sorted(Comparator.comparing(ConfigRows.Use::flow).thenComparing(ConfigRows.Use::step).thenComparing(ConfigRows.Use::direction))
                .forEach(u -> sorted.add(Map.of("flow", u.flow(), "step", u.step(), "direction", u.direction(), "address", u.address())));
        return HEX.formatHex(Jcs.sha256(sorted));
    }

    /** A stop or a start of a connection in force, stating its {@code run}. */
    private ObjectView stopOrStart(Actor actor, Ref ref, String run, boolean stop) {
        connectionOnly(ref, "a stop and a start");
        if (run == null) throw badRequest("a stop and a start state the run they saw");
        Instant now = now();
        return rows.write(tx -> {
            ConfigRows.Head head = tx.head(true);
            Optional<ConfigRows.ObjectRow> row = tx.row(ref, true);
            if (row.isPresent() && !run(row.get()).equals(run)) {
                throw changed(tx, ref, row, ref + " was stopped or started since it was read");
            }
            if (row.isEmpty() || row.get().body == null) {
                throw new ConfigRefusal(409, RefusalCode.NOT_SET, ref + " has nothing in force to " + (stop ? "stop" : "start"), values(ref));
            }
            ConfigRows.ObjectRow r = row.get().copy();
            r.run++;
            r.stoppedAt = stop ? now : null;
            r.stoppedBy = stop ? actor.who().name() : null;
            tx.put(r);
            long generation = raise(tx, head, head.mocksAllowed(), new HashSet<>());
            history(tx, r, actor, now, stop ? "stop" : "start", new JsonArray(), null, generation);
            String action = stop ? "config.stop" : "config.start";
            tx.audit(actor.who(), now, action, ref.subject(), withRemote(actor, new JsonObject().put("kind", ref.kind().id())
                    .put("key", ref.key()).put("generation", generation)));
            tx.notifyGeneration(generation);
            return view(tx, ref, Optional.of(r));
        });
    }

    @Override
    public void writeMocks(Actor actor, Change.Mocks change) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(change.confirm(), "confirm");
        Instant now = now();
        rows.write(tx -> {
            ConfigRows.Head head = tx.head(true);
            if (head.mocksAllowed() != change.expect()) {
                throw new ConfigRefusal(412, RefusalCode.CHANGED, "mocks were " + (head.mocksAllowed() ? "allowed" : "not allowed")
                        + " when the switch was written", new JsonObject().put("mocksAllowed", head.mocksAllowed()));
            }
            if (head.mocksAllowed() && !change.allowed()) {
                List<Unserved> unserved = new ArrayList<>();
                for (ConfigRows.ObjectRow r : tx.inForce()) {
                    if (r.ref.kind() != Ref.Kind.CONNECTION || !r.body.containsKey("mock")) continue;
                    for (ConfigRows.Use u : tx.uses(r.ref.key())) unserved.add(new Unserved(u, List.of(new JsonObject().put("lacks", "mocked"))));
                }
                if (!unserved.isEmpty()) {
                    String digest = usesDigest(unserved.stream().map(Unserved::use).toList());
                    if (change.confirm().isEmpty() || !change.confirm().get().equals(digest)) {
                        JsonArray uses = new JsonArray();
                        for (Unserved u : unserved) {
                            uses.add(new JsonObject().put("connection", u.use().connection()).put("flow", u.use().flow())
                                    .put("step", u.use().step()).put("direction", u.use().direction()).put("lacks", new JsonArray(new ArrayList<>(u.lacks()))));
                        }
                        throw new ConfigRefusal(409, RefusalCode.IN_USE, "turning mocks off would leave " + unserved.size()
                                + " uses of mocked connections unserved: confirm with the digest of the uses", new JsonObject(),
                                new JsonObject().put("uses", uses).put("digest", digest), null);
                    }
                }
            }
            long generation = raise(tx, head, change.allowed(), new HashSet<>());
            JsonObject detail = new JsonObject().put("allowed", change.allowed()).put("generation", generation);
            change.confirm().ifPresent(c -> detail.put("confirm", c));
            tx.audit(actor.who(), now, "config.mocks", "config/mocks", withRemote(actor, detail));
            tx.notifyGeneration(generation);
            return null;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------- binding again

    /** What the secret store's tick asks: the rows bound under a key, and binding them again under the current one. */
    SecretStore.BoundRows bound() {
        return new SecretStore.BoundRows() {
            @Override
            public Future<Long> countUnder(SqlConnection tx, byte[] keyId) {
                return rows.within(tx, t -> t.countUnder(keyId));
            }

            @Override
            public Future<Integer> bindAgain(SqlConnection tx, int max) {
                return rows.within(tx, t -> ConfigObjects.this.bindAgain(t, Math.min(max, BATCH)));
            }
        };
    }

    /**
     * Each tag under another key is verified under the key it names: one that verifies is bound under the current key, one that
     * does not is cleared, its key id kept, and stays refused with a WARNING. A batch that changed a row raises the generation.
     */
    private int bindAgain(ConfigRows.Tx tx, int batch) {
        SecretStore.Binder binder = binder(tx);
        byte[] current = binder.keyId();
        ConfigRows.Head head = tx.head(true);
        int changed = 0;
        List<ConfigRows.ObjectRow> objects = tx.boundElsewhere(current, batch);
        for (ConfigRows.ObjectRow found : objects) {
            ConfigRows.ObjectRow r = found.copy();
            if (r.tag != null && !Arrays.equals(r.keyId, current)) {
                byte[] bytes = Jcs.bytes(bodyArray(r.ref, r.incarnation, r.version, r.activation, r.format, r.body));
                if (secrets.verify(new SecretStore.Binding(r.keyId, r.tag), bytes)) {
                    SecretStore.Binding b = bind(binder, bytes);
                    r.keyId = b.keyId();
                    r.tag = b.tag();
                } else {
                    failed(r.ref, "its body in force", r.keyId);
                    r.tag = null;
                }
            }
            if (r.consentTag != null && !Arrays.equals(r.consentKeyId, current)) {
                byte[] bytes = Jcs.bytes(consentArray(r, HEX.formatHex(r.consent)));
                if (secrets.verify(new SecretStore.Binding(r.consentKeyId, r.consentTag), bytes)) {
                    SecretStore.Binding b = bind(binder, bytes);
                    r.consentKeyId = b.keyId();
                    r.consentTag = b.tag();
                } else {
                    failed(r.ref, "the consent to its draft", r.consentKeyId);
                    r.consentTag = null;
                }
            }
            tx.put(r);
            changed++;
        }
        if (objects.size() < batch) {
            for (ConfigRows.History h : tx.historyBoundElsewhere(current, batch - objects.size())) {
                byte[] bytes = Jcs.bytes(bodyArray(h.ref, h.incarnation, h.version, h.activation, h.format, h.body));
                if (secrets.verify(new SecretStore.Binding(h.keyId, h.tag), bytes)) {
                    SecretStore.Binding b = bind(binder, bytes);
                    tx.bindHistory(h.id, b.keyId(), b.tag());
                } else {
                    failed(h.ref, "version " + h.version + " of its history", h.keyId);
                    tx.bindHistory(h.id, h.keyId, null);
                }
                changed++;
            }
        }
        if (changed > 0) tx.notifyGeneration(raise(tx, head, head.mocksAllowed(), new HashSet<>()));
        return changed;
    }

    private void failed(Ref ref, String what, byte[] keyId) {
        warn.accept("the binding of " + what + " of " + ref + " under the key " + HEX.formatHex(keyId)
                + " does not verify: it stays refused; save and activate it again");
    }

    // ---------------------------------------------------------------------------------------------------------------- the checks

    /** The body of a connection's save, its shape checked: format 1 {@code {adapter, values, mock?}}. */
    private JsonObject connectionBody(String adapterName, Map<String, Object> values, Optional<Change.Mock> mock) {
        if (adapterName == null || values == null || mock == null) throw badRequest("a save states the adapter, the values and the mock");
        Adapter adapter = adapter(adapterName);
        Settings form = adapter.forms().connection();
        if (values.size() > MAX_FIELDS) {
            throw new ConfigRefusal(413, RefusalCode.TOO_LARGE, "a connection holds at most " + MAX_FIELDS + " fields",
                    new JsonObject().put("max", MAX_FIELDS));
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        List<String> hosts = List.of();
        for (Map.Entry<String, Object> e : new TreeMap<>(values).entrySet()) {
            String field = e.getKey();
            Object value = e.getValue();
            if (value == null) continue;
            if (field.equals(ALLOWED_HOSTS)) {
                hosts = allowedHosts(adapter, value);
                kept.put(field, hosts);
                continue;
            }
            if (!(value instanceof String || value instanceof Boolean || value instanceof Number)) {
                throw invalid("values." + field, "a value is a text, a whole number or a boolean");
            }
            if (Jcs.inexact(value)) throw invalid("values." + field, "a number is a whole number within ±2^53");
            if (value instanceof String s) {
                boolean secret = form.secrets().contains(field);
                if (secret) secretReference("values." + field, s);
                else if (s.contains(REFERENCE)) throw invalid("values." + field, "only a secret field holds secret://");
                if (hasUserInfo(s)) throw invalid("values." + field, "a URL holds no user information");
            } else if (form.secrets().contains(field)) {
                throw invalid("values." + field, "a secret field holds a reference secret://<name>");
            }
            kept.put(field, value);
        }
        Map<String, Object> checked = new LinkedHashMap<>(kept);
        checked.remove(ALLOWED_HOSTS);
        boolean connects = false;
        for (Direction d : Direction.values()) {
            if (!adapter.forms().connects(d)) continue;
            connects = true;
            for (Settings.Problem p : form.check(checked, d)) {
                if (SHAPE_CODES.contains(p.code())) throw invalid("values." + field(p.path()), p.message());
            }
        }
        if (!connects) throw invalid("adapter", "the adapter " + adapterName + " reaches no connection");
        try {
            Servers.of(form, checked, hosts, adapter.addressSchemes());
        } catch (Servers.Invalid refused) {
            throw invalid(refused.field().equals(ALLOWED_HOSTS) ? "values." + ALLOWED_HOSTS : "values." + refused.field(), refused.getMessage());
        }
        JsonObject body = new JsonObject().put("adapter", adapterName).put("values", new JsonObject(kept));
        mock.ifPresent(m -> body.put("mock", mock(m)));
        return sized(body);
    }

    /** The body of {@code kept}, a connection's body of the history, checked as any save. */
    private JsonObject connectionBody(JsonObject kept) {
        Body b = Body.of(kept);
        Optional<Change.Mock> mock = b.mock() == null ? Optional.empty() : Optional.of(new Change.Mock(b.mock().getString("reply"),
                Optional.ofNullable(b.mock().getString("delay")), Optional.ofNullable(b.mock().getString("fail"))));
        return connectionBody(b.adapter(), b.values(), mock);
    }

    /** The body of a system's save: {@code {instanceName}}, 1 to 128 printable characters. */
    private static JsonObject systemBody(String instanceName) {
        if (instanceName == null) throw badRequest("a system's save states its instance name");
        int length = instanceName.codePointCount(0, instanceName.length());
        if (length < 1 || length > MAX_INSTANCE_NAME || instanceName.codePoints().anyMatch(c -> Character.isISOControl(c) || !Character.isDefined(c))
                || instanceName.isBlank()) {
            throw invalid("instanceName", "an instance name is 1 to " + MAX_INSTANCE_NAME + " printable characters");
        }
        return new JsonObject().put("instanceName", instanceName);
    }

    private static JsonObject mock(Change.Mock m) {
        if (!"echo".equals(m.reply()) && !"empty".equals(m.reply())) throw invalid("mock.reply", "a mock replies echo or empty");
        JsonObject mock = new JsonObject().put("reply", m.reply());
        if (m.delay() == null || m.fail() == null) throw badRequest("a mock states its delay and its failure, each optional");
        if (m.delay().isPresent()) {
            var matched = DELAY.matcher(m.delay().get());
            if (!matched.matches()) throw invalid("mock.delay", "a delay is <n>ms or <n>s");
            long n = Long.parseLong(matched.group(1));
            Duration delay = matched.group(2).equals("ms") ? Duration.ofMillis(n) : Duration.ofSeconds(n);
            if (delay.compareTo(Duration.ofSeconds(60)) > 0) throw invalid("mock.delay", "a delay is at most 60 s");
            mock.put("delay", m.delay().get());
        }
        if (m.fail().isPresent()) {
            if (!m.fail().get().equals("retryable") && !m.fail().get().equals("invalid")) throw invalid("mock.fail", "a mock fails retryable or invalid");
            mock.put("fail", m.fail().get());
        }
        return mock;
    }

    private static JsonObject sized(JsonObject body) {
        if (Jcs.bytes(body).length > MAX_BODY_BYTES) {
            throw new ConfigRefusal(413, RefusalCode.TOO_LARGE, "a body takes at most " + MAX_BODY_BYTES + " bytes as canonical JSON",
                    new JsonObject().put("max", MAX_BODY_BYTES));
        }
        return body;
    }

    private static List<String> allowedHosts(Adapter adapter, Object value) {
        if (adapter.addressSchemes().isEmpty()) throw invalid("values." + ALLOWED_HOSTS, "the adapter has no address field, so it takes no allowed hosts");
        List<?> list = value instanceof JsonArray a ? a.getList() : value instanceof List<?> l ? l : null;
        if (list == null || list.size() > MAX_ALLOWED_HOSTS || !list.stream().allMatch(String.class::isInstance)) {
            throw invalid("values." + ALLOWED_HOSTS, "allowed hosts are a list of at most " + MAX_ALLOWED_HOSTS + " hosts");
        }
        for (Object host : list) {
            if (((String) host).contains(REFERENCE)) throw invalid("values." + ALLOWED_HOSTS, "only a secret field holds secret://");
        }
        return list.stream().map(String.class::cast).toList();
    }

    private static void secretReference(String field, String value) {
        if (!value.startsWith(REFERENCE)) throw invalid(field, "a secret field holds a reference secret://<name>");
        String name = value.substring(REFERENCE.length());
        if (!SECRET_NAME.matcher(name).matches()) throw invalid(field, "a secret's name is [a-z0-9][a-z0-9._-]*, at most 128 characters");
        if (name.startsWith(SecretStore.RESERVED)) {
            throw new ConfigRefusal(422, RefusalCode.RESERVED, field + ": names under " + SecretStore.RESERVED + " belong to the platform",
                    new JsonObject().put("field", field).put("name", name));
        }
    }

    private static boolean hasUserInfo(String text) {
        try {
            URI uri = new URI(text);
            return uri.isAbsolute() && uri.getRawAuthority() != null && uri.getRawAuthority().contains("@");
        } catch (URISyntaxException notAUri) {
            return false;
        }
    }

    private static String field(String pointer) {
        return pointer.startsWith("/") ? pointer.substring(1).replace("~1", "/").replace("~0", "~") : pointer;
    }

    private Adapter adapter(String name) {
        return adapters.apply(name).orElseThrow(() -> new ConfigRefusal(422, RefusalCode.INVALID, "the adapter " + name
                + " is not known to the node", new JsonObject().put("field", "adapter").put("adapter", name)));
    }

    /** Refuses with 409 {@code adapter-differs} when the deployed system of the connection names another adapter for it. */
    private static void adapterDiffers(ConfigRows.Tx tx, Ref ref, String adapter) {
        Optional<String> deployed = tx.deployedAdapter(ref.key());
        if (deployed.isPresent() && !deployed.get().equals(adapter)) {
            throw new ConfigRefusal(409, RefusalCode.ADAPTER_DIFFERS, ref + " takes the adapter " + adapter + ", but its deployed system names "
                    + deployed.get(), values(ref).put("adapter", adapter).put("deployed", deployed.get()));
        }
    }

    /**
     * The lines of what the values lack, for each direction the adapter connects: the form's problems, a secret not set and a
     * secret of a kind its field does not take; an empty list for a direction that is complete. Never a value.
     */
    private static Map<Direction, List<JsonObject>> lines(Adapter adapter, Body body, Map<String, Secret.Kind> kinds) {
        Settings form = adapter.forms().connection();
        Map<String, Object> checked = new LinkedHashMap<>(body.values());
        checked.remove(ALLOWED_HOSTS);
        Map<String, Set<Direction>> serves = directions(form);
        Map<Direction, List<JsonObject>> lines = new java.util.EnumMap<>(Direction.class);
        for (Direction d : Direction.values()) {
            if (!adapter.forms().connects(d)) continue;
            String direction = d.name().toLowerCase(java.util.Locale.ROOT);
            List<JsonObject> of = new ArrayList<>();
            for (Settings.Problem p : form.check(checked, d)) {
                if (p.code().equals(Settings.Problem.DIRECTION)) continue;
                of.add(new JsonObject().put("field", field(p.path())).put("code", p.code()).put("direction", direction));
            }
            for (String field : form.secrets()) {
                if (!(checked.get(field) instanceof String s) || !s.startsWith(REFERENCE) || !serves.getOrDefault(field, Set.of()).contains(d)) continue;
                Secret.Kind kind = kinds.get(s.substring(REFERENCE.length()));
                if (kind == null) of.add(new JsonObject().put("field", field).put("code", "not-set").put("direction", direction));
                else if (!form.kinds(field).contains(kind)) of.add(new JsonObject().put("field", field).put("code", "kind").put("direction", direction));
            }
            lines.put(d, of);
        }
        return lines;
    }

    /** The directions each field of {@code form} serves, as its description gives them. */
    private static Map<String, Set<Direction>> directions(Settings form) {
        Map<String, Set<Direction>> serves = new HashMap<>();
        Object fields = form.toJson().get("fields");
        if (!(fields instanceof List<?> list)) return serves;
        for (Object f : list) {
            Map<?, ?> field = (Map<?, ?>) f;
            Set<Direction> ds = EnumSet.noneOf(Direction.class);
            for (Object d : (List<?>) field.get("directions")) ds.add("in".equals(d) ? Direction.IN : Direction.OUT);
            serves.put((String) field.get("name"), ds);
        }
        return serves;
    }

    /** The names the secret fields of a draft name, with the kinds each takes; none for a mocked or removed connection. */
    private Map<String, Set<Secret.Kind>> secretUses(JsonObject draft) {
        if (draft == null || draft.getBoolean("removed", false) || draft.containsKey("mock") || !draft.containsKey("adapter")) return Map.of();
        Body body = Body.of(draft);
        Optional<Adapter> adapter = adapters.apply(body.adapter());
        if (adapter.isEmpty()) return Map.of();
        Settings form = adapter.get().forms().connection();
        Map<String, Set<Secret.Kind>> uses = new TreeMap<>();
        for (String field : form.secrets()) {
            if (!(body.values().get(field) instanceof String s) || !s.startsWith(REFERENCE)) continue;
            Set<Secret.Kind> takes = EnumSet.copyOf(form.kinds(field));
            uses.merge(s.substring(REFERENCE.length()), takes, (a, b) -> {
                Set<Secret.Kind> both = EnumSet.copyOf(a);
                both.retainAll(b);
                return both.isEmpty() ? a : both;
            });
        }
        return uses;
    }

    // ---------------------------------------------------------------------------------------------------------------- consent

    /** What {@code body} sends, as {@link Servers} computes it: nothing for a mocked or removed connection, or a system. */
    private Sends sendsOf(Adapter adapter, Body body, String what) {
        if (body.mock() != null) return new Sends(new TreeSet<>(), new TreeSet<>());
        try {
            return Servers.of(adapter.forms().connection(), body.values(), body.allowedHosts().stream().map(String.class::cast).toList(),
                    adapter.addressSchemes());
        } catch (Servers.Invalid refused) {
            throw invalid("values." + refused.field(), "the " + what + "'s " + refused.getMessage());
        }
    }

    /** The pairs {@code body} of {@code ref} sends, empty when it sends none or cannot be read as a connection's body. */
    private SortedSet<Send> pairs(Ref ref, JsonObject body) {
        if (ref.kind() != Ref.Kind.CONNECTION || body == null || body.getBoolean("removed", false) || !body.containsKey("adapter")) return new TreeSet<>();
        Body b = Body.of(body);
        Optional<Adapter> adapter = adapters.apply(b.adapter());
        if (adapter.isEmpty()) return new TreeSet<>();
        try {
            return new TreeSet<>(sendsOf(adapter.get(), b, "body").pairs());
        } catch (ConfigRefusal unreadable) {
            return new TreeSet<>();
        }
    }

    /** Every pair that a verified version of the connection sent: the version in force and each activation of its history. */
    private Set<Send> consented(ConfigRows.Tx tx, ConfigRows.ObjectRow r) {
        Set<Send> consented = new HashSet<>();
        if (verifies(r)) consented.addAll(pairs(r.ref, r.body));
        for (ConfigRows.History h : tx.activations(r.ref)) if (verifies(h)) consented.addAll(pairs(h.ref, h.body));
        return consented;
    }

    /** The pairs the draft of {@code r} needs consent for, with their digest; empty when it needs none. */
    private Optional<Needed> needed(ConfigRows.Tx tx, ConfigRows.ObjectRow r) {
        if (r.ref.kind() != Ref.Kind.CONNECTION || r.draft == null) return Optional.empty();
        return Servers.needs(new Sends(new TreeSet<>(), pairs(r.ref, r.draft)), consented(tx, r));
    }

    /** Whether the row's draft waits for {@code needs}: any draft for its activation, one whose consent is not given for its consent. */
    private boolean waits(ConfigRows.Tx tx, ConfigRows.ObjectRow r, Needs needs) {
        return r.draft != null && (needs == Needs.ACTIVATION || waitsForConsent(tx, r));
    }

    private boolean waitsForConsent(ConfigRows.Tx tx, ConfigRows.ObjectRow r) {
        Optional<Needed> needed = needed(tx, r);
        return needed.isPresent() && !(r.consent != null && needed.get().hex().equals(HEX.formatHex(r.consent)) && consentVerifies(r));
    }

    // ---------------------------------------------------------------------------------------------------------------- binding

    /** The array a body in force is bound over. */
    static List<Object> bodyArray(Ref ref, Long incarnation, Integer version, Long activation, Integer format, JsonObject body) {
        List<Object> array = new ArrayList<>();
        array.add("rail8 config v1");
        array.add(ref.kind().id());
        array.add(ref.key());
        array.add(decimal(incarnation));
        array.add(decimal(version));
        array.add(decimal(activation));
        array.add(decimal(format));
        array.add(body);
        return array;
    }

    /** The array a consent is bound over. */
    private static List<Object> consentArray(ConfigRows.ObjectRow r, String digest) {
        List<Object> array = new ArrayList<>();
        array.add("rail8 consent v1");
        array.add(r.ref.kind().id());
        array.add(r.ref.key());
        array.add(decimal(r.incarnation));
        array.add(decimal(r.draftId));
        array.add(decimal(r.activation));
        array.add(digest);
        return array;
    }

    private static String decimal(Number n) {
        return n == null ? null : n.toString();
    }

    /** Whether the body in force of {@code r} is bound and its tag verifies. */
    private boolean verifies(ConfigRows.ObjectRow r) {
        if (r.version == 0 || r.keyId == null || r.tag == null) return false;
        return secrets.verify(new SecretStore.Binding(r.keyId, r.tag), Jcs.bytes(bodyArray(r.ref, r.incarnation, r.version, r.activation, r.format, r.body)));
    }

    private boolean verifies(ConfigRows.History h) {
        if (h.keyId == null || h.tag == null || h.incarnation == null || h.activation == null || h.version == null) return false;
        return secrets.verify(new SecretStore.Binding(h.keyId, h.tag), Jcs.bytes(bodyArray(h.ref, h.incarnation, h.version, h.activation, h.format, h.body)));
    }

    private boolean consentVerifies(ConfigRows.ObjectRow r) {
        if (r.consent == null || r.consentKeyId == null || r.consentTag == null) return false;
        return secrets.verify(new SecretStore.Binding(r.consentKeyId, r.consentTag), Jcs.bytes(consentArray(r, HEX.formatHex(r.consent))));
    }

    private SecretStore.Binder binder(ConfigRows.Tx tx) {
        return secrets.binder(tx.connection()).await();
    }

    private static SecretStore.Binding bind(SecretStore.Binder binder, byte[] bytes) {
        try {
            return binder.bind(bytes);
        } catch (SecretRefused refused) {
            throw new ConfigRefusal(refused.status(), RefusalCode.KEYS_DIFFER, refused.getMessage(), refused.values());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- tokens

    /** The {@code etag} of {@code r}, or of a key no row holds when it is null. */
    static String etag(ConfigRows.ObjectRow r) {
        List<Object> array = new ArrayList<>();
        array.add("rail8 etag v1");
        array.add(r == null ? null : decimal(r.incarnation));
        array.add(r == null ? "0" : decimal(r.revision));
        array.add(r == null ? null : decimal(r.draftId));
        array.add(r == null || r.consent == null ? null : HEX.formatHex(r.consent));
        array.add(r == null || r.consentAt == null ? null : r.consentAt.toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Jcs.sha256(array));
    }

    /** The {@code run} token of {@code r}: its incarnation and run counter in hex. */
    static String run(ConfigRows.ObjectRow r) {
        return Long.toHexString(r.incarnation) + "." + Integer.toHexString(r.run);
    }

    private void expect(ConfigRows.Tx tx, Ref ref, Optional<ConfigRows.ObjectRow> row, String expect) {
        String now = row.map(ConfigObjects::etag).orElse(EMPTY_ETAG);
        if (!now.equals(expect)) throw changed(tx, ref, row, ref + " changed since it was read");
    }

    /** A 412 answering the object as {@code row} holds it now. */
    private ConfigRefusal changed(ConfigRows.Tx tx, Ref ref, Optional<ConfigRows.ObjectRow> row, String text) {
        return new ConfigRefusal(412, RefusalCode.CHANGED, text, values(ref), new JsonObject(), view(tx, ref, row));
    }

    // ---------------------------------------------------------------------------------------------------------------- views

    private ObjectView view(ConfigRows.Tx tx, Ref ref, Optional<ConfigRows.ObjectRow> row) {
        ConfigRows.Head head = tx.head(false);
        return view(tx, ref, row, new Overview(head.generation(), head.stamp(), head.mocksAllowed(), tx.nodes()));
    }

    /**
     * The object as the screen shows it: what is in force and the draft, each with its values or instance name; for a draft its
     * changes against the version in force, and for a connection's draft what an activation would refuse ({@code problems}) and
     * the consent it needs; the stop; the secrets the values name; whether each live node holds the version in force; the
     * deployed uses with their digest; and the adapter's connection form. Never a secret's value.
     */
    private ObjectView view(ConfigRows.Tx tx, Ref ref, Optional<ConfigRows.ObjectRow> row, Overview overview) {
        boolean connection = ref.kind() == Ref.Kind.CONNECTION;
        JsonObject json = new JsonObject().put("kind", ref.kind().id()).put("key", ref.key());
        Optional<String> deployed = connection ? tx.deployedAdapter(ref.key()) : Optional.empty();
        if (connection) {
            json.put("system", ref.key().substring(0, ref.key().lastIndexOf('/')));
            deployed.ifPresent(a -> json.put("deployedAdapter", a));
        }
        Optional<String> adapterName = connection ? row.flatMap(r -> Optional.ofNullable(r.body).or(() -> Optional.ofNullable(r.draft)))
                .flatMap(b -> Optional.ofNullable(b.getString("adapter"))).or(() -> deployed) : Optional.empty();
        adapterName.ifPresent(a -> json.put("adapter", a));
        Optional<Adapter> adapter = adapterName.flatMap(adapters);
        if (row.isEmpty()) {
            json.put("etag", EMPTY_ETAG).put("revision", 0).put("version", 0).putNull("activation").putNull("inForce").putNull("draft");
            if (connection) json.putNull("run").putNull("stopped").put("secrets", new JsonArray());
        } else {
            ConfigRows.ObjectRow r = row.get();
            json.put("etag", etag(r)).put("revision", r.revision).put("version", r.version).put("activation", decimal(r.activation));
            json.put("inForce", r.version == 0 ? null : inForceView(tx, r));
            json.put("draft", r.draft == null ? null : draftView(tx, r, adapter));
            if (connection) {
                json.put("run", run(r));
                json.put("stopped", r.stoppedAt == null ? null : new JsonObject().put("at", r.stoppedAt.toString()).put("by", r.stoppedBy));
                json.put("secrets", secretsView(adapter, r.draft != null ? r.draft : r.body));
            }
        }
        JsonArray nodes = new JsonArray();
        for (NodeReport n : overview.nodes()) {
            String holds = overview.holds(n, ref);
            JsonObject node = new JsonObject().put("node", n.node()).put("holds", holds);
            if (!holds.equals("yes")) {
                n.generation().ifPresent(g -> node.put("generation", g));
                n.readAt().ifPresent(at -> node.put("readAt", at.toString()));
            }
            nodes.add(node);
        }
        json.put("nodes", nodes);
        List<ConfigRows.Use> uses = connection ? tx.uses(ref.key()) : tx.usesOfSystem(ref.key());
        JsonArray flows = new JsonArray();
        for (ConfigRows.Use u : uses.subList(0, Math.min(uses.size(), MAX_PAGE))) {
            JsonObject use = new JsonObject().put("flow", u.flow()).put("step", u.step()).put("direction", u.direction());
            if (connection) use.put("address", u.address());
            else use.put("connection", u.connection());
            flows.add(use);
        }
        json.put("usedBy", new JsonObject().put("digest", usesDigest(uses)).put("count", uses.size()).put("flows", flows));
        if (connection) json.put("form", adapter.map(a -> new JsonObject(a.forms().connection().toJson())).orElse(null));
        return new ObjectView(json);
    }

    /** The version in force as the screen shows it: a connection's adapter, values and mock, or a system's instance name. */
    private JsonObject inForceView(ConfigRows.Tx tx, ConfigRows.ObjectRow r) {
        JsonObject inForce = new JsonObject().put("version", r.version).put("removed", r.body == null);
        if (r.body != null) bodyView(r.ref, r.body, inForce);
        inForce.put("activatedAt", r.activatedAt.toString()).put("activatedBy", r.activatedBy)
                .put("generation", tx.version(r.ref, r.version).map(h -> h.generation).orElse(null))
                .put("binding", r.keyId != null && verifies(r) ? "ok" : "fails");
        if (r.keyId != null) inForce.put("keyId", HEX.formatHex(r.keyId));
        return inForce;
    }

    /** The draft as the screen shows it, with its changes, and for a connection the problems an activation would have and its consent. */
    private JsonObject draftView(ConfigRows.Tx tx, ConfigRows.ObjectRow r, Optional<Adapter> adapter) {
        boolean removed = r.draft.getBoolean("removed", false);
        JsonObject draft = new JsonObject().put("removed", removed);
        if (!removed) bodyView(r.ref, r.draft, draft);
        draft.put("savedAt", r.draftAt.toString()).put("savedBy", r.draftBy).put("changes", diff(inForceBody(r), r.draft));
        if (r.ref.kind() != Ref.Kind.CONNECTION) return draft;
        JsonArray problems = new JsonArray();
        if (!removed && !r.draft.containsKey("mock") && adapter.isPresent()) {
            Body body = Body.of(r.draft);
            Map<String, Secret.Kind> kinds = tx.kinds(secretUses(r.draft).keySet());
            lines(adapter.get(), body, kinds).values().forEach(l -> l.forEach(problems::add));
        }
        draft.put("problems", problems);
        draft.put("sends", sends(pairs(r.ref, r.draft)));
        Optional<Needed> needed = needed(tx, r);
        draft.put("consent", needed.map(n -> {
            boolean given = r.consent != null && n.hex().equals(HEX.formatHex(r.consent)) && consentVerifies(r);
            return new JsonObject().put("digest", n.hex()).put("sends", sends(n.pairs()))
                    .put("given", given ? new JsonObject().put("at", r.consentAt.toString()).put("by", r.consentBy) : null);
        }).orElse(null));
        return draft;
    }

    /** Puts a body's fields into {@code into}: a connection's adapter, values and mock, or a system's instance name. */
    private static void bodyView(Ref ref, JsonObject body, JsonObject into) {
        if (ref.kind() == Ref.Kind.SYSTEM) {
            into.put("instanceName", body.getString("instanceName"));
            return;
        }
        into.put("adapter", body.getString("adapter")).put("values", body.getValue("values")).put("mock", body.getValue("mock"));
    }

    /** The secrets {@code body}'s secret fields name, each with its field and the kinds the field takes; never a value. */
    private static JsonArray secretsView(Optional<Adapter> adapter, JsonObject body) {
        JsonArray secrets = new JsonArray();
        if (adapter.isEmpty() || body == null || !(body.getValue("values") instanceof JsonObject values)) return secrets;
        Settings form = adapter.get().forms().connection();
        for (String field : form.secrets()) {
            if (!(values.getValue(field) instanceof String s) || !s.startsWith(REFERENCE)) continue;
            JsonArray kinds = new JsonArray();
            form.kinds(field).stream().sorted().forEach(k -> kinds.add(k.id()));
            secrets.add(new JsonObject().put("field", field).put("name", s.substring(REFERENCE.length())).put("kinds", kinds));
        }
        return secrets;
    }

    private static JsonArray sends(Set<Send> pairs) {
        JsonArray array = new JsonArray();
        for (Send p : pairs) array.add(new JsonObject().put("secret", p.secret()).put("server", p.server()));
        return array;
    }

    private static HistoryRow historyRow(ConfigRows.History h) {
        JsonObject json = new JsonObject().put("id", Long.toString(h.id)).put("at", h.at.toString()).put("actor", h.actor).put("via", h.via)
                .put("change", h.change).put("revision", h.revision).put("diff", h.diff.copy());
        if (h.generation != null) json.put("generation", h.generation);
        if (h.version != null) json.put("version", h.version);
        if (h.note != null) json.put("note", h.note);
        return new HistoryRow(json);
    }

    private static HistoryRow act(ConfigRows.SecretAct a) {
        return new HistoryRow(new JsonObject().put("at", a.at().toString()).put("actor", a.actor()).put("change", a.action()).put("secret", a.name()));
    }

    /** The names a body's values name as {@code secret://<name>}. */
    private static Set<String> secretNames(JsonObject body) {
        Set<String> names = new TreeSet<>();
        if (body == null || !(body.getValue("values") instanceof JsonObject values)) return names;
        for (Map.Entry<String, Object> e : values) {
            if (e.getValue() instanceof String s && s.startsWith(REFERENCE)) names.add(s.substring(REFERENCE.length()));
        }
        return names;
    }

    // ---------------------------------------------------------------------------------------------------------------- rows

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** A new random id: non-zero, and held by no row nor drawn before in this write. */
    private long draw(ConfigRows.Tx tx, Set<Long> drawn) {
        while (true) {
            long id = random.nextLong();
            if (id != 0 && !drawn.contains(id) && !tx.used(id)) {
                drawn.add(id);
                return id;
            }
        }
    }

    /** Raises the generation with a new stamp and writes the switch of mocks; answers the new generation. */
    private long raise(ConfigRows.Tx tx, ConfigRows.Head head, boolean mocksAllowed, Set<Long> drawn) {
        long stamp;
        do {
            stamp = random.nextLong();
        } while (stamp == 0 || stamp == head.stamp());
        long generation = head.generation() + 1;
        tx.head(new ConfigRows.Head(generation, stamp, mocksAllowed));
        return generation;
    }

    /** Writes the history row of a change of {@code r} and trims the object's history to its limit. */
    private ConfigRows.History history(ConfigRows.Tx tx, ConfigRows.ObjectRow r, Actor actor, Instant at, String change, JsonArray diff,
                                              String note, Long generation) {
        ConfigRows.History h = new ConfigRows.History();
        h.ref = r.ref;
        h.at = at;
        h.actor = actor.who().name();
        h.userId = actor.who().userOrNull();
        h.via = actor.who().via();
        h.credential = actor.who().credential().orElse(null);
        h.change = change;
        h.revision = r.revision;
        h.diff = diff;
        h.note = note;
        h.generation = generation;
        if (change.equals("activate")) {
            h.version = r.version;
            h.format = r.format;
            h.body = r.body == null ? null : r.body.copy();
            h.incarnation = r.incarnation;
            h.activation = r.activation;
            h.keyId = r.keyId;
            h.tag = r.tag;
        }
        h.id = tx.add(h);
        tx.trim(r.ref, limits.history(), limits.keptActivations());
        return h;
    }

    private JsonObject inForceBody(ConfigRows.ObjectRow r) {
        if (r.version == 0 || r.body == null) return r.version == 0 ? new JsonObject() : new JsonObject().put("removed", true);
        return r.body;
    }

    private static boolean sameAsInForce(ConfigRows.ObjectRow r, JsonObject draft) {
        if (draft.getBoolean("removed", false)) return r.body == null;
        return r.body != null && Jcs.write(r.body).equals(Jcs.write(draft));
    }

    /** The fields that differ between two bodies, each {@code {field, from, to}}: a connection's values one by one. */
    static JsonArray diff(JsonObject from, JsonObject to) {
        Map<String, Object> a = flat(from);
        Map<String, Object> b = flat(to);
        TreeSet<String> fields = new TreeSet<>(a.keySet());
        fields.addAll(b.keySet());
        JsonArray diff = new JsonArray();
        for (String f : fields) {
            Object x = a.get(f);
            Object y = b.get(f);
            if (!Jcs.write(x).equals(Jcs.write(y))) diff.add(new JsonObject().put("field", f).put("from", x).put("to", y));
        }
        return diff;
    }

    private static Map<String, Object> flat(JsonObject body) {
        Map<String, Object> flat = new TreeMap<>();
        if (body == null) return flat;
        for (Map.Entry<String, Object> e : body) {
            if (e.getKey().equals("values") && e.getValue() instanceof JsonObject values) {
                for (Map.Entry<String, Object> v : values) flat.put("values." + v.getKey(), v.getValue());
            } else {
                flat.put(e.getKey(), e.getValue());
            }
        }
        return flat;
    }

    /** Whether a deployed system names {@code ref}: a system by its key, a connection by its name in its system's definition. */
    private static boolean named(ConfigRows.Tx tx, Ref ref) {
        return ref.kind() == Ref.Kind.SYSTEM ? tx.deployed(ref.key()) : tx.deployedAdapter(ref.key()).isPresent();
    }

    private static JsonObject withRemote(Actor actor, JsonObject detail) {
        return detail.put("remote", actor.who().remote());
    }

    private static JsonObject values(Ref ref) {
        return new JsonObject().put("kind", ref.kind().id()).put("key", ref.key());
    }

    private static void requireExpect(String expect) {
        if (expect == null) throw badRequest("a write states the etag it saw as expect");
    }

    private static void connectionOnly(Ref ref, String what) {
        if (ref.kind() != Ref.Kind.CONNECTION) throw badRequest(what + " is a connection's");
    }

    private static ConfigRefusal badRequest(String text) {
        return new ConfigRefusal(400, RefusalCode.BAD_REQUEST, text, new JsonObject());
    }

    private static ConfigRefusal invalid(String field, String text) {
        return new ConfigRefusal(422, RefusalCode.INVALID, field + ": " + text, new JsonObject().put("field", field));
    }

    private static ConfigRefusal noDraft(Ref ref) {
        return new ConfigRefusal(409, RefusalCode.NO_DRAFT, ref + " has no draft", values(ref));
    }

    private static ConfigRefusal nothingToConsent(Ref ref, String why) {
        return new ConfigRefusal(409, RefusalCode.NOTHING_TO_CONSENT, ref + " " + why, values(ref));
    }

    private ConfigRefusal notFound(ConfigRows.Tx tx, Ref ref) {
        return new ConfigRefusal(404, RefusalCode.NOT_FOUND, "no configuration of " + ref + " is held", values(ref).put("etag", EMPTY_ETAG));
    }

    /** A connection's body of format 1, read as it is stored. */
    private record Body(String adapter, Map<String, Object> values, JsonObject mock) {

        static Body of(JsonObject body) {
            JsonObject values = body.getValue("values") instanceof JsonObject v ? v : new JsonObject();
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : values) {
                Object value = e.getValue() instanceof JsonArray a ? a.getList() : e.getValue();
                map.put(e.getKey(), value);
            }
            return new Body(body.getString("adapter"), map, body.getValue("mock") instanceof JsonObject m ? m : null);
        }

        List<?> allowedHosts() {
            return values.get(ALLOWED_HOSTS) instanceof List<?> l ? l : List.of();
        }
    }

    @Override
    public String toString() {
        return "ConfigObjects[" + rows + "]";
    }
}
