package dev.rail8.runtime.admin;

import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobNotFoundException;
import dev.rail8.api.BodyLostException;
import dev.rail8.api.BlobStore;
import dev.rail8.api.Envelope;
import dev.rail8.api.Hash;
import dev.rail8.api.Health;
import dev.rail8.api.plugin.AdminService;
import dev.rail8.core.admission.TargetLimit;
import dev.rail8.core.audit.Actor;
import dev.rail8.core.dlq.Dlq;
import dev.rail8.core.dlq.FailedMessages;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.flow.FlowDefinition;
import dev.rail8.core.flow.Runs;
import dev.rail8.core.flow.WorkSignals;
import dev.rail8.core.flow.Workers;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.core.record.MessageStore;
import dev.rail8.core.record.Page;
import dev.rail8.core.record.Snapshot;
import dev.rail8.core.record.StepCpu;
import dev.rail8.ingress.ServerCertificate;
import dev.rail8.runtime.access.Access;
import dev.rail8.runtime.access.AccessRefused;
import dev.rail8.runtime.access.Action;
import dev.rail8.runtime.access.Caller;
import dev.rail8.runtime.access.Kind;
import dev.rail8.runtime.access.MemoryAccess;
import dev.rail8.runtime.access.PackageRef;
import dev.rail8.runtime.access.Role;
import dev.rail8.runtime.access.Via;
import dev.rail8.runtime.deploy.DeployRefusedException;
import dev.rail8.runtime.deploy.DeployResult;
import dev.rail8.runtime.deploy.DeployedFlow;
import dev.rail8.runtime.deploy.DeployedPackage;
import dev.rail8.runtime.deploy.DeploymentStore;
import dev.rail8.runtime.deploy.Deployments;
import dev.rail8.runtime.deploy.UndeployResult;
import dev.rail8.runtime.config.Change;
import dev.rail8.runtime.config.ConfigRefusal;
import dev.rail8.runtime.config.ConfigStore;
import dev.rail8.runtime.config.Ref;
import dev.rail8.runtime.secret.SecretRefused;
import dev.rail8.runtime.secret.SecretStore;
import dev.rail8.runtime.git.CredentialsCheck;
import dev.rail8.runtime.pg.PgTx;
import dev.rail8.runtime.plugin.Plugins;
import dev.rail8.runtime.queue.NotAdmittedException;
import dev.rail8.runtime.refusal.RefusalCode;
import dev.rail8.runtime.store.PgMessageList;
import dev.rail8.runtime.store.StepLogJson;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The admin read API over the stores: a message's timeline with wall and CPU nanos per step (the rows of
 * every attempt by attempt, then step, each naming its {@code attempt} and showing the lines the step's code logged as its
 * {@code log}, {@link StepLogJson}), the payload after step n (the latest attempt's,
 * or {@code ?attempt=k}'s), the search for messages and the DLQ a page at a time ({@code {items, next|null}}), {@code /health},
 * and the probes {@code /live} and {@code /ready}. {@code GET /admin/messages} finds messages by business key
 * ({@code ?key=name:value}, {@link MessageStore#findByKey}) or by a step they passed ({@code ?flow=<flow id>&step=<step name>},
 * {@link MessageStore#findByStep}), with {@code limit} and {@code after}, each item {@code {id, flow}} with the qualified
 * {@code <package>/<flow>}; it is 400 for neither, for a key with a flow or step,
 * and for a flow without a step or a step without a flow. With {@code from} or {@code to} it is instead the list of recent
 * messages ({@link PgMessageList}): {@code from} and {@code to} are ISO-8601 instants, both required, and {@code flow},
 * {@code status} (completed, failed, running or cancelled), {@code key=name:value} and {@code step} narrow it; its items are
 * {@code {id, flow, status, startedAt, durationNanos, keys}}, and it is 400 for a range the list refuses, for an instant or a
 * status that does not parse, and on a server built without the list. {@code GET /admin/message-counts?from=&to=} counts the
 * messages of that list in each status ({@link PgMessageList#counts}), of {@code flow} when it is given: an object
 * {@code {completed, failed, running, cancelled}}, 400 for the same refusals as the list.
 *
 * <p>A verticle owns the socket and routes on its event loop; with a certificate ({@link Config#tls}) the socket serves HTTPS
 * only; every route body runs on a fresh virtual thread — it awaits
 * the stores, encodes the answer and checks its headers there — and the answer is written back on the verticle's context.
 * {@code /health} is open and shows status only, never a check's detail. {@code /live} and {@code /ready} are open too:
 * {@code /live} answers 200 {@code {"status":"UP"}} while the server runs and reads nothing; {@code /ready} reads its
 * {@link Readiness} inputs on the request, awaits nothing, and answers 200 {@code {"status":"UP","checks":{..}}} when every
 * one is UP, else 503 {@code {"status":"DOWN","checks":{..}}}. Every error body is {@code {error, code, values}}, the English
 * text, its {@link RefusalCode} and the values the text names (in this description {@code {error}} stands for them): 400 for a
 * parameter that does not parse or a limit or cursor a page store refuses, 404 for what is not
 * there (a message, a step, a body recorded as metadata only, bytes no longer stored), 405 for a method a route does not
 * take, 503 for a request the node's Postgres pool refused a connection (its wait elapsed, its wait queue full, or the
 * pool closed by a stopping node: retryable), whether a statement on the pool was refused or a transaction's
 * acquire ({@link dev.rail8.runtime.pg.PoolRefusedException}): the reads, the work queue's discard, the DLQ
 * {@code /retry}'s read and act and {@code /cancel}'s act with a fixed text as {@code error}, a deploy's work-queue question or record write and an
 * undeploy's work-queue question or record delete with the refusal's own line (below); 500 for anything else: a store that
 * failed, a recorded header no response may carry, a stored body whose stream fails before the head is out (after it, the
 * response is reset).
 *
 * <p>With a {@link Deployments} it also deploys from git: {@code POST /admin/deploy} with the body
 * {@code {"repository": "<name>", "commit": "<40 hex, or a ref in dev and ci>", "expect": "<40 hex>" | "none" | "any"}} and
 * nothing else (else 400; above {@link #DEPLOY_BODY_MAX} bytes 413): {@code expect} is the state the deploy expects to replace,
 * the commit of the package's record, {@code none} for no record or {@code any} for whatever the pool records; a check may
 * leave it out and then compares nothing. The actor is the authenticated caller (the shared token and the open loopback API name
 * it by their {@code X-Rail8-Actor} header, 400 above {@link #ACTOR_MAX} characters). The
 * deploy runs on the route's virtual thread: 200 {@code {package, version, repository, commit, flows, added, swapped, removed,
 * replaces}} (the flows sorted qualified flow ids: a redeploy swaps only the flows that changed; {@code replaces} the commit of the
 * record the deploy replaced, or {@code none}), or a refusal by its kind — 400, 403, 409 (another deploy runs on the node, or the
 * package is deployed from another repository), 412 {@code {error, replaces}} (the pool records another state than
 * {@code expect}: {@code replaces} is the one it records), 422 {@code {error, problems}}, 500
 * ({@code {error}}; a redeploy whose start failed and rolled back: {@code {error, problems}}), 503 (the node stops; or the node's
 * Postgres pool refused the deploy record's write: the same body as the 500 of a record not written, or its work-queue question:
 * {@code {error}} naming the flow and the pool's refusal; only when every failed question was the pool's — a real failure of
 * another flow's question is 500). With {@code ?check=true} (a {@code check} other than {@code true} or {@code false} is 400) it
 * answers what the deploy would do, the same answer, or its refusal: nothing that runs changes and no record is written.
 * {@code GET /admin/flows} and
 * {@code GET /admin/packages} list what runs, sorted, not paged; a flow shows its effective {@code hash} and its {@code state}
 * ({@code RUNNING}, {@code RESTARTING} or {@code FAILED}; a RESTARTING one also {@code restart: {failures, next, cause}}) and the
 * {@code commit} its package was deployed from (null for a deploy with no origin), a package its {@code repository} and
 * {@code commit} (null for a deploy with no origin), its {@code state} and its {@code problems} (FAILED when the restore could not install it, with no
 * flows, no files, no folders and no artifacts) and its tree: {@code folders:[{path, namespace|null, description|null}]}
 * and {@code artifacts:[{path, kind, id|null}]}, each sorted by path, its {@code content} digest (null when FAILED) and its
 * {@code dependencies:[{name, version, content, repository, commit, direct}]}, the closure its deploy resolved.
 * {@code DELETE /admin/packages/{name}} undeploys on the route's virtual thread: 200 {@code {name, version, flowsRemoved}}
 * (its flows' qualified ids, sorted), or a refusal by the deploy's status map — 400, 403, 409 (another deploy or undeploy runs on
 * the node, or the work queue holds items of a flow of it), 503 (the node stops; or the
 * node's Postgres pool refused the work queue's question or the record delete: nothing changed, retryable; a refused
 * question only when no other question failed otherwise and no flow holds items — those are 500 and 409) — and 404 for a package the node does not list; a record
 * delete or a queue question that fails otherwise is 500. {@code GET /admin/pool/packages} answers what the pool runs and what each
 * node of it runs, read in one transaction: 200 {@code {records:[{name, version, repository, commit}], nodes:[{node,
 * packages:[{name, version, repository, commit, deployedAt, state, behind}]}]}}, the records and each node's packages sorted by
 * name, the nodes whose lease holds sorted by id, {@code behind} the lines of why the node's last install of the package's record
 * was refused; 500 when the store fails, or holds more rows and records together than one read takes.
 * {@code GET /admin/targets} lists the node's limits per target system, sorted by target: 200
 * {@code {items:[{target, maxCalls, maxWaitMs, inFlight, waiting, refused}]}}, every target a built flow named so far; a limit of a
 * component that names no target is not listed. {@code GET /admin/components} lists every component the node serves, sorted by
 * scheme: 200 {@code {environment, httpOrigin, items:[{scheme, httpPrefix, settings}]}}, {@code environment} the node's
 * {@code RAIL8_ENV}, {@code httpOrigin} the origin senders call its HTTP port at ({@code RAIL8_HTTP_ORIGIN}, null when not set),
 * {@code httpPrefix} the path the component's in endpoints are served under on that port (null when it takes no requests there),
 * and {@code settings} the component's two forms, its reach and its text keys ({@code Forms.toJson}, format 2), as the UI draws
 * its forms from them. {@code GET /admin/steps} lists every step type a module or plugin of the node builds, sorted by type: 200
 * {@code {items:[{type, configSchema}]}}, {@code configSchema} the JSON Schema of the step's options, or null when the factory's
 * is no JSON object; the built-in steps are not listed. Without one these routes do not exist (404).
 *
 * <p>With a {@link Deployments} it also shows a deployed flow's CPU by step: {@code GET /admin/flows/{flow}/cpu} — the flow's
 * qualified id as one path segment, its slash percent-encoded — answers, with a {@link Sampling}, 200 {@code {flow, sampling:"on",
 * period, since, items:[{step, name, executions, samples, cpuNanos}], totals:{samples, attributed, pending, unattributed, platform,
 * lost, dropped}}}: the node's CPU-sampling aggregate since the sampler started, the items by CPU descending, the totals node-wide
 * ({@link StepCpu}); never a per-message figure. Once the sampler's stream has failed, {@code sampling:"failed"} with the rest as the
 * stream last fed it. Without one, 200 {@code {flow, sampling:"off", items:[]}}. A flow not deployed here is 404.
 *
 * <p>With a {@link WorkQueue} it shows and releases held work items: {@code GET /admin/work/held?limit&after} pages every
 * flow's held rows in seq order ({@code {items:[{seq, messageId, flow, step, partitionKey, attempts, heldAt}], next|null}});
 * {@code POST /admin/work/{seq}/release} answers 200 {@code {seq}}, 404 for a seq unknown or not held, 400 for a seq that is
 * not a long. A release wakes the row's flow in the node's {@link Workers} (every flow when the queue cannot tell the row) and
 * announces it through the node's {@link WorkSignals} to the node whose journal holds its body ({@code *} when unknown);
 * built without them, a release wakes and announces nothing. Each held row also carries {@code blocks}, the rows of its
 * key behind it. A row that stops its key — a message of an ordered flow that failed for good, parked, the rows of its key
 * waiting behind it until an operator retries or cancels it — is not among the held rows: {@code GET /admin/work/stopped?limit&after}
 * pages those the same way ({@code {items:[{seq, messageId, flow, step, partitionKey, attempts, stoppedAt, blocks, error|null}],
 * next|null}}, {@code error} the parked failure's text), and {@code /release} does not release one (404). {@code GET /admin/work/lost?limit&after} pages the lost rows — their body given up by an accepted
 * {@code RAIL8_JOURNAL_ACCEPT_LOSS} — the same way ({@code {items:[{seq, messageId, flow, partitionKey, lostAt, blocks}],
 * next|null}}); {@code POST /admin/work/{seq}/discard} deletes a lost row and writes one {@code audit} row in the same transaction,
 * answering 200 {@code {seq, auditId}}, 404 for a seq unknown or not lost, 400 for a seq that is not a long; the audit names
 * the caller ({@link Caller#acting}). A discard wakes the row's flow and announces it to the node of its key's next row, as a release does. Without a
 * queue these routes do not exist (404).
 *
 * <p>With a {@link FailedMessages}, the operator acts on a parked message, each act audited with the caller as {@code /discard}
 * names it. With a {@link Deployments} too, {@code POST /admin/dlq/{id}/retry} runs a message its flow's worker parked once more
 * under its own id (a message keeps one id), as attempt k + 1 of the row's own bound: in place when it stops its key, else at
 * the tail of its key; 202 {@code {id, attempt}}, 404 (no DLQ row), 409 (its flow not deployed here, a work row of the message not
 * settled yet or its body lost, its ingress body no longer kept), 422 saying why (a flow that is not at-least-once, a row its
 * worker did not park, or a keyed flow's row parked before its key was kept). {@code POST /admin/dlq/{id}/cancel} ends a parked
 * message on any node, deployed or not, and so frees its key: 200 {@code {id, auditId, cancelledAt}}, 404 (no DLQ row), 409 (a
 * work row of the message not settled yet), 400 (a body other than none or {@code {"reason"}}), 413 (a body above
 * {@link #CANCEL_BODY_MAX} bytes). {@code POST /admin/messages/{id}/cancel} ends an at-least-once message wherever it is, with the
 * same body and answers: a parked one as the DLQ cancel does (200, {@code state: "ended"}); one queued, waiting or running has its
 * work row marked and its run stopped (202 {@code {id, auditId, cancelledAt, state: "stopping", node}}); 404 for a message with
 * neither, sync and at-most-once runs among them. There is no {@code /restart} route (404).
 *
 * <p>With {@link Modules}, each admin service a module brings is a route, {@code <method> /admin/modules/<plugin>/<path>}, that
 * runs the service's calls on virtual threads of their own, at most 4 of one plugin at once ({@link ModuleCalls}); its own
 * answers are problems ({@code application/problem+json}), not {@code {error}}. {@code GET /admin/modules} lists the node's
 * modules, then its plugins, as they loaded: 200 {@code {items:[{kind, name, version|null, api, sha256|null, from|null, brings:
 * {schemes, steps, languages, services:[{method, path}], command}}]}}, each list of {@code brings} sorted, {@code command} true
 * when it brings a command of the command line, and {@code items} empty without modules.
 *
 * <p>Every {@code /admin/**} request goes in one order. First the browser guard: a request that carries an {@code Origin}
 * header, or a {@code Sec-Fetch-Site} other than {@code none}, is 403 {@code {error, code: "browser"}}: what a browser sends,
 * never the CLI. Then {@code POST /admin/sessions}, the login, takes no credential; on a listener that refuses logins
 * ({@link Config#refusesLogins}: plain HTTP on a bind other than loopback, without {@code RAIL8_ADMIN_TLS=off}) it is 403
 * {@code {error, code: "tls-required"}} before its body is read, and so is any request that presents a session's credential
 * or a named token, known by its form before the store is asked. Every
 * other request is authenticated
 * ({@link Access#authenticate}) from {@code Authorization: Bearer <credential>} before the router looks at its path, so a
 * path no route takes is authenticated too: a session; the emergency key, a {@code RAIL8_ADMIN_TOKEN} of its form; and while
 * the bridge is open, the shared token, a {@code RAIL8_ADMIN_TOKEN} of another form, and on a node without one a request
 * without a credential. A refusal is 401 {@code {error, code, values}} with {@code WWW-Authenticate: Bearer}, or 503. Then the
 * router's 404 and 405, and the door: the caller must be allowed the route's action ({@link Caller#require}, 403
 * {@code {error, code, values, needs}}, {@code needs} the roles that grant it), before any body is read. Then the body (400, 413) and
 * the route, which asks {@link Caller#acting} for the {@link Actor} of its audit row. A request with the emergency key, a
 * 404 or 405 included, and one with the shared token for an action of the key's other than {@code self.read}, is written to
 * the audit before anything runs, 503 when it cannot be. A plugin's service answers these refusals as problems of type
 * {@code urn:rail8:admin:problem:unauthenticated} and {@code forbidden}.
 *
 * <p>The users and their sessions ({@link Access}): {@code POST /admin/sessions {name, password, device?}} (at most
 * {@link #LOGIN_BODY_MAX} bytes; a {@code device}, the browser's device credential or {@code ""}, asks for a new one and picks
 * the row of the login limit) answers 201 {@code {session, expiresAt, idleSeconds, device?, me}}, or 401 {@code login-refused},
 * 429 {@code too-many-attempts} with {@code Retry-After}, 503 {@code busy}.
 * {@code GET /admin/me} answers {@code {name, kind, roles, actions, refused, via, firstPassword, expiresAt, emergencyKey}};
 * {@code POST /admin/me/password {current, next}} and {@code POST /admin/me/logout} 204. {@code GET /admin/users} lists every
 * user ({@code {items}}) and {@code GET /admin/users/{name}} shows one: {@code {name, kind, roles:[{role, grantedAt, grantedBy,
 * grantedVia}], blockedAt, firstPassword, createdAt, createdBy, sessions:[{id, createdAt, lastSeenAt, expiresAt}], tokens}},
 * {@code tokens} its live tokens as the lists of tokens show them.
 * {@code POST /admin/users {name, kind, roles}} answers 201 {@code {name, firstPassword}} for a person, {@code {name}} for a
 * technical account; {@code POST /admin/users/{name}/roles {add, remove}} 200 {@code {name, roles, added, removed}}, each role
 * checked by its own action ({@code administrators.change} for the administrator role, {@code roles.change} for any other);
 * {@code /block}, {@code /unblock} and {@code /sessions/end} 200 {@code {name}}, {@code /password-reset} 200
 * {@code {firstPassword}}. Their bodies are at most {@link #USERS_BODY_MAX} bytes. An invariant a change would break is 409.
 *
 * <p>The tokens ({@link Access}), each shown by its id and never its secret: {@code GET /admin/me/tokens} lists the caller's
 * own live tokens and {@code GET /admin/tokens?expiringWithin=P14D} every user's (the parameter optional, at most
 * {@code P365D}), soonest expiry first, as {@code {tokens:[{id, user, name, createdAt, createdBy, expiresAt, lastUsedAt}],
 * maxDays, defaultDays}}. {@code POST /admin/me/tokens {name, days?, password}} issues one of the caller's own in a session,
 * the password asked again (403 {@code wrong-password}, 429 as the login), and {@code POST /admin/users/{name}/tokens {name,
 * days?}} one of a technical account (409 for a person): 201 {@code {id, name, expiresAt, token}}, the token shown once.
 * {@code POST /admin/me/tokens/{id}/revoke} revokes one of the caller's own and {@code POST /admin/tokens/{id}/revoke} anyone's:
 * 204, and 404 for an id it does not find. {@code POST /admin/tokens/ceiling {maxDays}} sets the installation's ceiling of a
 * token's life, 1 to 365 days: 200 {@code {maxDays, defaultDays}}. {@code GET /admin/audit} reads the whole audit a page at
 * a time, newest first, narrowed by {@code user}, {@code action}, {@code from} and {@code to}.
 * A view of a body ({@code message.body}) is written to the audit before any byte of it is sent, 503 when it cannot be.
 *
 * @since 0.1
 */
public final class AdminServer {

    /** The page size when a request names no {@code limit}; the stores refuse more than {@link Page#MAX_LIMIT}. */
    public static final int DEFAULT_LIMIT = 50;

    private static final System.Logger LOG = System.getLogger(AdminServer.class.getName());

    /** How long before its {@code notAfter} the listener's certificate is named in the {@code notices} of {@code /health}. */
    static final Duration CERTIFICATE_NOTICE = Duration.ofDays(14);

    /** The header a caller of the bridge declares its name in, for the audit row; and its length at most. */
    static final String ACTOR = "X-Rail8-Actor";
    static final int ACTOR_MAX = Access.ACTOR_MAX;
    /** The largest body {@code POST /admin/dlq/{id}/cancel} reads, in bytes: a larger one is 413. */
    static final int CANCEL_BODY_MAX = 8 * 1024;

    /** The largest body of {@code POST /admin/deploy}, in bytes. */
    static final int DEPLOY_BODY_MAX = 4 * 1024;
    /** The largest body of a secret's create or overwrite (bytes): a plain value of 32 KiB, escaped, or a key pair with its certificates. */
    static final int SECRET_BODY_MAX = 64 * 1024;
    /** The largest body of a login, {@code POST /admin/sessions}, in bytes. */
    static final int LOGIN_BODY_MAX = 1024;
    /** The largest body of the routes of users and of the caller's own password, in bytes. */
    static final int USERS_BODY_MAX = 4 * 1024;
    /** The largest body of a write of the configuration, in bytes: a save carries values of 64 KiB. */
    static final int CONFIG_BODY_MAX = 128 * 1024;
    /** The metadata of a route matched by a pattern: its path as the table of actions writes it, such as {@code /admin/config/connections/{key}}. */
    static final String PATH = "rail8.path";
    /**
     * A key of the configuration in a path: its segments with their {@code /}, none of them {@code -}, which starts the verb. The
     * key's rules are the parser's, which the route asks.
     */
    private static final String KEY = "(?<key>(?!-(?:/|$))[^/]*(?:/(?!-(?:/|$))[^/]*)*)";
    /**
     * The metadata of every route of {@code /admin}: {@value #ACTIONS} the list of actions its door lets through, or for the routes
     * mounted without one — the browser guard, the login and the authentication, and on a listener that refuses logins the
     * refusal of a login before it, {@code plain HTTP} — {@value #UNCHECKED} their name.
     */
    static final String ACTIONS = "rail8.actions";
    static final String UNCHECKED = "rail8.unchecked";
    private static final String CALLER = "rail8.caller";       // where the authentication leaves the request's Caller
    private static final String BODY_MAX = "rail8.body-max";   // where the door names the limit of its route's body, for the 413
    private static final String MODULES = "/admin/modules/";
    private static final String JSON = "application/json";
    private static final String PROBLEM = "application/problem+json";
    private static final String OCTETS = "application/octet-stream";
    /** The refusal of a login, a session or a named token on a listener that {@link Config#refusesLogins refuses them}. */
    private static final String PLAIN_HTTP = "the admin API on this address serves plain HTTP: it takes no login, no session and no named token here until"
            + " the node has a certificate (RAIL8_ADMIN_TLS_CERT and RAIL8_ADMIN_TLS_KEY) or RAIL8_ADMIN_TLS=off";
    /** What the router answers itself: no route, a method the route does not take, a request it cannot read, a handler that threw. */
    private static final Map<Integer, Map.Entry<RefusalCode, String>> ROUTER_ERRORS = Map.of(400, Map.entry(RefusalCode.BAD_REQUEST, "bad request"),
            404, Map.entry(RefusalCode.NO_ROUTE, "no such route"), 405, Map.entry(RefusalCode.METHOD_NOT_ALLOWED, "method not allowed"),
            500, Map.entry(RefusalCode.INTERNAL_ERROR, "internal error"));

    /**
     * Where the API listens, the token {@code /admin/**} needs and how the listener is encrypted. A blank token is no token; a
     * host that is not a loopback address literal (a host name included: where it resolves is not known here) without a token is
     * refused. With a certificate the listener serves HTTPS only. Without one, on a host that is not a loopback literal, it
     * refuses logins, sessions and named tokens ({@link #refusesLogins}) unless {@code tlsOff} states that the network to it is protected
     * another way; a certificate and {@code tlsOff} together are refused.
     *
     * @param host the bind address
     * @param port the port; 0 binds any free one
     * @param token the bearer token, empty for none
     * @param tls the certificate the listener serves ({@code RAIL8_ADMIN_TLS_CERT} and {@code RAIL8_ADMIN_TLS_KEY}), empty for plain HTTP
     * @param tlsOff {@code RAIL8_ADMIN_TLS=off}: plain HTTP takes logins, sessions and named tokens on any bind
     */
    public record Config(String host, int port, Optional<String> token, Optional<ServerCertificate> tls, boolean tlsOff) {
        public Config {
            Objects.requireNonNull(host, "host");
            token = token.filter(t -> !t.isBlank());
            Objects.requireNonNull(tls, "tls");
            if (token.isEmpty() && !loopback(host)) {
                throw new IllegalArgumentException("the admin API on " + host + " is not loopback-only: it needs a token (RAIL8_ADMIN_TOKEN)");
            }
            if (tls.isPresent() && tlsOff) {
                throw new IllegalArgumentException("RAIL8_ADMIN_TLS=off and a certificate (RAIL8_ADMIN_TLS_CERT and RAIL8_ADMIN_TLS_KEY) are both set:"
                        + " the admin API serves HTTPS with the certificate, or plain HTTP with off");
            }
        }

        /** Plain HTTP, without {@code RAIL8_ADMIN_TLS=off}. */
        public Config(String host, int port, Optional<String> token) {
            this(host, port, token, Optional.empty(), false);
        }

        /** Whether the listener refuses logins, sessions and named tokens: plain HTTP on a host that is not a loopback literal, without off. */
        public boolean refusesLogins() {
            return tls.isEmpty() && !tlsOff && !loopback(host);
        }

        /** The token redacted: a log line never carries it. */
        @Override
        public String toString() {
            return "Config[host=" + host + ", port=" + port + ", token=" + token.map(t -> "***") + ", tls=" + tls + ", tlsOff=" + tlsOff + "]";
        }

        private static boolean loopback(String host) {
            try {
                return InetAddress.ofLiteral(host).isLoopbackAddress();
            } catch (IllegalArgumentException aHostName) {
                return false;
            }
        }
    }

    /**
     * What {@code /health} reports: each check by name (a check that fails is DOWN), the snapshot writes that failed, and —
     * only when there is one — the node's {@code notices}: facts an operator should act on that fail nothing (a
     * generated master key), never a key or a check's detail. The server adds one of its own while the listener's certificate
     * expires within {@link #CERTIFICATE_NOTICE} by the node's clock, or has expired, judged on each request.
     *
     * @param checks the checks, by name
     * @param snapshotWriteFailures read on every request
     * @param notices shown as the answer's {@code notices} array when not empty
     * @param readiness what {@code /ready} reports
     * @param git the last outcome of the check of the git credentials, read on every request: {@code /health} shows its state and
     *            when it was checked, {@code GET /admin/git} the repository and the reason too; it changes no check's status
     */
    public record HealthProbe(Map<String, Supplier<Future<Health>>> checks, LongSupplier snapshotWriteFailures, List<String> notices, Readiness readiness,
                              Supplier<Optional<CredentialsCheck.Outcome>> git) {
        public HealthProbe {
            checks = Map.copyOf(checks);
            Objects.requireNonNull(snapshotWriteFailures, "snapshotWriteFailures");
            notices = List.copyOf(notices);
            Objects.requireNonNull(readiness, "readiness");
            Objects.requireNonNull(git, "git");
        }

        /** No check of the git credentials: {@code credentials} is null. */
        public HealthProbe(Map<String, Supplier<Future<Health>>> checks, LongSupplier snapshotWriteFailures, List<String> notices, Readiness readiness) {
            this(checks, snapshotWriteFailures, notices, readiness, Optional::empty);
        }

        /** No readiness inputs: {@code /ready} is UP on every check while the server answers ({@link Readiness#ALWAYS}). */
        public HealthProbe(Map<String, Supplier<Future<Health>>> checks, LongSupplier snapshotWriteFailures, List<String> notices) {
            this(checks, snapshotWriteFailures, notices, Readiness.ALWAYS);
        }

        /** No notices, no readiness inputs ({@link Readiness#ALWAYS}). */
        public HealthProbe(Map<String, Supplier<Future<Health>>> checks, LongSupplier snapshotWriteFailures) {
            this(checks, snapshotWriteFailures, List.of());
        }
    }

    /**
     * What {@code /ready} reports: four in-memory inputs, true is UP, each read on every request and never awaited — so
     * {@code /ready} needs no deadline.
     *
     * @param lifecycle the node finished its start and its close has not begun
     * @param fence the node lease's fence is open
     * @param journal the journal is UP: neither failed nor in outage (DEGRADED is DOWN here)
     * @param deployments the deployments check is not DOWN: a package or a flow listed FAILED or RESTARTING keeps the node ready
     */
    public record Readiness(BooleanSupplier lifecycle, BooleanSupplier fence, BooleanSupplier journal, BooleanSupplier deployments) {
        /** Every input UP: what a server built without readiness inputs reports. */
        public static final Readiness ALWAYS = new Readiness(() -> true, () -> true, () -> true, () -> true);

        public Readiness {
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(fence, "fence");
            Objects.requireNonNull(journal, "journal");
            Objects.requireNonNull(deployments, "deployments");
        }
    }

    /**
     * What {@code GET /admin/flows/{flow}/cpu} shows when the node runs a CPU sampler.
     *
     * @param period the sampling period
     * @param since when the sampler started: the aggregate is cumulative since then
     * @param cpu the sampler's aggregate
     * @param failed whether the sampler's stream has failed, read at every request
     */
    public record Sampling(Duration period, Instant since, StepCpu cpu, BooleanSupplier failed) {
        public Sampling {
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(since, "since");
            Objects.requireNonNull(cpu, "cpu");
            Objects.requireNonNull(failed, "failed");
        }
    }

    /**
     * The node's modules and plugins, the services of the admin API that its modules bring, and the clock each call of one is
     * given.
     *
     * @param listed   each module, then each plugin, with what it brings, as {@code GET /admin/modules} lists them
     * @param services each module's services, by the module's name
     * @param clock    the node's clock
     */
    public record Modules(List<Plugins.Listed> listed, Map<String, List<AdminService>> services, Clock clock) {
        /** No modules, no plugins and no services. */
        public static final Modules NONE = new Modules(List.of(), Map.of(), Clock.systemUTC());

        public Modules {
            listed = List.copyOf(listed);
            services = services.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
            Objects.requireNonNull(clock, "clock");
        }
    }

    private final Config config;
    private final MessageStore messages;
    private final Dlq dlq;
    private final BlobStore blobs;
    private final HealthProbe health;
    private final Optional<Deployments> deployments;
    private final Optional<WorkQueue> work;
    private final Optional<FailedMessages> failed;
    private final Optional<Sampling> sampling;
    private final Optional<PgMessageList> list;
    private final Workers workers;
    private final WorkSignals signals;
    private final Modules modules;
    private final Access access;
    private volatile Supplier<Future<Void>> undeploy = Future::succeededFuture;
    private volatile Router router;
    private volatile Optional<Configuration> configuration = Optional.empty();

    /** The read API only: no deploy routes. */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health) {
        this(config, messages, dlq, blobs, health, Optional.empty());
    }

    /** The read API and the services of {@code modules}: no deploy routes. */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Modules modules) {
        this(config, messages, dlq, blobs, health, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), new Workers(),
                WorkSignals.NONE, modules);
    }

    /** The read API and, with {@code deployments}, the deploy routes; no work routes. */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments) {
        this(config, messages, dlq, blobs, health, deployments, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), new Workers(), WorkSignals.NONE);
    }

    /** The read API, with {@code deployments} the deploy routes, and the held-work routes over {@code work}; no operator's acts. */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work) {
        this(config, messages, dlq, blobs, health, deployments, work, new Workers(), WorkSignals.NONE);
    }

    /**
     * The read API, with {@code deployments} the deploy routes, and the held-work routes over {@code work}, a release waking its
     * flow in {@code workers} and announcing it through {@code signals}; no operator's acts.
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, Workers workers, WorkSignals signals) {
        this(config, messages, dlq, blobs, health, deployments, Optional.of(work), Optional.empty(), Optional.empty(), Optional.empty(), workers, signals);
    }

    /**
     * The read API, the held-work routes over {@code work} and, with {@code deployments}, the deploy routes and the flows' CPU,
     * sampled when {@code sampling} is there; no operator's acts.
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, Optional<Sampling> sampling) {
        this(config, messages, dlq, blobs, health, deployments, work, sampling, new Workers(), WorkSignals.NONE);
    }

    /**
     * {@link #AdminServer(Config, MessageStore, Dlq, BlobStore, HealthProbe, Optional, WorkQueue, Optional)}, a release waking its
     * flow in {@code workers} and announcing it through {@code signals}; no operator's acts.
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, Optional<Sampling> sampling, Workers workers, WorkSignals signals) {
        this(config, messages, dlq, blobs, health, deployments, Optional.of(work), Optional.empty(), sampling, Optional.empty(), workers, signals);
    }

    /**
     * The node's server: {@link #AdminServer(Config, MessageStore, Dlq, BlobStore, HealthProbe, Optional, WorkQueue, Optional, Workers,
     * WorkSignals)} and the operator's acts on a parked message over {@code failed} — the cancel, and with {@code deployments} the
     * retry — each waking its flow in {@code workers} and announcing it through {@code signals}.
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, FailedMessages failed, Optional<Sampling> sampling, Workers workers, WorkSignals signals) {
        this(config, messages, dlq, blobs, health, deployments, Optional.of(work), Optional.of(failed), sampling, Optional.empty(), workers, signals);
    }

    /**
     * {@link #AdminServer(Config, MessageStore, Dlq, BlobStore, HealthProbe, Optional, WorkQueue, FailedMessages, Optional, Workers,
     * WorkSignals)} and the list of recent messages over {@code list}.
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, FailedMessages failed, Optional<Sampling> sampling, PgMessageList list, Workers workers, WorkSignals signals) {
        this(config, messages, dlq, blobs, health, deployments, work, failed, sampling, list, workers, signals, Modules.NONE);
    }

    /**
     * The node's server with its modules' services:
     * {@link #AdminServer(Config, MessageStore, Dlq, BlobStore, HealthProbe, Optional, WorkQueue, FailedMessages, Optional, PgMessageList,
     * Workers, WorkSignals)} and the services of {@code modules}.
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, FailedMessages failed, Optional<Sampling> sampling, PgMessageList list, Workers workers, WorkSignals signals,
                       Modules modules) {
        this(config, messages, dlq, blobs, health, deployments, work, failed, sampling, list, workers, signals, modules,
                MemoryAccess.bridge(config.token()));
    }

    /**
     * The node's server with its users:
     * {@link #AdminServer(Config, MessageStore, Dlq, BlobStore, HealthProbe, Optional, WorkQueue, FailedMessages, Optional, PgMessageList,
     * Workers, WorkSignals, Modules)}, every {@code /admin} request authenticated by {@code access}. The other constructors
     * authenticate with a {@link MemoryAccess#bridge} of the config's token: no user, the bridge open.
     */
    public AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                       WorkQueue work, FailedMessages failed, Optional<Sampling> sampling, PgMessageList list, Workers workers, WorkSignals signals,
                       Modules modules, Access access) {
        this(config, messages, dlq, blobs, health, deployments, Optional.of(work), Optional.of(failed), sampling, Optional.of(list), workers, signals,
                modules, access);
    }

    private AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                        Optional<WorkQueue> work, Optional<FailedMessages> failed, Optional<Sampling> sampling, Optional<PgMessageList> list,
                        Workers workers, WorkSignals signals) {
        this(config, messages, dlq, blobs, health, deployments, work, failed, sampling, list, workers, signals, Modules.NONE);
    }

    private AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                        Optional<WorkQueue> work, Optional<FailedMessages> failed, Optional<Sampling> sampling, Optional<PgMessageList> list,
                        Workers workers, WorkSignals signals, Modules modules) {
        this(config, messages, dlq, blobs, health, deployments, work, failed, sampling, list, workers, signals, modules, MemoryAccess.bridge(config.token()));
    }

    /**
     * The server with each part optional: the deploy routes with {@code deployments}, the work routes with {@code work}, the acts
     * on a parked message with {@code failed}, the list by time with {@code list}, every {@code /admin} request authenticated by
     * {@code access}.
     */
    AdminServer(Config config, MessageStore messages, Dlq dlq, BlobStore blobs, HealthProbe health, Optional<Deployments> deployments,
                Optional<WorkQueue> work, Optional<FailedMessages> failed, Optional<Sampling> sampling, Optional<PgMessageList> list,
                Workers workers, WorkSignals signals, Modules modules, Access access) {
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.dlq = Objects.requireNonNull(dlq, "dlq");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.health = Objects.requireNonNull(health, "health");
        this.deployments = Objects.requireNonNull(deployments, "deployments");
        this.work = work;
        this.failed = failed;
        this.sampling = Objects.requireNonNull(sampling, "sampling");
        this.list = list;
        this.workers = Objects.requireNonNull(workers, "workers");
        this.signals = Objects.requireNonNull(signals, "signals");
        this.modules = Objects.requireNonNull(modules, "modules");
        this.access = Objects.requireNonNull(access, "access");
    }

    /**
     * The configuration of the environment that {@code /admin/config} serves: its store; the read of it this node's environment
     * makes, which a write awaits so that its change holds on this node when it is answered; and the secret store whose list says
     * of each secret a connection names whether it is set, of which kind, and when and by whom it last changed.
     */
    public record Configuration(ConfigStore store, Supplier<Future<Void>> reread, Optional<SecretStore> secrets) {
        public Configuration {
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(reread, "reread");
            Objects.requireNonNull(secrets, "secrets");
        }
    }

    /** Serves the routes of {@code /admin/config} from {@code configuration}; called before {@link #start}. */
    public AdminServer configuration(Configuration configuration) {
        this.configuration = Optional.of(Objects.requireNonNull(configuration, "configuration"));
        return this;
    }

    /**
     * Deploys the listening verticle; completes with the bound port (the actual one when configured 0). Once it listens, it logs
     * a WARNING for {@code RAIL8_ADMIN_TLS=off}, and one for a listener that refuses logins, sessions and named tokens.
     */
    public Future<Integer> start(Vertx vertx) {
        Listener listener = new Listener();
        return vertx.deployVerticle(listener).map(id -> {
            undeploy = () -> vertx.undeploy(id);
            int port = listener.server.actualPort();
            String where = config.host() + ":" + port;
            if (config.tlsOff()) {
                LOG.log(System.Logger.Level.WARNING, "RAIL8_ADMIN_TLS=off: the admin API on " + where + " takes passwords and credentials over"
                        + " plain HTTP; the network between the UI server, the command line and the nodes must be protected another way");
            } else if (config.refusesLogins()) {
                LOG.log(System.Logger.Level.WARNING, "the admin API on " + where + " serves plain HTTP to other hosts: it refuses logins,"
                        + " sessions and named tokens until it has a certificate (RAIL8_ADMIN_TLS_CERT and RAIL8_ADMIN_TLS_KEY), or until RAIL8_ADMIN_TLS=off"
                        + " states that the network to it is protected another way; the emergency key, the shared token and the first passwords"
                        + " given to them still cross it in clear");
            }
            return port;
        });
    }

    /** Undeploys the verticle, which closes the socket; a second stop, or one before start, has nothing to do. */
    public Future<Void> stop() {
        Supplier<Future<Void>> running = undeploy;
        undeploy = Future::succeededFuture;
        return running.get();
    }

    /**
     * The router once the server started, for a test that walks its routes: each route of {@code /admin} carries its actions, or
     * its name when it is one of those without, as the metadata {@link #ACTIONS} and {@link #UNCHECKED}.
     */
    Router router() {
        return Objects.requireNonNull(router, "the server has not started");
    }

    /** The verticle: the socket, the router and the context every answer is written on. */
    private final class Listener extends VerticleBase {

        volatile HttpServer server;

        @Override
        public Future<?> start() {
            Router routes = Router.router(vertx);
            routes.get("/health").handler(offload(context, (c, who) -> health(c)));
            routes.get("/live").handler(offload(context, (c, who) -> Json.of(200, new JsonObject().put("status", Health.Status.UP.name()))));   // reads nothing
            routes.get("/ready").handler(offload(context, (c, who) -> ready(c)));
            // the routes of /admin without an action, in this order: the guard, the login, the authentication; on a listener that
            // refuses logins, a refusal of the login comes before the login, so a password sent there is never read
            routes.route("/admin/*").putMetadata(UNCHECKED, "browser guard").handler(AdminServer::browserGuard);
            if (config.refusesLogins()) {
                routes.post("/admin/sessions").putMetadata(UNCHECKED, "plain HTTP").handler(ctx -> refuse(ctx, failed(plainHttp(), ctx)));
            }
            routes.post("/admin/sessions").putMetadata(UNCHECKED, "login").handler(BodyHandler.create(false).setBodyLimit(LOGIN_BODY_MAX)).handler(offload(context, (c, who) -> login(c)));
            routes.route("/admin/*").putMetadata(UNCHECKED, "authentication").handler(this::authenticate);
            Admin admin = new Admin(routes, context);
            new ModuleCalls(modules, context).mount(admin);
            admin.get("/admin/me", Action.SELF_READ, (c, who) -> Json.of(200, me(who, access.expiresAt(who))));
            admin.post("/admin/me/password", Action.SELF_PASSWORD, USERS_BODY_MAX, AdminServer.this::password);
            admin.post("/admin/me/logout", Action.SELF_LOGOUT, (c, who) -> {
                access.logout(who.acting(Action.SELF_LOGOUT, PackageRef.ANY, remote(c)));
                return NO_CONTENT;
            });
            admin.get("/admin/users", Action.USER_READ, (c, who) -> users());
            admin.get("/admin/users/:name", Action.USER_READ, (c, who) -> user(c.pathParam("name")));
            admin.post("/admin/users", Action.USER_CREATE, USERS_BODY_MAX, AdminServer.this::createUser);
            admin.post("/admin/users/:name/roles", Action.ROLES_CHANGE, Action.ADMINISTRATORS_CHANGE, USERS_BODY_MAX, AdminServer.this::roles);
            admin.post("/admin/users/:name/block", Action.USER_BLOCK, (c, who) -> {
                access.block(who.acting(Action.USER_BLOCK, PackageRef.ANY, remote(c)), c.pathParam("name"));
                return named(c);
            });
            admin.post("/admin/users/:name/unblock", Action.USER_BLOCK, (c, who) -> {
                access.unblock(who.acting(Action.USER_BLOCK, PackageRef.ANY, remote(c)), c.pathParam("name"));
                return named(c);
            });
            admin.post("/admin/users/:name/password-reset", Action.USER_PASSWORD_RESET, (c, who) -> Json.of(200, new JsonObject()
                    .put("firstPassword", access.resetPassword(who.acting(Action.USER_PASSWORD_RESET, PackageRef.ANY, remote(c)), c.pathParam("name")))));
            admin.post("/admin/users/:name/sessions/end", Action.SESSION_END, (c, who) -> {
                access.endSessions(who.acting(Action.SESSION_END, PackageRef.ANY, remote(c)), c.pathParam("name"));
                return named(c);
            });
            admin.get("/admin/me/tokens", Action.SELF_TOKENS, (c, who) -> tokens(access.ownTokens(who)));
            admin.post("/admin/me/tokens", Action.SELF_TOKEN_ISSUE, USERS_BODY_MAX, AdminServer.this::issueOwnToken);
            admin.post("/admin/me/tokens/:id/revoke", Action.SELF_TOKENS, (c, who) -> {
                access.revokeOwnToken(who.acting(Action.SELF_TOKENS, PackageRef.ANY, remote(c)), c.pathParam("id"));
                return NO_CONTENT;
            });
            admin.post("/admin/users/:name/tokens", Action.TOKEN_ISSUE, USERS_BODY_MAX, AdminServer.this::issueToken);
            admin.get("/admin/tokens", Action.TOKEN_READ, (c, who) -> tokens(access.tokens(expiringWithin(c))));
            admin.get("/admin/audit", Action.AUDIT_READ, (c, who) -> wholeAudit(c));
            admin.post("/admin/tokens/ceiling", Action.TOKEN_CEILING, USERS_BODY_MAX, AdminServer.this::ceiling);
            admin.post("/admin/tokens/:id/revoke", Action.TOKEN_REVOKE, (c, who) -> {
                access.revokeToken(who.acting(Action.TOKEN_REVOKE, PackageRef.ANY, remote(c)), c.pathParam("id"));
                return NO_CONTENT;
            });
            admin.get("/admin/modules", Action.DEPLOYMENT_READ, (c, who) -> listed(modules));
            admin.get("/admin/git", Action.GIT_READ, (c, who) -> git());
            admin.get("/admin/messages", Action.MESSAGE_READ, (c, who) -> search(c));
            admin.get("/admin/message-counts", Action.MESSAGE_READ, (c, who) -> counts(c));
            admin.get("/admin/messages/:id", Action.MESSAGE_READ, (c, who) -> timeline(c));
            admin.get("/admin/messages/:id/audit", Action.MESSAGE_READ, (c, who) -> audit(c));
            admin.get("/admin/messages/:id/steps/:n/body", Action.MESSAGE_BODY, AdminServer.this::body);
            admin.get("/admin/dlq", Action.MESSAGE_READ, (c, who) -> dlq(c));
            deployments.ifPresent(d -> {
                admin.post("/admin/deploy", Action.PACKAGE_DEPLOY, DEPLOY_BODY_MAX, (c, who) -> AdminServer.deploy(c, who, d));
                admin.delete("/admin/packages/:name", Action.PACKAGE_UNDEPLOY, (c, who) -> undeployed(c, who, d));
                admin.get("/admin/flows", Action.DEPLOYMENT_READ, (c, who) -> flows(d));
                admin.get("/admin/packages", Action.DEPLOYMENT_READ, (c, who) -> packages(d));
                admin.get("/admin/pool/packages", Action.DEPLOYMENT_READ, (c, who) -> poolPackages(d));
                admin.get("/admin/targets", Action.DEPLOYMENT_READ, (c, who) -> targets(d));
                admin.get("/admin/components", Action.DEPLOYMENT_READ, (c, who) -> components(d));
                admin.get("/admin/steps", Action.DEPLOYMENT_READ, (c, who) -> steps(d));
                admin.get("/admin/flows/:flow/cpu", Action.DEPLOYMENT_READ, (c, who) -> cpu(c, d));
            });
            deployments.flatMap(Deployments::secretStore).ifPresent(store -> {
                admin.get("/admin/secrets", Action.SECRET_LIST, (c, who) -> Json.of(200, store.list().await()));
                admin.get("/admin/secrets/:name", Action.SECRET_LIST, (c, who) -> secret(c, store));
                admin.post("/admin/secrets", Action.SECRET_WRITE, SECRET_BODY_MAX, (c, who) -> Json.of(201,
                        store.create(who.acting(Action.SECRET_WRITE, PackageRef.ANY, remote(c)), secretBody(c, true)).await()));
                admin.post("/admin/secrets/:name", Action.SECRET_WRITE, SECRET_BODY_MAX, (c, who) -> Json.of(200,
                        store.overwrite(who.acting(Action.SECRET_WRITE, PackageRef.ANY, remote(c)), c.pathParam("name"), secretBody(c, false)).await()));
                admin.delete("/admin/secrets/:name", Action.SECRET_DELETE, (c, who) -> {
                    store.delete(who.acting(Action.SECRET_DELETE, PackageRef.ANY, remote(c)), c.pathParam("name"),
                            Optional.ofNullable(c.request().getParam("confirm"))).await();
                    return NO_CONTENT;
                });
            });
            configuration.ifPresent(c -> mountConfiguration(admin, c));
            work.ifPresent(q -> {
                admin.get("/admin/work/held", Action.MESSAGE_READ, (c, who) -> held(c, q));
                admin.get("/admin/work/stopped", Action.MESSAGE_READ, (c, who) -> stopped(c, q));
                admin.post("/admin/work/:seq/release", Action.WORK_RELEASE, (c, who) -> release(c, who, q, workers, signals));
                admin.get("/admin/work/lost", Action.MESSAGE_READ, (c, who) -> lost(c, q));
                admin.post("/admin/work/:seq/discard", Action.WORK_DISCARD, (c, who) -> discard(c, who, q, workers, signals));
            });
            failed.ifPresent(f -> {
                deployments.ifPresent(d -> admin.post("/admin/dlq/:id/retry", Action.MESSAGE_RETRY, (c, who) -> retry(c, who, d, f)));
                // a body read into memory: bounded, nothing written to disk
                admin.post("/admin/dlq/:id/cancel", Action.MESSAGE_CANCEL, CANCEL_BODY_MAX, (c, who) -> cancel(c, who, f));
                admin.post("/admin/messages/:id/cancel", Action.MESSAGE_CANCEL, CANCEL_BODY_MAX, (c, who) -> end(c, who, f));
            });
            ROUTER_ERRORS.forEach((status, reason) -> routes.errorHandler(status,
                    ctx -> unrouted(ctx, status, Json.of(status, error(reason.getKey(), reason.getValue(), Map.of())))));
            // the door names the limit of its route's body; the login is the one route that reads a body without a door
            routes.errorHandler(413, ctx -> {
                int max = Objects.requireNonNullElse(ctx.<Integer>get(BODY_MAX), LOGIN_BODY_MAX);
                refuse(ctx, Json.of(413, error(RefusalCode.TOO_LARGE, "the request body is above " + max + " bytes", Map.of("max", max))));
            });
            router = routes;
            HttpServerOptions plain = new HttpServerOptions().setHost(config.host()).setPort(config.port());
            return vertx.createHttpServer(config.tls().map(c -> c.secure(plain)).orElse(plain)).requestHandler(routes).listen()
                    .map(listening -> server = listening);
        }

        @Override
        public Future<?> stop() {
            return server.close();
        }

        /**
         * Every request of {@code /admin} but the login: its caller from its credential and its {@value #ACTOR} header, on a virtual
         * thread — the read of the store blocks — left in the context for the door; or its refusal, before any body is read. The
         * request is paused meanwhile, so its body waits for the route's body handler.
         */
        private void authenticate(RoutingContext ctx) {
            HttpServerRequest req = ctx.request();
            Optional<String> authorization = Optional.ofNullable(req.getHeader(HttpHeaders.AUTHORIZATION));
            if (config.refusesLogins() && (Access.presentsSession(authorization) || Access.presentsToken(authorization))) {   // by its form: the store is not asked
                refuse(ctx, failed(plainHttp(), ctx));
                return;
            }
            req.pause();
            Optional<String> declared = Optional.ofNullable(req.getHeader(ACTOR));
            Thread.ofVirtual().name("rail8-admin").start(() -> {
                Caller who;
                try {
                    who = access.authenticate(authorization, declared);
                } catch (Throwable refused) {   // a refusal, the pool's, or a store that failed: answered, never a hang
                    Reply reply = failed(refused, ctx);
                    context.runOnContext(v -> refuse(ctx, reply));
                    return;
                }
                context.runOnContext(v -> {
                    ctx.put(CALLER, who);
                    ctx.next();
                });
            });
        }

        /**
         * A 400, 404, 405 or 500 of the router: a request of the emergency key that no route takes (404, 405) is written to the
         * audit first, as not allowed, as the door writes the others.
         */
        private void unrouted(RoutingContext ctx, int status, Reply reply) {
            Caller who = ctx.get(CALLER);
            if (who == null || who.via() != Via.EMERGENCY_KEY || status != 404 && status != 405) {
                refuse(ctx, reply);
                return;
            }
            HttpServerRequest req = ctx.request();
            String method = req.method().name();
            String path = req.path();
            String remote = remote(ctx);
            Thread.ofVirtual().name("rail8-admin").start(() -> {
                Reply written = reply;
                try {
                    access.keyUsed(who, method, path, remote, Optional.empty(), false);
                } catch (Throwable unwritten) {
                    written = failed(unwritten, ctx);
                }
                Reply answered = written;
                context.runOnContext(v -> refuse(ctx, answered));
            });
        }
    }

    /**
     * Mounts the routes of {@code /admin}, each behind the door of its actions: the only way such a route is mounted. The route's
     * body takes the {@link Caller} the door let through; each route carries its actions as the metadata {@link #ACTIONS}.
     */
    final class Admin {

        private final Router router;
        private final Context context;

        Admin(Router router, Context context) {
            this.router = router;
            this.context = context;
        }

        /** {@code GET path}, for a caller allowed {@code action}. */
        void get(String path, Action action, Route body) {
            mount(HttpMethod.GET, path, List.of(action), 0, body);
        }

        /** {@code POST path}, for a caller allowed {@code action}; a body is not read. */
        void post(String path, Action action, Route body) {
            mount(HttpMethod.POST, path, List.of(action), 0, body);
        }

        /** {@code POST path}, for a caller allowed {@code action}; its body, at most {@code bodyMax} bytes, is read after the door. */
        void post(String path, Action action, int bodyMax, Route body) {
            mount(HttpMethod.POST, path, List.of(action), bodyMax, body);
        }

        /** {@code POST path}, for a caller allowed {@code either} or {@code or}; its body is read after the door. */
        void post(String path, Action either, Action or, int bodyMax, Route body) {
            mount(HttpMethod.POST, path, List.of(either, or), bodyMax, body);
        }

        /** {@code DELETE path}, for a caller allowed {@code action}. */
        void delete(String path, Action action, Route body) {
            mount(HttpMethod.DELETE, path, List.of(action), 0, body);
        }

        /** {@code method path}, for a caller allowed {@code action}, answered by {@code handler} on the event loop: a module's service. */
        void call(HttpMethod method, String path, Action action, Handler<RoutingContext> handler) {
            router.route(method, path).putMetadata(ACTIONS, List.of(action)).handler(door(List.of(action), 0)).handler(handler);
        }

        /**
         * {@code method path}, where {@code path} holds {@code {key}}: a key of the configuration with its {@code /}, which the
         * route reads as the path parameter {@code key}; for a caller allowed {@code action}, its body, at most {@code bodyMax}
         * bytes and none when 0, read after the door. The route carries {@code path} as the metadata {@link #PATH}.
         */
        void keyed(HttpMethod method, String path, Action action, int bodyMax, Route body) {
            String regex = "^" + Pattern.quote(path).replace("{key}", "\\E" + KEY + "\\Q") + "$";
            mount(() -> router.routeWithRegex(method, regex).putMetadata(PATH, path), List.of(action), bodyMax, body);
        }

        private void mount(HttpMethod method, String path, List<Action> actions, int bodyMax, Route body) {
            mount(() -> router.route(method, path), actions, bodyMax, body);
        }

        /**
         * The door, then the route, each on a route {@code route} makes. A route that reads a body has the door on a route of its
         * own just before it, as Vert.x runs a body handler only before the others of its route: the body is read into memory
         * after the door, bounded, nothing written to disk.
         */
        private void mount(Supplier<io.vertx.ext.web.Route> route, List<Action> actions, int bodyMax, Route body) {
            io.vertx.ext.web.Route mounted = route.get().putMetadata(ACTIONS, actions).handler(door(actions, bodyMax));
            if (bodyMax > 0) {
                mounted = route.get().putMetadata(ACTIONS, actions).handler(BodyHandler.create(false).setBodyLimit(bodyMax));
            }
            mounted.handler(offload(context, body));
        }

        /**
         * The door, on the event loop: the caller must be allowed one of {@code actions} — the first it may do, else the first is
         * refused — before any body is read. A use of the emergency key, and of the shared token for an action of the key's other
         * than {@code self.read}, is written to the audit first, on a virtual thread; a row not written is 503 and nothing runs.
         * {@code bodyMax} is the largest body the route reads, for the text of its 413; 0 when it reads none.
         */
        private Handler<RoutingContext> door(List<Action> actions, int bodyMax) {
            return ctx -> {
                if (bodyMax > 0) ctx.put(BODY_MAX, bodyMax);
                Caller who = ctx.get(CALLER);
                Action asked = actions.stream().filter(a -> who.may(a, PackageRef.ANY)).findFirst().orElse(actions.getFirst());
                if (!keyUse(who, asked)) {
                    pass(ctx, who, asked);
                    return;
                }
                HttpServerRequest req = ctx.request();
                String method = req.method().name();
                String path = req.path();
                String remote = remote(ctx);
                Thread.ofVirtual().name("rail8-admin").start(() -> {
                    try {
                        access.keyUsed(who, method, path, remote, Optional.of(asked), who.may(asked, PackageRef.ANY));
                    } catch (Throwable unwritten) {
                        Reply reply = failed(unwritten, ctx);
                        context.runOnContext(v -> refuse(ctx, reply));
                        return;
                    }
                    context.runOnContext(v -> pass(ctx, who, asked));
                });
            };
        }

        private static void pass(RoutingContext ctx, Caller who, Action asked) {
            try {
                who.require(asked, PackageRef.ANY);
            } catch (AccessRefused refused) {
                refuse(ctx, failed(refused, ctx));
                return;
            }
            ctx.next();
        }
    }

    /**
     * Whether a request is written to the audit as a use of the emergency key at the door: every one of the key, and one of the
     * shared token for an action of the key's other than {@code self.read}.
     */
    private static boolean keyUse(Caller who, Action asked) {
        return who.via() == Via.EMERGENCY_KEY || who.via() == Via.SHARED_TOKEN && asked.emergencyKey() && asked != Action.SELF_READ;
    }

    /**
     * The route body on a fresh virtual thread — never on the event loop — where the answer is also encoded and its headers
     * checked; it is written back on {@code context}, the verticle's. The request is resumed first: a body no handler read is
     * dropped as it comes.
     */
    private static Handler<RoutingContext> offload(Context context, Route body) {
        return ctx -> {
            resume(ctx.request());
            Thread.ofVirtual().name("rail8-admin").start(() -> {
                Reply reply = run(body, ctx);
                context.runOnContext(v -> answer(ctx.response(), reply));
            });
        };
    }

    /** Answers a request refused before its route, then resumes it: the body it may still send is dropped as it comes, never read. */
    private static void refuse(RoutingContext ctx, Reply reply) {
        answer(ctx.response(), reply);
        resume(ctx.request());
    }

    /** Resumes a request the authentication paused; one already read to its end has nothing to resume. */
    static void resume(HttpServerRequest req) {
        if (!req.isEnded()) req.resume();
    }

    /** A route body; it may throw anything — {@link #run} answers it. */
    @FunctionalInterface
    private interface Route {
        /** The answer to {@code ctx} for {@code who}, the caller the door let through (null on a route without one). */
        Reply apply(RoutingContext ctx, Caller who) throws Exception;
    }

    private static Reply run(Route body, RoutingContext ctx) {
        try {
            return body.apply(ctx, ctx.get(CALLER));
        } catch (Throwable failure) {   // a store that failed, a header no response may carry, a checked one thrown sneakily by an await: answered, never a hang
            return failed(failure, ctx);
        }
    }

    /**
     * A failure as the API answers it: a {@link Refused} 400 — only what the routes map to Refused: a bad limit, cursor, key, step
     * number or body; an {@link AccessRefused} as {@link #refusal} answers it; the pool's refusal 503; anything else 500, logged.
     */
    private static Reply failed(Throwable failure, RoutingContext ctx) {
        if (failure instanceof Refused refused) return Json.of(400, error(refused.code, "bad request: " + refused.getMessage(), Map.of()));
        if (failure instanceof AccessRefused refused) return refusal(refused, ctx.request().path().startsWith(MODULES));
        if (failure instanceof SecretRefused refused) return Json.of(refused.status(), refused.body());
        if (failure instanceof ConfigRefusal refused) return Json.of(refused.status(), refused.body());
        if (PgTx.refusedByThePool(failure)) return busy();
        LOG.log(System.Logger.Level.WARNING, "admin request " + Access.redacted(ctx.request().path()) + " failed", failure);
        return Json.of(500, error(RefusalCode.INTERNAL_ERROR, "internal error", Map.of()));
    }

    /**
     * An {@link AccessRefused} as the API answers it: {@code {error, code, values}}; a 401 with
     * {@code WWW-Authenticate: Bearer}; a 403 with {@code needs}, the ids of the roles that grant the refused action; a 429 and a
     * busy 503 with {@code Retry-After}. With {@code problem} — a plugin's service — a 401 or 403 is instead a problem of type
     * {@code urn:rail8:admin:problem:unauthenticated} or {@code forbidden} with {@code detail} for {@code error} and the same other members.
     */
    private static Reply refusal(AccessRefused refused, boolean problem) {
        int status = refused.status();
        Map<String, String> headers = new TreeMap<>();
        if (status == 401) headers.put("www-authenticate", "Bearer");
        refused.retryAfter().ifPresent(seconds -> headers.put("retry-after", Long.toString(seconds)));
        boolean asProblem = problem && (status == 401 || status == 403);
        JsonObject body = asProblem
                ? new JsonObject().put("type", "urn:rail8:admin:problem:" + (status == 401 ? "unauthenticated" : "forbidden"))
                        .put("title", status == 401 ? "Unauthenticated" : "Forbidden").put("status", status).put("detail", refused.getMessage())
                : new JsonObject().put("error", refused.getMessage());
        body.put("code", refused.code().code()).put("values", new JsonObject(new TreeMap<>(refused.values())));
        if (status == 403) body.put("needs", ids(refused.needs()));
        return new Json(status, asProblem ? PROBLEM : JSON, body.toBuffer(), headers);
    }

    /**
     * Writes the answer; when Vert.x refuses it, the request still ends — a 500 before the head is out, a reset after. Completes
     * once the answer is written (or refused).
     */
    private static Future<?> answer(HttpServerResponse res, Reply reply) {
        try {
            return reply.send(res);
        } catch (RuntimeException refused) {
            LOG.log(System.Logger.Level.WARNING, "writing an admin answer failed", refused);
            return fail(res);
        }
    }

    /** Ends a response that cannot be answered as meant: a 500 {@code {error}} while the head is not out, otherwise a reset. */
    private static Future<?> fail(HttpServerResponse res) {
        if (res.headWritten()) return res.reset();
        return Json.of(500, error(RefusalCode.INTERNAL_ERROR, "internal error", Map.of())).send(res);
    }

    /**
     * Lets the request on unless a browser sent it: an {@code Origin} header, whatever its value, or a {@code Sec-Fetch-Site}
     * other than {@code none} (a typed URL) is 403 {@code {error, code: "browser"}}. The CLI and curl send neither.
     */
    private static void browserGuard(RoutingContext ctx) {
        HttpServerRequest req = ctx.request();
        String site = req.getHeader("Sec-Fetch-Site");
        if (req.getHeader(HttpHeaders.ORIGIN) == null && (site == null || site.equals("none"))) {
            ctx.next();
        } else {
            answer(ctx.response(), refusal(AccessRefused.forbidden(RefusalCode.BROWSER,
                    "the admin API does not answer a browser: a request with Origin or Sec-Fetch-Site other than none is refused", Map.of(), Set.of()), false));
        }
    }

    /** 403 {@code tls-required}: a login, a session or a named token on a listener that {@link Config#refusesLogins refuses them}. */
    private static AccessRefused plainHttp() {
        return AccessRefused.forbidden(RefusalCode.TLS_REQUIRED, PLAIN_HTTP, Map.of(), Set.of());
    }

    /** The address the request came from, for the audit. */
    private static String remote(RoutingContext ctx) {
        return ctx.request().remoteAddress().hostAddress();
    }

    /**
     * A request the API refuses: a 400 naming the reason, {@code bad-request} unless it has a code of its own. Only a bad parameter
     * becomes one, never a store's other failure.
     */
    private static final class Refused extends RuntimeException {
        private final transient RefusalCode code;

        Refused(String reason) {
            this(RefusalCode.BAD_REQUEST, reason);
        }

        Refused(RefusalCode code, String reason) {
            super(reason, null, false, false);
            this.code = code;
        }
    }

    /** {@code call}'s IllegalArgumentException — a parameter that does not parse, or a page store refusing a limit or cursor — as a 400. */
    private static <T> T refusable(Supplier<T> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException refused) {
            throw new Refused(refused.getMessage());
        }
    }

    // --- routes (on the request's virtual thread)

    private Reply health(RoutingContext ctx) {
        Map<String, Future<Health>> pending = new TreeMap<>();
        health.checks().forEach((name, check) -> pending.put(name, check.get().otherwise(Health.down(""))));
        JsonObject checks = new JsonObject();
        pending.forEach((name, answer) -> checks.put(name, answer.await().status().name()));
        boolean up = !checks.getMap().containsValue(Health.Status.DOWN.name());
        JsonObject answer = new JsonObject().put("status", (up ? Health.Status.UP : Health.Status.DOWN).name()).put("checks", checks)
                .put("snapshotWriteFailures", health.snapshotWriteFailures().getAsLong());
        List<String> notices = new ArrayList<>(health.notices());
        config.tls().flatMap(c -> c.expiryNotice(modules.clock().instant(), CERTIFICATE_NOTICE)).ifPresent(notices::add);
        if (!notices.isEmpty()) answer.put("notices", new JsonArray(notices));
        Optional<CredentialsCheck.Outcome> git = health.git().get();
        answer.put("git", new JsonObject().put("credentials", git.map(o -> o.state().name()).orElse(null))
                .put("checkedAt", git.map(o -> o.checkedAt().toString()).orElse(null)));
        return Json.of(up ? 200 : 503, answer);
    }

    /** {@code GET /admin/git}: the last check of the git credentials, every field null before the first. */
    private Reply git() {
        Optional<CredentialsCheck.Outcome> git = health.git().get();
        return Json.of(200, new JsonObject().put("credentials", git.map(o -> o.state().name()).orElse(null))
                .put("repository", git.map(CredentialsCheck.Outcome::repository).orElse(null))
                .put("reason", git.map(CredentialsCheck.Outcome::reason).orElse(null))
                .put("checkedAt", git.map(o -> o.checkedAt().toString()).orElse(null)));
    }

    /** {@code /ready}: each input read now, none awaited; 200 when all are UP, else 503, each named. */
    private Reply ready(RoutingContext ctx) {
        Readiness r = health.readiness();
        JsonObject checks = new JsonObject().put("lifecycle", status(r.lifecycle())).put("fence", status(r.fence()))
                .put("journal", status(r.journal())).put("deployments", status(r.deployments()));
        boolean up = !checks.getMap().containsValue(Health.Status.DOWN.name());
        return Json.of(up ? 200 : 503, new JsonObject().put("status", (up ? Health.Status.UP : Health.Status.DOWN).name()).put("checks", checks));
    }

    private static String status(BooleanSupplier input) {
        return (input.getAsBoolean() ? Health.Status.UP : Health.Status.DOWN).name();
    }

    private Reply timeline(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        List<Snapshot> timeline = messages.timeline(id).await();
        if (timeline.isEmpty()) return Json.of(404, error(RefusalCode.NO_MESSAGE, "no message " + id + " recorded", Map.of("id", id)));
        JsonArray steps = new JsonArray();
        for (Snapshot s : timeline) steps.add(step(s));
        return Json.of(200, new JsonObject().put("id", id).put("flow", timeline.getFirst().flow()).put("steps", steps));
    }

    /**
     * {@code GET /admin/messages/{id}/audit}: who retried the message, cancelled it or viewed its body, newest first, a page at
     * a time with {@code limit} and {@code after}; each item {@code {id, at, action, actor, via, credential, detail}}, with
     * {@code credential} null when the caller had none of its own.
     */
    private Reply audit(RoutingContext ctx) {
        Page<Access.Audited> page = refusable(() -> access.messageAudit(ctx.pathParam("id"), after(ctx), limit(ctx)));
        return Json.of(200, page(page, a -> new JsonObject().put("id", a.id()).put("at", a.at().toString()).put("action", a.action())
                .put("actor", a.actor()).put("via", a.via()).put("credential", a.credential().orElse(null)).put("detail", a.detail())));
    }

    /**
     * {@code GET /admin/audit}: the whole audit, newest first, a page at a time with {@code limit} and {@code after}, narrowed
     * by the optional {@code user} (a user's name), {@code action}, {@code from} and {@code to} (ISO-8601 instants, {@code from}
     * included, {@code to} not); each item {@code {id, at, action, subject, actor, via, credential, detail}}, with
     * {@code credential} null when the caller had none of its own.
     */
    private Reply wholeAudit(RoutingContext ctx) {
        Access.AuditFilter filter = refusable(() -> new Access.AuditFilter(given(ctx, "user"), given(ctx, "action"),
                given(ctx, "from").map(from -> instant(ctx, "from")), given(ctx, "to").map(to -> instant(ctx, "to"))));
        Page<Access.Audited> page = refusable(() -> access.audit(filter, after(ctx), limit(ctx)));
        return Json.of(200, page(page, a -> new JsonObject().put("id", a.id()).put("at", a.at().toString()).put("action", a.action())
                .put("subject", a.subject()).put("actor", a.actor()).put("via", a.via()).put("credential", a.credential().orElse(null))
                .put("detail", a.detail())));
    }

    private Reply body(RoutingContext ctx, Caller who) {
        String id = ctx.pathParam("id");
        int n = refusable(() -> Integer.parseInt(ctx.pathParam("n")));
        OptionalInt attempt = attempt(ctx);
        // the timeline is in attempt order: the last row of step n is its latest attempt's
        Snapshot at = messages.timeline(id).await().stream().filter(s -> s.step() == n && (attempt.isEmpty() || s.attempt() == attempt.getAsInt()))
                .reduce((earlier, later) -> later).orElse(null);
        if (at == null) {
            return Json.of(404, attempt.isEmpty() ? error(RefusalCode.NO_STEP, "no step " + n + " recorded for message " + id, Map.of("id", id, "step", n))
                    : error(RefusalCode.NO_STEP_ATTEMPT, "no step " + n + " of attempt " + attempt.getAsInt() + " recorded for message " + id,
                            Map.of("id", id, "step", n, "attempt", attempt.getAsInt())));
        }
        if (at.bodyKey().isEmpty()) {
            return Json.of(404, error(RefusalCode.BODY_NOT_KEPT, "step " + n + " of message " + id + " was recorded as metadata only: hash and size, no bytes",
                    Map.of("id", id, "step", n)));
        }
        // the head first: a recorded content-type no response may carry is refused here (IllegalArgumentException, a 500), before any stream is opened
        MultiMap head = HttpHeaders.headers().set("content-type", at.headers().getOrDefault("content-type", OCTETS)).set("content-length", Long.toString(at.bodySize()));
        JsonObject gone = error(RefusalCode.BODY_REMOVED, "the bytes of step " + n + " of message " + id + " are no longer stored (removed by retention)",
                Map.of("id", id, "step", n));
        BlobKey key = at.bodyKey().get();
        // existence as Postgres names it, never a node's location index: no object is retention's removal;
        // bytes that go after this check are gone too — an open that throws (a store that finds the key synchronously) or a
        // stream that fails before its first chunk (a store that opens lazily) answers the same 404
        boolean stored;
        try {
            stored = blobs.exists(key).await();
        } catch (BodyLostException lost) {   // given up by an accepted loss: stored once, never readable again
            return Json.of(410, error(RefusalCode.BODY_LOST, "the bytes of step " + n + " of message " + id + " are gone for good: " + lost.getMessage(),
                    Map.of("id", id, "step", n)));
        }
        if (!stored) return Json.of(404, gone);
        // the view is in the audit before any byte of it is sent: a row not written is 503, and nothing is opened
        access.bodyViewed(who.acting(Action.MESSAGE_BODY, PackageRef.ofFlow(at.flow()), remote(ctx)), id, n);
        ReadStream<Buffer> bytes;
        try {
            bytes = blobs.open(key);
        } catch (BlobNotFoundException missing) {   // removed between the check and the open
            return Json.of(404, gone);
        }
        return new Bytes(head, bytes, gone);
    }

    /**
     * {@code key=name:value}, split at the first colon: a value may contain colons, a name may not; or {@code flow} and
     * {@code step} together, never with a key.
     */
    private Reply search(RoutingContext ctx) {
        if (ctx.request().getParam("from") != null || ctx.request().getParam("to") != null) return recent(ctx);
        String flow = ctx.request().getParam("flow");
        String step = ctx.request().getParam("step");
        if (flow != null || step != null) {
            if (ctx.request().getParam("key") != null) throw new Refused("search by key or by flow and step, not both");
            if (flow == null || flow.isEmpty() || step == null || step.isEmpty()) throw new Refused("a search by step names both flow and step");
            return Json.of(200, page(refusable(() -> messages.findByStep(flow, step, after(ctx), limit(ctx)).await()), AdminServer::found));
        }
        String key = Objects.requireNonNullElse(ctx.request().getParam("key"), "");
        int colon = key.indexOf(':');
        if (colon < 0) throw new Refused("key must be name:value");
        Page<MessageStore.Found> page = refusable(() -> messages.findByKey(key.substring(0, colon), key.substring(colon + 1), after(ctx), limit(ctx)).await());
        return Json.of(200, page(page, AdminServer::found));
    }

    /** A message a search found, as {@code {id, flow}}. */
    private static JsonObject found(MessageStore.Found f) {
        return new JsonObject().put("id", f.id()).put("flow", f.flow());
    }

    /** The list of recent messages: {@code from} and {@code to}, and the optional {@code flow}, {@code status}, {@code key} and {@code step}. */
    private Reply recent(RoutingContext ctx) {
        PgMessageList recent = list.orElseThrow(() -> new Refused(RefusalCode.NO_MESSAGE_LIST, "this server does not list messages by time"));
        Optional<PgMessageList.Status> status = given(ctx, "status").map(s -> switch (s) {
            case "completed" -> PgMessageList.Status.COMPLETED;
            case "failed" -> PgMessageList.Status.FAILED;
            case "running" -> PgMessageList.Status.RUNNING;
            case "cancelled" -> PgMessageList.Status.CANCELLED;
            default -> throw new Refused("status must be completed, failed, running or cancelled, was '" + s + "'");
        });
        Optional<PgMessageList.Key> key = given(ctx, "key").map(k -> {
            int colon = k.indexOf(':');
            if (colon < 0) throw new Refused("key must be name:value");
            return new PgMessageList.Key(k.substring(0, colon), k.substring(colon + 1));
        });
        PgMessageList.Query query = refusable(() -> new PgMessageList.Query(instant(ctx, "from"), instant(ctx, "to"), given(ctx, "flow"), status, key,
                given(ctx, "step")));
        return Json.of(200, page(refusable(() -> recent.list(query, after(ctx), limit(ctx)).await()), AdminServer::listed));
    }

    /** How many messages of the list of recent messages are in each status: {@code from} and {@code to}, and the optional {@code flow}. */
    private Reply counts(RoutingContext ctx) {
        PgMessageList recent = list.orElseThrow(() -> new Refused(RefusalCode.NO_MESSAGE_LIST, "this server does not count messages by time"));
        Map<PgMessageList.Status, Long> counts = refusable(() -> recent.counts(instant(ctx, "from"), instant(ctx, "to"), given(ctx, "flow")).await());
        JsonObject answer = new JsonObject();
        counts.forEach((status, n) -> answer.put(status.name().toLowerCase(Locale.ROOT), n));
        return Json.of(200, answer);
    }

    /** The parameter {@code name}, empty when it is missing or empty. */
    private static Optional<String> given(RoutingContext ctx, String name) {
        return Optional.ofNullable(ctx.request().getParam(name)).filter(value -> !value.isEmpty());
    }

    /** The ISO-8601 instant in parameter {@code name}; a missing one or one that does not parse is a 400. */
    private static Instant instant(RoutingContext ctx, String name) {
        String raw = ctx.request().getParam(name);
        if (raw == null) throw new Refused(name + " is required: an ISO-8601 instant");
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException notAnInstant) {
            throw new Refused(name + " must be an ISO-8601 instant, was '" + raw + "'");
        }
    }

    private Reply dlq(RoutingContext ctx) {
        return Json.of(200, page(refusable(() -> dlq.list(after(ctx), limit(ctx)).await()), AdminServer::parked));
    }

    private static Reply held(RoutingContext ctx, WorkQueue work) {
        return Json.of(200, page(refusable(() -> work.held(after(ctx), limit(ctx)).await()), AdminServer::held));
    }

    private static Reply stopped(RoutingContext ctx, WorkQueue work) {
        return Json.of(200, page(refusable(() -> work.stopped(after(ctx), limit(ctx)).await()), AdminServer::stopped));
    }

    /**
     * 200 {@code {seq}} for a held row, released and audited: its flow woken here (every flow when untold) and announced to its body's
     * node ({@code *} when untold), the release committed; 404 for any other seq the queue does not release; a seq that is not a long never reaches it.
     */
    private static Reply release(RoutingContext ctx, Caller who, WorkQueue work, Workers workers, WorkSignals signals) {
        long seq = refusable(() -> Long.parseLong(ctx.pathParam("seq")));
        Optional<WorkQueue.Released> released = work.releaseRow(seq, who.acting(Action.WORK_RELEASE, PackageRef.ANY, remote(ctx))).await();
        if (released.isEmpty()) return Json.of(404, error(RefusalCode.NO_HELD_WORK, "no held work item " + seq, Map.of("seq", seq)));
        WorkQueue.Released row = released.get();
        row.flow().ifPresentOrElse(workers::wake, workers::wakeAll);
        announce(signals, row.bodyNode().orElse(null), row.flow().orElse(null), "released work item " + seq);
        return Json.of(200, new JsonObject().put("seq", seq));
    }

    /** Announces {@code flow} to {@code target} after a change committed: a throw is logged, the answer stands. */
    private static void announce(WorkSignals signals, String target, String flow, String what) {
        try {
            signals.announce(target, flow);
        } catch (RuntimeException | Error thrown) {   // the change committed: its answer stands, the body's node finds the row by reconciliation
            LOG.log(System.Logger.Level.WARNING, "announcing " + what + " failed: " + thrown, thrown);
        }
    }

    private static Reply lost(RoutingContext ctx, WorkQueue work) {
        return Json.of(200, page(refusable(() -> work.lost(after(ctx), limit(ctx)).await()), AdminServer::lost));
    }

    /**
     * 200 {@code {seq, auditId}} for a lost row, discarded and audited: its flow woken here and, when its key has a next row
     * whose node is known, that node announced, after the commit; 404 for any other seq (nothing deleted, nothing audited); a seq
     * that is not a long is 400 before the queue is asked.
     */
    private static Reply discard(RoutingContext ctx, Caller who, WorkQueue work, Workers workers, WorkSignals signals) {
        long seq = refusable(() -> Long.parseLong(ctx.pathParam("seq")));
        Optional<WorkQueue.Discarded> discarded = work.discard(seq, who.acting(Action.WORK_DISCARD, PackageRef.ANY, remote(ctx))).await();
        if (discarded.isEmpty()) return Json.of(404, error(RefusalCode.NO_LOST_WORK, "no lost work item " + seq, Map.of("seq", seq)));
        WorkQueue.Discarded d = discarded.get();
        workers.wake(d.flow());
        d.nextNode().ifPresent(node -> announce(signals, node, d.flow(), "the key's next row after discarded work item " + seq));
        return Json.of(200, new JsonObject().put("seq", seq).put("auditId", d.auditId()));
    }

    /**
     * {@code POST /admin/dlq/{id}/retry}: the parked message runs once more under its own id, from step 1, as attempt k + 1 of its
     * own bound. 404 without a DLQ row; 409 when its flow is not deployed
     * here; 422 when its flow is not at-least-once, when its worker did not park it — a row at a step other than 1, one without an
     * {@code attempt} k of at least 1 — or when its flow is keyed ({@code partitionBy}) and the row kept no key (parked before V17);
     * nothing written for any of them; each 422 says why and names no other route. Then {@link FailedMessages#retry}, audited: a
     * message that stops its key runs in place, ahead of the rows behind it, any other at the tail of its key. The row gone
     * meanwhile is 404, a work row of the message there and not stopped, or a stopped row whose body is lost, 409 naming that work
     * item, an ingress body no longer kept 409; nothing changed, nothing woken or announced. 202 {@code {id, attempt: k + 1}} once
     * it committed: the flow woken here and announced to the node of the row's body (every node when the act names none). A body
     * sent to it is not read.
     */
    private Reply retry(RoutingContext ctx, Caller who, Deployments deployments, FailedMessages failed) {
        String id = ctx.pathParam("id");
        Optional<Parked> found = dlq.get(id).await();
        if (found.isEmpty()) return Json.of(404, error(RefusalCode.NO_DLQ_ROW, "no DLQ row for message " + id, Map.of("id", id)));
        Parked row = found.get();
        String flow = row.flow();
        Actor by = who.acting(Action.MESSAGE_RETRY, PackageRef.ofFlow(flow), remote(ctx));
        Optional<FlowDefinition> def = deployments.definition(flow);
        if (def.isEmpty()) {
            return Json.of(409, error(RefusalCode.RESTART_FLOW_NOT_DEPLOYED, "flow " + flow + " is not deployed on this node; message " + id + " is not restarted",
                    Map.of("flow", flow, "id", id)));
        }
        if (!"at-least-once".equals(def.get().deliveryMode())) {
            return Json.of(422, error(RefusalCode.RESTART_NOT_AT_LEAST_ONCE, "message " + id + " of flow " + flow + " is not restarted: the flow is "
                    + def.get().deliveryMode() + ", only an at-least-once flow's worker runs a restart",
                    Map.of("id", id, "flow", flow, "mode", def.get().deliveryMode())));
        }
        Object attempt = row.props().asMap().get(Envelope.ATTEMPT.name());
        if (row.step() != 1 || !(attempt instanceof Integer k) || k < 1) {
            return Json.of(422, error(RefusalCode.RESTART_NOT_PARKED_BY_WORKER, "message " + id + " was not parked by its flow's worker (step " + row.step()
                    + ", attempt " + attempt + "): it cannot be restarted", Map.of("id", id, "step", row.step(), "attempt", String.valueOf(attempt))));
        }
        if (def.get().partitionBy().isPresent() && row.partitionKey().isEmpty()) {
            return Json.of(422, error(RefusalCode.RESTART_KEY_NOT_KEPT, "message " + id + " of the keyed flow " + flow + " was parked before its key was kept",
                    Map.of("id", id, "flow", flow)));
        }
        FailedMessages.Act act;
        try {
            act = failed.retry(id, flow, row.partitionKey().orElse(null), k, by).await();
        } catch (BlobNotFoundException | BodyLostException gone) {
            return Json.of(409, error(RefusalCode.RESTART_BODY_GONE, "message " + id + " is not restarted: its ingress body is no longer kept ("
                    + gone.getMessage() + ")", Map.of("id", id)));
        } catch (NotAdmittedException retired) {
            return Json.of(409, error(RefusalCode.RESTART_NOT_ADMITTED, "message " + id + " is not restarted: " + retired.getMessage(), Map.of("id", id)));
        }
        return switch (act) {
            case FailedMessages.Unsettled u -> Json.of(409, error(RefusalCode.RESTART_UNSETTLED, "message " + id + " is not restarted: its work item " + u.seq()
                    + " is not settled yet, or its body is lost", Map.of("id", id, "seq", u.seq())));
            case FailedMessages.NotParked n -> Json.of(404, error(RefusalCode.NO_DLQ_ROW, "no DLQ row for message " + id + " any more", Map.of("id", id)));
            case FailedMessages.Stopping s -> throw new IllegalStateException("a retry answered " + s);
            case FailedMessages.Done done -> {
                workers.wake(flow);
                announce(signals, done.node().orElse(null), flow, "restarted message " + id);
                yield Json.of(202, new JsonObject().put("id", id).put("attempt", k + 1));
            }
        };
    }

    /**
     * {@code POST /admin/dlq/{id}/cancel}: the parked message ends — its DLQ row deleted and, when it stops its key, its stopped
     * work row too, so the key's next row runs — and one audit row records it, in one transaction ({@link FailedMessages#cancel}).
     * It reads no deployed flow: every parked message can be cancelled. The body is the reason: none, or {@code {"reason": "..."}}
     * of 1 to {@link FailedMessages#REASON_MAX} characters after stripping and nothing else, else 400; above
     * {@link #CANCEL_BODY_MAX} bytes 413 — each before anything is read. 404
     * without a DLQ row; 409 while a work row of the message is there and not stopped, naming it; nothing written. 200
     * {@code {id, auditId, cancelledAt}} once it committed: the flow woken here and announced to the node of its key's next row
     * when there is one.
     */
    private Reply cancel(RoutingContext ctx, Caller who, FailedMessages failed) {
        String id = ctx.pathParam("id");
        Actor by = who.acting(Action.MESSAGE_CANCEL, PackageRef.ANY, remote(ctx));
        Optional<String> reason = reason(ctx.request(), ctx.body());
        FailedMessages.Act act = failed.cancel(id, by, reason).await();
        return switch (act) {
            case FailedMessages.Unsettled u -> Json.of(409, error(RefusalCode.CANCEL_UNSETTLED, "message " + id + " is not cancelled: its work item " + u.seq()
                    + " is not settled yet", Map.of("id", id, "seq", u.seq())));
            case FailedMessages.NotParked n -> Json.of(404, error(RefusalCode.NO_DLQ_ROW, "no DLQ row for message " + id, Map.of("id", id)));
            case FailedMessages.Stopping s -> throw new IllegalStateException("a DLQ cancel answered " + s);
            case FailedMessages.Done done -> Json.of(200, cancelled(id, done));
        };
    }

    /**
     * A parked message cancelled: its flow woken here and announced to the node of its key's next row when there is one. Answers
     * {@code {id, auditId, cancelledAt}}.
     */
    private JsonObject cancelled(String id, FailedMessages.Done done) {
        FailedMessages.Event e = done.event();
        workers.wake(e.flow());
        done.node().ifPresent(node -> announce(signals, node, e.flow(), "the key's next row after cancelled message " + id));
        return new JsonObject().put("id", id).put("auditId", e.id()).put("cancelledAt", e.at().toString());
    }

    /**
     * {@code POST /admin/messages/{id}/cancel}: the at-least-once message ends wherever it is ({@link FailedMessages#end}), with the
     * body and its 400 and 413 as {@code POST /admin/dlq/{id}/cancel}. A parked message is cancelled as there: 200
     * {@code {id, auditId, cancelledAt, state: "ended"}}. One not parked whose work row is queued, waiting or leased has the row
     * marked: its run stops, the one going on this node at once (with the cancel's actor and reason), one on another node at that
     * node's next lease renewal, one not running at its row's next claim, which a wake here and an announce to every node bring
     * on. 202 {@code {id, auditId, cancelledAt, state: "stopping", node}}, {@code node} the holder of the row's live lease, null
     * when none holds it; a repeat answers the first cancel. 409 naming the work item when it is held, stopped without a DLQ row,
     * lost, or not settled beside a DLQ row; 404 when the message has neither: sync and at-most-once runs are not cancelled.
     */
    private Reply end(RoutingContext ctx, Caller who, FailedMessages failed) {
        String id = ctx.pathParam("id");
        Actor by = who.acting(Action.MESSAGE_CANCEL, PackageRef.ANY, remote(ctx));
        Optional<String> reason = reason(ctx.request(), ctx.body());
        FailedMessages.Act act = failed.end(id, by, reason).await();
        return switch (act) {
            case FailedMessages.Unsettled u -> Json.of(409, error(RefusalCode.END_UNSETTLED, "message " + id + " is not cancelled: its work item " + u.seq()
                    + " is held, lost, or not settled yet", Map.of("id", id, "seq", u.seq())));
            case FailedMessages.NotParked n -> Json.of(404, error(RefusalCode.NOT_CANCELLABLE, "no queued, running or parked at-least-once message " + id
                    + ": sync and at-most-once runs are not cancelled; their time limit ends them", Map.of("id", id)));
            case FailedMessages.Done done -> Json.of(200, cancelled(id, done).put("state", "ended"));
            case FailedMessages.Stopping s -> {
                FailedMessages.Event e = s.event();
                deployments.ifPresent(d -> d.stop(id, new Runs.Cancelled(e.actor(), e.reason())));
                if (s.node().isEmpty()) {
                    workers.wake(e.flow());
                    announce(signals, null, e.flow(), "cancelled message " + id);
                }
                yield Json.of(202, new JsonObject().put("id", id).put("auditId", e.id()).put("cancelledAt", e.at().toString())
                        .put("state", "stopping").put("node", s.node().orElse(null)));
            }
        };
    }

    /**
     * A cancel's reason: no body is none; any other body is {@code {"reason": "<text>"}} with no other member, the text 1 to
     * {@link FailedMessages#REASON_MAX} characters after stripping, else a 400 — an empty object and a null reason among them.
     * Multipart form data is such a 400 too, never a cancel without a reason: the body handler parses it as a form and buffers
     * none of it, so it would read as no body.
     */
    private static Optional<String> reason(HttpServerRequest req, RequestBody body) {
        String shape = "the body is none or {\"reason\": \"<1 to " + FailedMessages.REASON_MAX + " characters>\"}";
        String type = req.getHeader(HttpHeaders.CONTENT_TYPE);
        if (type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data")) throw new Refused(shape + ": it is a form");
        if (body.isEmpty()) return Optional.empty();
        Object json;
        try {
            json = io.vertx.core.json.Json.decodeValue(body.buffer());
        } catch (DecodeException notJson) {
            throw new Refused(shape + ": it is not JSON");
        }
        if (!(json instanceof JsonObject o) || o.size() != 1 || !(o.getValue("reason") instanceof String text)) throw new Refused(shape);
        return refusable(() -> FailedMessages.reason(Optional.of(text)));
    }

    /** The {@code attempt} parameter: empty when missing, else an int of at least 1, else (not an int, below 1) a 400. */
    private static OptionalInt attempt(RoutingContext ctx) {
        String raw = ctx.request().getParam("attempt");
        if (raw == null) return OptionalInt.empty();
        int attempt;
        try {
            attempt = Integer.parseInt(raw);
        } catch (NumberFormatException notAnInt) {
            attempt = 0;
        }
        if (attempt < 1) throw new Refused("attempt must be an attempt number of at least 1, was '" + raw + "'");
        return OptionalInt.of(attempt);
    }

    private static Optional<String> after(RoutingContext ctx) {
        return Optional.ofNullable(ctx.request().getParam("after"));
    }

    /** The {@code limit} parameter, {@link #DEFAULT_LIMIT} without one; the store refuses one outside 1..500, a non-number is a NumberFormatException. */
    private static int limit(RoutingContext ctx) {
        String limit = ctx.request().getParam("limit");
        return limit == null ? DEFAULT_LIMIT : Integer.parseInt(limit);
    }

    // --- the configuration of the environment (on the request's virtual thread)

    private static final String MOCKS_SHAPE = "the body is {\"expect\": true | false, \"allowed\": true | false, \"confirm\"?: \"<digest>\"}";
    private static final String CONNECTION_SAVE_SHAPE = "the body is {\"expect\", \"adapter\", \"values\": {…}, \"mock\"?: {\"reply\","
            + " \"delay\"?, \"fail\"?}}, {\"expect\", \"removed\": true} or {\"expect\", \"fromVersion\": <version>}";
    private static final String SYSTEM_SAVE_SHAPE = "the body is {\"expect\", \"instanceName\"}, {\"expect\", \"removed\": true} or"
            + " {\"expect\", \"fromVersion\": <version>}";
    private static final String EXPECT_SHAPE = "the body is {\"expect\": \"<etag>\"}";
    private static final String CONSENT_SHAPE = "the body is {\"expect\": \"<etag>\", \"digest\": \"<digest>\" | null}";
    private static final String ACTIVATE_SHAPE = "the body is {\"expect\": \"<etag>\", \"confirm\"?: \"<digest>\", \"note\"?: \"<text>\"}";
    private static final String RUN_SHAPE = "the body is {\"expect\": \"<run>\"}";
    private static final String IMPORT_SHAPE = "the body is {\"scope\": {\"kind\"?: \"connection\" | \"system\", \"prefix\"?: \"<text>\"},"
            + " \"files\": [{\"path\", \"text\"}, …], \"expect\"?: \"<digest>\", \"note\"?: \"<text>\"}, with expect unless check=true";
    /** The most bytes the body of an import takes: the files of one call, as JSON. */
    static final int IMPORT_BODY_MAX = 4 * 1024 * 1024;

    /**
     * The routes of {@code /admin/config}: the environment and its switch of mocks, and for connections and systems the list,
     * one object, its history and each verb, a route of its own behind its action. A write answers the object as this node reads
     * it once its own environment has read the store again.
     */
    private static void mountConfiguration(Admin admin, Configuration config) {
        admin.get("/admin/config", Action.CONFIG_READ, (c, who) -> Json.of(200, environment(config.store().overview())));
        admin.post("/admin/config/-/mocks", Action.CONFIG_CONNECTIONS, CONFIG_BODY_MAX, (c, who) -> {
            JsonObject o = object(c, MOCKS_SHAPE, Set.of("expect", "allowed"), Set.of("confirm"));
            config.store().writeMocks(configActor(c, who, Action.CONFIG_CONNECTIONS), new Change.Mocks(flag(o, "expect", MOCKS_SHAPE),
                    flag(o, "allowed", MOCKS_SHAPE), optionalText(o, "confirm", MOCKS_SHAPE)));
            config.reread().get().otherwiseEmpty().await();   // a read that failed leaves the answer's nodes saying so
            return Json.of(200, environment(config.store().overview()));
        });
        admin.get("/admin/config/export", Action.CONFIG_READ, (c, who) -> {
            ConfigStore.Scope scope = scope(c.request().getParam("kind"), c.request().getParam("prefix"));
            ConfigStore.Export export = config.store().export(scope);
            JsonArray files = new JsonArray();
            export.files().forEach(f -> files.add(new JsonObject().put("path", f.path()).put("text", f.text())));
            JsonArray refused = new JsonArray();
            export.refused().forEach(ref -> refused.add(new JsonObject().put("kind", ref.kind().id()).put("key", ref.key())));
            return Json.of(200, new JsonObject().put("files", files).put("refused", refused));
        });
        admin.post("/admin/config/import", Action.CONFIG_IMPORT, IMPORT_BODY_MAX, (c, who) -> configImport(c, who, config));
        for (Ref.Kind kind : Ref.Kind.values()) {
            String base = "/admin/config/" + kind.id() + "s";
            boolean connection = kind == Ref.Kind.CONNECTION;
            admin.get(base, Action.CONFIG_READ, (c, who) -> configList(c, config, kind));
            admin.keyed(HttpMethod.GET, base + "/{key}", Action.CONFIG_READ, 0, (c, who) -> {
                Ref ref = configRef(c, kind);
                return Json.of(200, shown(config, config.store().read(ref)));
            });
            admin.keyed(HttpMethod.GET, base + "/{key}/-/history", Action.CONFIG_READ, 0, (c, who) -> configHistory(c, config, kind));
            admin.keyed(HttpMethod.POST, base + "/{key}/-/save", Action.CONFIG_CONNECTIONS, CONFIG_BODY_MAX,
                    (c, who) -> configWrite(c, who, config, kind, Action.CONFIG_CONNECTIONS, () -> save(c, kind)));
            admin.keyed(HttpMethod.POST, base + "/{key}/-/discard", Action.CONFIG_CONNECTIONS, CONFIG_BODY_MAX,
                    (c, who) -> configWrite(c, who, config, kind, Action.CONFIG_CONNECTIONS, () -> new Change.Discard(expect(c, EXPECT_SHAPE, Set.of()))));
            admin.keyed(HttpMethod.POST, base + "/{key}/-/activate", Action.CONFIG_CONNECTIONS, CONFIG_BODY_MAX, (c, who) ->
                    configWrite(c, who, config, kind, Action.CONFIG_CONNECTIONS, () -> {
                        JsonObject body = object(c, ACTIVATE_SHAPE, Set.of("expect"), Set.of("confirm", "note"));
                        return new Change.Activate(text(body, "expect", ACTIVATE_SHAPE), optionalText(body, "confirm", ACTIVATE_SHAPE),
                                optionalText(body, "note", ACTIVATE_SHAPE));
                    }));
            if (!connection) continue;
            admin.keyed(HttpMethod.POST, base + "/{key}/-/consent", Action.CONFIG_CONSENT, CONFIG_BODY_MAX, (c, who) ->
                    configWrite(c, who, config, kind, Action.CONFIG_CONSENT, () -> {
                        JsonObject body = object(c, CONSENT_SHAPE, Set.of("expect", "digest"), Set.of());
                        Object digest = body.getValue("digest");
                        if (digest != null && !(digest instanceof String)) throw new Refused(CONSENT_SHAPE);
                        return new Change.Consent(text(body, "expect", CONSENT_SHAPE), Optional.ofNullable((String) digest));
                    }));
            admin.keyed(HttpMethod.POST, base + "/{key}/-/stop", Action.CONFIG_RUN, CONFIG_BODY_MAX,
                    (c, who) -> configWrite(c, who, config, kind, Action.CONFIG_RUN, () -> new Change.Stop(expect(c, RUN_SHAPE, Set.of()))));
            admin.keyed(HttpMethod.POST, base + "/{key}/-/start", Action.CONFIG_RUN, CONFIG_BODY_MAX,
                    (c, who) -> configWrite(c, who, config, kind, Action.CONFIG_RUN, () -> new Change.Start(expect(c, RUN_SHAPE, Set.of()))));
        }
    }

    /**
     * {@code POST /admin/config/import?check}: with {@code check=true} the dry run of the body's files over its scope, else their
     * write, all or none, given the {@code expect} of a dry run. The caller writes the kinds whose write it holds:
     * {@code config.connections} for both.
     */
    private static Reply configImport(RoutingContext ctx, Caller who, Configuration config) {
        String check = ctx.request().getParam("check");
        if (check != null && !check.equals("true") && !check.equals("false")) throw new Refused("check is true or false, was '" + check + "'");
        boolean dryRun = "true".equals(check);
        JsonObject o = object(ctx, IMPORT_SHAPE, Set.of("scope", "files"), Set.of("expect", "note"));
        if (!(o.getValue("scope") instanceof JsonObject s) || !Set.of("kind", "prefix").containsAll(s.fieldNames())) throw new Refused(IMPORT_SHAPE);
        if (s.containsKey("kind") && !(s.getValue("kind") instanceof String) || s.containsKey("prefix") && !(s.getValue("prefix") instanceof String)) {
            throw new Refused(IMPORT_SHAPE);
        }
        ConfigStore.Scope scope = scope(s.getString("kind"), s.getString("prefix"));
        if (!(o.getValue("files") instanceof JsonArray given)) throw new Refused(IMPORT_SHAPE);
        List<ConfigStore.File> files = new ArrayList<>();
        for (Object f : given) {
            if (!(f instanceof JsonObject file) || !file.fieldNames().equals(Set.of("path", "text"))) throw new Refused(IMPORT_SHAPE);
            files.add(new ConfigStore.File(text(file, "path", IMPORT_SHAPE), text(file, "text", IMPORT_SHAPE)));
        }
        Set<Ref.Kind> writes = who.may(Action.CONFIG_CONNECTIONS, PackageRef.ANY) ? EnumSet.allOf(Ref.Kind.class) : EnumSet.noneOf(Ref.Kind.class);
        if (dryRun) return Json.of(200, config.store().importCheck(writes, scope, files).json());
        if (!o.containsKey("expect")) throw new Refused(IMPORT_SHAPE);
        ConfigStore.Plan written = config.store().importWrite(configActor(ctx, who, Action.CONFIG_IMPORT), writes, scope, files,
                text(o, "expect", IMPORT_SHAPE), optionalText(o, "note", IMPORT_SHAPE));
        config.reread().get().otherwiseEmpty().await();   // a read that failed leaves the answer's nodes saying so
        return Json.of(200, written.json());
    }

    /** The scope of an export or an import: {@code kind}, {@code connection} or {@code system}, and {@code prefix}, each when given. */
    private static ConfigStore.Scope scope(String kind, String prefix) {
        Optional<Ref.Kind> k = kind == null ? Optional.empty() : Optional.of(switch (kind) {
            case "connection" -> Ref.Kind.CONNECTION;
            case "system" -> Ref.Kind.SYSTEM;
            default -> throw new Refused("kind is connection or system, was '" + kind + "'");
        });
        return new ConfigStore.Scope(k, Optional.ofNullable(prefix));
    }

    /**
     * The key of the route's path as an object of {@code kind}, read through the one parser of keys: before any read of the store,
     * a key the parser refuses is 400 {@code bad-request}, naming the rule it breaks.
     */
    private static Ref configRef(RoutingContext ctx, Ref.Kind kind) {
        return refusable(() -> new Ref(kind, ctx.pathParam("key")));
    }

    /** The writer of a change by {@code action}: the audit's actor, and whether the caller also holds {@code config.consent}. */
    private static dev.rail8.runtime.config.Actor configActor(RoutingContext ctx, Caller who, Action action) {
        return new dev.rail8.runtime.config.Actor(who.acting(action, PackageRef.ANY, remote(ctx)), who.may(Action.CONFIG_CONSENT, PackageRef.ANY));
    }

    /**
     * A write of one object: the key first, then the body as {@code change} reads it, then the store's write. It answers the object
     * once this node's environment has read the store again, so a change in force holds on this node when it is answered.
     */
    private static Reply configWrite(RoutingContext ctx, Caller who, Configuration config, Ref.Kind kind, Action action,
                                     Supplier<Change> change) {
        Ref ref = configRef(ctx, kind);
        Change asked = change.get();
        config.store().write(configActor(ctx, who, action), ref, asked);
        config.reread().get().otherwiseEmpty().await();   // a read that failed leaves the answer's nodes saying so
        return Json.of(200, shown(config, config.store().read(ref)));
    }

    /** A save: of a connection its values, mock or removal, or a copy of a version; of a system its instance name, removal or copy. */
    private static Change save(RoutingContext ctx, Ref.Kind kind) {
        boolean connection = kind == Ref.Kind.CONNECTION;
        String shape = connection ? CONNECTION_SAVE_SHAPE : SYSTEM_SAVE_SHAPE;
        JsonObject o = object(ctx, shape, Set.of("expect"), connection ? Set.of("adapter", "values", "mock", "removed", "fromVersion")
                : Set.of("instanceName", "removed", "fromVersion"));
        String expect = text(o, "expect", shape);
        Set<String> given = new java.util.TreeSet<>(o.fieldNames());
        given.remove("expect");
        if (given.equals(Set.of("removed"))) {
            if (!Boolean.TRUE.equals(o.getValue("removed"))) throw new Refused(shape);
            return new Change.Remove(expect);
        }
        if (given.equals(Set.of("fromVersion"))) {
            if (!(o.getValue("fromVersion") instanceof Integer version)) throw new Refused(shape);
            return new Change.GoBack(expect, version);
        }
        if (!connection) {
            if (!given.equals(Set.of("instanceName"))) throw new Refused(shape);
            return new Change.SaveSystem(expect, text(o, "instanceName", shape));
        }
        if (!given.equals(Set.of("adapter", "values")) && !given.equals(Set.of("adapter", "values", "mock"))) throw new Refused(shape);
        if (!(o.getValue("values") instanceof JsonObject values)) throw new Refused(shape);
        Optional<Change.Mock> mock = Optional.empty();
        if (o.containsKey("mock")) {
            if (!(o.getValue("mock") instanceof JsonObject m) || !m.fieldNames().contains("reply")
                    || !Set.of("reply", "delay", "fail").containsAll(m.fieldNames())) {
                throw new Refused(shape);
            }
            mock = Optional.of(new Change.Mock(text(m, "reply", shape), optionalText(m, "delay", shape), optionalText(m, "fail", shape)));
        }
        return new Change.Save(expect, text(o, "adapter", shape), values.getMap(), mock);
    }

    /** The {@code expect} of a body that holds it and the members of {@code optional} only; else a 400 saying {@code shape}. */
    private static String expect(RoutingContext ctx, String shape, Set<String> optional) {
        return text(object(ctx, shape, Set.of("expect"), optional), "expect", shape);
    }

    /** The boolean member {@code name} of {@code o}, else a 400 saying {@code shape}. */
    private static boolean flag(JsonObject o, String name, String shape) {
        if (o.getValue(name) instanceof Boolean flag) return flag;
        throw new Refused(shape);
    }

    /** The string member {@code name} of {@code o} when it is there, else a 400 saying {@code shape} for another type. */
    private static Optional<String> optionalText(JsonObject o, String name, String shape) {
        return o.containsKey(name) ? Optional.of(text(o, name, shape)) : Optional.empty();
    }

    /**
     * {@code GET /admin/config/<kind>s?prefix&needs&after&limit}: a page of objects by key, {@code {items, next}}, each its key,
     * its state, its version, whether a draft waits, and the live nodes that do not hold it; a connection also its adapter and
     * whether its draft waits for consent. {@code needs=consent} or {@code needs=activation} narrows the page to those drafts.
     */
    private static Reply configList(RoutingContext ctx, Configuration config, Ref.Kind kind) {
        String needs = ctx.request().getParam("needs");
        Optional<ConfigStore.Needs> narrowed = needs == null ? Optional.empty() : switch (needs) {
            case "consent" -> Optional.of(ConfigStore.Needs.CONSENT);
            case "activation" -> Optional.of(ConfigStore.Needs.ACTIVATION);
            default -> throw new Refused("needs is consent or activation, was '" + needs + "'");
        };
        int limit = refusable(() -> limit(ctx));
        ConfigStore.Page<ConfigStore.ObjectView> page = config.store().list(new ConfigStore.Query(kind,
                Optional.ofNullable(ctx.request().getParam("prefix")), after(ctx), limit, narrowed));
        JsonArray items = new JsonArray();
        for (ConfigStore.ObjectView view : page.items()) items.add(listed(view.json()));
        return Json.of(200, new JsonObject().put("items", items).put("next", page.next().orElse(null)));
    }

    /**
     * One object of a list: its key; its state, {@code missing} with nothing in force, else {@code stopped}, {@code mocked} or
     * {@code set}; its version; whether a draft waits; the live nodes that do not hold it.
     */
    private static JsonObject listed(JsonObject o) {
        JsonObject inForce = o.getJsonObject("inForce");
        JsonObject draft = o.getJsonObject("draft");
        boolean connection = o.getString("kind").equals("connection");
        String state = inForce == null || inForce.getBoolean("removed") ? "missing"
                : connection && o.getValue("stopped") != null ? "stopped"
                : connection && inForce.getValue("mock") != null ? "mocked" : "set";
        JsonObject item = new JsonObject().put("key", o.getString("key"));
        if (connection) item.put("adapter", o.getString("adapter"));
        else item.put("instanceName", inForce == null ? null : inForce.getString("instanceName"));
        item.put("state", state).put("version", o.getInteger("version")).put("draft", draft != null);
        if (connection) {
            JsonObject consent = draft == null ? null : draft.getJsonObject("consent");
            item.put("needsConsent", consent != null && consent.getValue("given") == null);
        }
        JsonArray behind = new JsonArray();
        for (Object n : o.getJsonArray("nodes")) {
            JsonObject node = (JsonObject) n;
            if (!node.getString("holds").equals("yes")) behind.add(node.getString("node"));
        }
        return item.put("nodesBehind", behind);
    }

    /**
     * {@code GET /admin/config/<kind>s/{key}/-/history?after&limit}: the object's history, newest first, {@code {items, next}},
     * {@value #DEFAULT_LIMIT} a page and at most 500, with the changes of its secrets merged in.
     */
    private static Reply configHistory(RoutingContext ctx, Configuration config, Ref.Kind kind) {
        Ref ref = configRef(ctx, kind);
        String raw = ctx.request().getParam("after");
        OptionalLong after = raw == null ? OptionalLong.empty() : OptionalLong.of(refusable(() -> Long.parseLong(raw)));
        int limit = refusable(() -> limit(ctx));
        ConfigStore.Page<ConfigStore.HistoryRow> page = config.store().history(ref, after, limit);
        JsonArray items = new JsonArray();
        page.items().forEach(row -> items.add(row.json()));
        return Json.of(200, new JsonObject().put("items", items).put("next", page.next().orElse(null)));
    }

    /**
     * The object as the screen shows it, each secret its connection names with what the secret store's list says of it: whether
     * it is set, and if so its kind, when and by whom it last changed, and whether this node can read it. Never a value.
     */
    private static JsonObject shown(Configuration config, ConfigStore.ObjectView view) {
        JsonObject json = view.json();
        JsonArray secrets = json.getJsonArray("secrets");
        if (secrets == null || secrets.isEmpty()) return json;
        Map<String, JsonObject> listed = new HashMap<>();
        config.secrets().ifPresent(store -> store.list().await().getJsonArray("secrets")
                .forEach(o -> listed.put(((JsonObject) o).getString("name"), (JsonObject) o)));
        for (Object o : secrets) {
            JsonObject secret = (JsonObject) o;
            JsonObject entry = listed.get(secret.getString("name"));
            secret.put("set", entry != null);
            if (entry != null) {
                secret.put("kind", entry.getString("kind")).put("changedAt", entry.getString("changedAt"))
                        .put("changedBy", entry.getString("changedBy")).put("unreadable", entry.getBoolean("unreadable"));
            }
        }
        return json;
    }

    /**
     * {@code GET /admin/config}: the environment's generation and whether it allows mocks, and each live node with whether it
     * holds the store as it is now, its generation, its last read and the objects it left out, which it names up to a hundred and counts.
     */
    private static JsonObject environment(ConfigStore.Overview overview) {
        JsonArray nodes = new JsonArray();
        for (ConfigStore.NodeReport n : overview.nodes()) {
            boolean current = n.configEpoch().isPresent() && n.configEpoch().getAsLong() == n.epoch() && n.generation().isPresent()
                    && n.generation().getAsLong() == overview.generation() && n.stamp().isPresent() && n.stamp().getAsLong() == overview.stamp();
            JsonArray leftOut = new JsonArray();
            n.refused().forEach(ref -> leftOut.add(new JsonObject().put("kind", ref.kind().id()).put("key", ref.key())));
            nodes.add(new JsonObject().put("node", n.node()).put("current", current)
                    .put("generation", n.generation().isPresent() ? n.generation().getAsLong() : null)
                    .put("readAt", n.readAt().map(Instant::toString).orElse(null)).put("leftOut", leftOut).put("leftOutCount", n.refusedCount()));
        }
        return new JsonObject().put("generation", overview.generation()).put("mocksAllowed", overview.mocksAllowed()).put("nodes", nodes);
    }

    // --- the login, the caller's own and the users (on the request's virtual thread)

    /**
     * {@code POST /admin/sessions {name, password, device?}}: a new session, 201 {@code {session, expiresAt, idleSeconds, device?,
     * me}}. A login with {@code device} asks for a device: the value is the device credential the browser holds, or {@code ""}
     * for none. A valid one is counted on its own row of the login limit; anything else counts as none. The answer carries
     * {@code device} only when the node issued a new one, which the browser keeps in place of the one it sent. A login without
     * {@code device} asks for none and is counted on its name's row.
     */
    private Reply login(RoutingContext ctx) {
        String shape = "the body is {\"name\": \"<name>\", \"password\": \"<password>\"}";
        JsonObject o = object(ctx, shape, Set.of("name", "password"), Set.of("device"));
        if (o.containsKey("device") && !(o.getValue("device") instanceof String)) throw new Refused(shape);
        Access.Login login = access.login(text(o, "name", shape), text(o, "password", shape), Optional.ofNullable(o.getString("device")),
                remote(ctx));
        JsonObject answer = new JsonObject().put("session", login.session()).put("expiresAt", login.expiresAt().toString())
                .put("idleSeconds", login.idleSeconds());
        login.device().ifPresent(d -> answer.put("device", d));
        return Json.of(201, answer.put("me", me(login.caller(), Optional.of(login.expiresAt()))));
    }

    /**
     * {@code GET /admin/me}: the caller's name (for a caller without an account the name the audit records), its kind (null
     * without an account), its roles, every action it may do, how it proved who it is, whether it holds a first password, when
     * its session or token ends at the latest (null for any other caller), and whether the node holds an emergency key, which only an
     * administrator is told. {@code refused} names each action it may not do, with the {@code code} and {@code needs} its 403 would
     * carry.
     */
    private JsonObject me(Caller who, Optional<Instant> expiresAt) {
        JsonArray actions = new JsonArray();
        JsonObject refused = new JsonObject();
        for (Action action : Action.values()) {
            Optional<AccessRefused> refusal = who.refusal(action, PackageRef.ANY);
            if (refusal.isEmpty()) actions.add(action.id());
            else refused.put(action.id(), new JsonObject().put("code", refusal.get().code().code()).put("needs", ids(refusal.get().needs())));
        }
        return new JsonObject().put("name", who.name()).put("kind", who.kind() == Kind.NONE ? null : who.kind().id())
                .put("roles", ids(who.roles())).put("actions", actions).put("refused", refused).put("via", who.via().id()).put("firstPassword", who.firstPassword())
                .put("expiresAt", expiresAt.map(Instant::toString).orElse(null))
                .put("emergencyKey", access.emergencyKey() && who.roles().contains(Role.ADMINISTRATOR));
    }

    /** {@code POST /admin/me/password {current, next}}: 204 once the caller's own password changed. */
    private Reply password(RoutingContext ctx, Caller who) {
        String shape = "the body is {\"current\": \"<password>\", \"next\": \"<password>\"}";
        JsonObject o = object(ctx, shape, Set.of("current", "next"), Set.of());
        access.changePassword(who.acting(Action.SELF_PASSWORD, PackageRef.ANY, remote(ctx)), text(o, "current", shape), text(o, "next", shape));
        return NO_CONTENT;
    }

    /** {@code GET /admin/users}: every user, by name. */
    private Reply users() {
        JsonArray items = new JsonArray();
        access.users().forEach(u -> items.add(user(u)));
        return Json.of(200, new JsonObject().put("items", items));
    }

    /** {@code GET /admin/users/{name}}: the user, or 404. */
    private Reply user(String name) {
        return access.user(name).map(u -> Json.of(200, user(u))).orElseGet(() -> Json.of(404, error(RefusalCode.NO_USER, "no user " + name, Map.of("name", name))));
    }

    /**
     * {@code POST /admin/users {name, kind, roles}}: each role checked as an {@code add} by the action that grants it, then a new
     * user; 201 {@code {name, firstPassword}} for a person, {@code {name}} for a technical account.
     */
    private Reply createUser(RoutingContext ctx, Caller who) {
        String shape = "the body is {\"name\": \"<name>\", \"kind\": \"person\" or \"technical\", \"roles\": [\"<role>\", …]}";
        JsonObject o = object(ctx, shape, Set.of("name", "kind"), Set.of("roles"));
        String name = text(o, "name", shape);
        String kind = text(o, "kind", shape);
        Kind account = Kind.ofAccount(kind).orElseThrow(() -> new Refused("kind is person or technical, was '" + kind + "'"));
        Set<Role> roles = roles(o, "roles", shape);
        roles.forEach(role -> who.require(grants(role), PackageRef.ANY));
        Optional<String> first = access.createUser(who.acting(Action.USER_CREATE, PackageRef.ANY, remote(ctx)), name, account, roles);
        JsonObject answer = new JsonObject().put("name", name);
        first.ifPresent(password -> answer.put("firstPassword", password));
        return Json.of(201, answer);
    }

    /**
     * {@code POST /admin/users/{name}/roles {add, remove}}: each role checked by the action that grants it, then the change by
     * difference; 200 {@code {name, roles, added, removed}}. A body that names no role is 400.
     */
    private Reply roles(RoutingContext ctx, Caller who) {
        String shape = "the body is {\"add\": [\"<role>\", …], \"remove\": [\"<role>\", …]}";
        JsonObject o = object(ctx, shape, Set.of(), Set.of("add", "remove"));
        Set<Role> add = roles(o, "add", shape);
        Set<Role> remove = roles(o, "remove", shape);
        Set<Role> touched = EnumSet.noneOf(Role.class);
        touched.addAll(add);
        touched.addAll(remove);
        if (touched.isEmpty()) throw new Refused("add or remove at least one role");
        touched.forEach(role -> who.require(grants(role), PackageRef.ANY));
        Action acting = touched.stream().allMatch(role -> role == Role.ADMINISTRATOR) ? Action.ADMINISTRATORS_CHANGE : Action.ROLES_CHANGE;
        Access.RolesChanged changed = access.changeRoles(who.acting(acting, PackageRef.ANY, remote(ctx)), ctx.pathParam("name"), add, remove);
        return Json.of(200, new JsonObject().put("name", changed.name()).put("roles", ids(changed.roles())).put("added", ids(changed.added()))
                .put("removed", ids(changed.removed())));
    }

    /**
     * {@code POST /admin/me/tokens {name, days?, password}}: a token of the caller's own, the password asked again; 201
     * {@code {id, name, expiresAt, token}}, the token shown once.
     */
    private Reply issueOwnToken(RoutingContext ctx, Caller who) {
        String shape = "the body is {\"name\": \"<name>\", \"days\": <days>, \"password\": \"<password>\"}, days optional";
        JsonObject o = object(ctx, shape, Set.of("name", "password"), Set.of("days"));
        return issued(access.issueOwnToken(who.acting(Action.SELF_TOKEN_ISSUE, PackageRef.ANY, remote(ctx)), text(o, "password", shape),
                text(o, "name", shape), days(o, shape)));
    }

    /** {@code POST /admin/users/{name}/tokens {name, days?}}: a token of the technical account, 201 as {@link #issueOwnToken}. */
    private Reply issueToken(RoutingContext ctx, Caller who) {
        String shape = "the body is {\"name\": \"<name>\", \"days\": <days>}, days optional";
        JsonObject o = object(ctx, shape, Set.of("name"), Set.of("days"));
        return issued(access.issueToken(who.acting(Action.TOKEN_ISSUE, PackageRef.ANY, remote(ctx)), ctx.pathParam("name"),
                text(o, "name", shape), days(o, shape)));
    }

    /** {@code POST /admin/tokens/ceiling {maxDays}}: 200 {@code {maxDays, defaultDays}}. */
    private Reply ceiling(RoutingContext ctx, Caller who) {
        String shape = "the body is {\"maxDays\": <days>}";
        JsonObject o = object(ctx, shape, Set.of("maxDays"), Set.of());
        if (!(o.getValue("maxDays") instanceof Integer maxDays)) throw new Refused(shape);
        return Json.of(200, ceiling(access.changeCeiling(who.acting(Action.TOKEN_CEILING, PackageRef.ANY, remote(ctx)), maxDays)));
    }

    /** The whole number in the member {@code days} of {@code o}, empty when it is absent; else a 400 saying {@code shape}. */
    private static OptionalInt days(JsonObject o, String shape) {
        if (!o.containsKey("days")) return OptionalInt.empty();
        if (o.getValue("days") instanceof Integer days) return OptionalInt.of(days);
        throw new Refused(shape);
    }

    /** The {@code expiringWithin} parameter, an ISO-8601 duration such as {@code P14D}; empty without one, else a 400. */
    private static Optional<Duration> expiringWithin(RoutingContext ctx) {
        String within = ctx.request().getParam("expiringWithin");
        if (within == null) return Optional.empty();
        try {
            return Optional.of(Duration.parse(within));
        } catch (DateTimeParseException notADuration) {
            throw new Refused("expiringWithin is an ISO-8601 duration such as P14D, not '" + within + "'");
        }
    }

    /** The action that grants or takes {@code role}: {@code administrators.change} for the administrator role, {@code roles.change} for any other. */
    private static Action grants(Role role) {
        return role == Role.ADMINISTRATOR ? Action.ADMINISTRATORS_CHANGE : Action.ROLES_CHANGE;
    }

    /** The entry of the secret {@code :name}: 404 {@code no-secret} when no source holds it and nothing uses it. */
    private static Reply secret(RoutingContext ctx, SecretStore store) {
        String name = ctx.pathParam("name");
        return store.entry(name).await().map(entry -> Json.of(200, entry)).orElseGet(() -> Json.of(404, error(RefusalCode.NO_SECRET,
                "no source holds the secret '" + name + "' and nothing uses it", Map.of("name", name))));
    }

    /**
     * The body of a secret's create ({@code named}) or overwrite: a JSON object of its kind's members, which the store checks;
     * else a 400 saying the shape. A refusal never echoes the body.
     */
    private static JsonObject secretBody(RoutingContext ctx, boolean named) {
        String shape = "the body is {" + (named ? "\"name\": \"<name>\", " : "") + "\"kind\": \"value\", \"value\": …}, {"
                + (named ? "\"name\", " : "") + "\"kind\": \"credentials\", \"user\", \"password\"} or {" + (named ? "\"name\", " : "")
                + "\"kind\": \"key-pair\", \"privateKey\", \"certificates\"}";
        return object(ctx, shape, named ? Set.of("name", "kind") : Set.of("kind"), Set.of("value", "user", "password", "privateKey", "certificates"));
    }

    /** 200 {@code {name}}, the user of the route's path. */
    private static Reply named(RoutingContext ctx) {
        return Json.of(200, new JsonObject().put("name", ctx.pathParam("name")));
    }

    /**
     * The request's body as a JSON object holding every member of {@code required} and none but those and {@code optional}; else a
     * 400 saying {@code shape}: no body, a form, no JSON, or another shape.
     */
    private static JsonObject object(RoutingContext ctx, String shape, Set<String> required, Set<String> optional) {
        String type = ctx.request().getHeader(HttpHeaders.CONTENT_TYPE);
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) throw new Refused(shape + ": it is a form");
        if (ctx.body().isEmpty()) throw new Refused(shape + ": there is none");
        Object json;
        try {
            json = io.vertx.core.json.Json.decodeValue(ctx.body().buffer());
        } catch (DecodeException notJson) {
            throw new Refused(shape + ": it is not JSON");
        }
        if (!(json instanceof JsonObject o) || !o.fieldNames().containsAll(required)
                || !o.fieldNames().stream().allMatch(m -> required.contains(m) || optional.contains(m))) {
            throw new Refused(shape);
        }
        return o;
    }

    /** The string member {@code name} of {@code o}, else a 400 saying {@code shape}. */
    private static String text(JsonObject o, String name, String shape) {
        if (o.getValue(name) instanceof String text) return text;
        throw new Refused(shape);
    }

    /** The roles listed by their ids in the member {@code name} of {@code o}: none when it is absent, else a 400 for another shape or an unknown role. */
    private static Set<Role> roles(JsonObject o, String name, String shape) {
        if (!o.containsKey(name)) return Set.of();
        if (!(o.getValue(name) instanceof JsonArray ids)) throw new Refused(shape);
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (Object id : ids) {
            if (!(id instanceof String text)) throw new Refused(shape);
            roles.add(Role.byId(text).orElseThrow(() -> new Refused("no role '" + text + "'")));
        }
        return roles;
    }

    // --- JSON

    /**
     * {@code GET /admin/flows}: every flow deployed here with its state; a flow whose consumer waits for its next attempt also
     * carries {@code restart}: its failures in a row, when the next attempt is due and the last failure's cause.
     */
    private static Reply flows(Deployments deployments) {
        JsonArray items = new JsonArray();
        Map<String, Deployments.Restarting> restarting = deployments.restarting();
        for (DeployedFlow f : deployments.flows()) {
            JsonObject item = new JsonObject().put("id", f.id()).put("package", f.pkg()).put("packageVersion", f.packageVersion()).put("commit", f.commit().orElse(null))
                    .put("flowVersion", f.flowVersion()).put("delivery", f.delivery()).put("hash", f.hash()).put("state", f.state().name());
            Deployments.Restarting r = restarting.get(f.id());
            if (r != null) item.put("restart", new JsonObject().put("failures", r.failures()).put("next", r.next().toString()).put("cause", r.cause()));
            items.add(item);
        }
        return Json.of(200, new JsonObject().put("items", items));
    }

    /**
     * {@code GET /admin/flows/{flow}/cpu}: 404 for a flow not deployed here; else the sampler's rows of the flow and its totals,
     * sampling on or, once its stream failed, failed; or {@code sampling:"off"} without a sampler.
     */
    private Reply cpu(RoutingContext ctx, Deployments deployments) {
        String flow = ctx.pathParam("flow");
        if (deployments.flows().stream().noneMatch(f -> f.id().equals(flow))) {
            return Json.of(404, error(RefusalCode.NO_FLOW, "no flow " + flow + " deployed on this node", Map.of("flow", flow)));
        }
        JsonObject answer = new JsonObject().put("flow", flow);
        if (sampling.isEmpty()) return Json.of(200, answer.put("sampling", "off").put("items", new JsonArray()));
        StepCpu cpu = sampling.get().cpu();
        JsonArray items = new JsonArray();
        for (StepCpu.Row r : cpu.rows(flow)) {
            items.add(new JsonObject().put("step", r.step()).put("name", r.name()).put("executions", r.executions()).put("samples", r.samples())
                    .put("cpuNanos", r.cpuNanos()));
        }
        StepCpu.Totals t = cpu.totals();
        JsonObject totals = new JsonObject().put("samples", t.samples()).put("attributed", t.attributed()).put("pending", t.pending())
                .put("unattributed", t.unattributed()).put("platform", t.platform()).put("lost", t.lost()).put("dropped", t.dropped());
        return Json.of(200, answer.put("sampling", sampling.get().failed().getAsBoolean() ? "failed" : "on").put("period", sampling.get().period().toString()).put("since", sampling.get().since().toString())
                .put("items", items).put("totals", totals));
    }

    /**
     * {@code GET /admin/components}: the node's environment and advertised origin, then every component the node serves, sorted by
     * scheme, with its HTTP prefix and its forms as {@code settings}.
     */
    private static Reply components(Deployments deployments) {
        JsonArray items = new JsonArray();
        deployments.forms().forEach((scheme, forms) -> items.add(new JsonObject().put("scheme", scheme)
                .put("httpPrefix", deployments.httpPrefix(scheme).orElse(null)).put("settings", new JsonObject(forms.toJson(scheme)))));
        return Json.of(200, new JsonObject().put("environment", deployments.environment()).put("httpOrigin", deployments.httpOrigin().orElse(null))
                .put("items", items));
    }

    /**
     * {@code GET /admin/modules}: each module, then each plugin, as it loaded, with what it brings; its services are those
     * {@code modules} serves for it, sorted by path, then method.
     */
    private static Reply listed(Modules modules) {
        JsonArray items = new JsonArray();
        for (Plugins.Listed l : modules.listed()) {
            JsonArray services = new JsonArray();
            modules.services().getOrDefault(l.name(), List.of()).stream()
                    .sorted(Comparator.comparing(AdminService::path).thenComparing(AdminService::method))
                    .forEach(s -> services.add(new JsonObject().put("method", s.method()).put("path", s.path())));
            JsonObject brings = new JsonObject().put("schemes", new JsonArray(l.schemes())).put("steps", new JsonArray(l.steps()))
                    .put("languages", new JsonArray(l.languages())).put("services", services).put("command", l.command());
            items.add(new JsonObject().put("kind", l.kind()).put("name", l.name()).put("version", l.version().orElse(null)).put("api", l.api())
                    .put("sha256", l.sha256().orElse(null)).put("from", l.from().orElse(null)).put("brings", brings));
        }
        return Json.of(200, new JsonObject().put("items", items));
    }

    /**
     * {@code GET /admin/steps}: every step type a module or plugin of the node builds, sorted by type, with the JSON Schema its
     * step's options are checked against; {@code configSchema} is null when the factory's schema is no JSON object.
     */
    private static Reply steps(Deployments deployments) {
        JsonArray items = new JsonArray();
        deployments.stepSchemas().forEach((type, schema) -> items.add(new JsonObject().put("type", type).put("configSchema", schemaObject(schema))));
        return Json.of(200, new JsonObject().put("items", items));
    }

    /** {@code text} as a JSON object, or null when it is none. */
    private static JsonObject schemaObject(String text) {
        try {
            return new JsonObject(text);
        } catch (RuntimeException notAnObject) {
            return null;
        }
    }

    /** {@code GET /admin/targets}: the gauge of every target the deployed flows named, sorted by target. */
    private static Reply targets(Deployments deployments) {
        JsonArray items = new JsonArray();
        for (TargetLimit.Gauge g : deployments.targets()) {
            items.add(new JsonObject().put("target", g.target()).put("maxCalls", g.maxCalls()).put("maxWaitMs", g.maxWait().toMillis())
                    .put("inFlight", g.inFlight()).put("waiting", g.waiting()).put("refused", g.refused()));
        }
        return Json.of(200, new JsonObject().put("items", items));
    }

    /**
     * {@code POST /admin/deploy}: the deploy of the body's commit of the body's repository expecting the body's {@code expect}, or
     * with {@code ?check=true} its check, which compares nothing without one; the body and the {@code check} parameter are refused
     * (400) before the deploy is asked.
     */
    private static Reply deploy(RoutingContext ctx, Caller who, Deployments deployments) throws IOException {
        Actor by = who.acting(Action.PACKAGE_DEPLOY, PackageRef.ANY, remote(ctx));
        String check = Objects.requireNonNullElse(ctx.request().getParam("check"), "false");
        if (!check.equals("true") && !check.equals("false")) throw new Refused("check is true or false, was '" + check + "'");
        String shape = "the body is {\"repository\": \"<name>\", \"commit\": \"<commit or ref>\", \"expect\": \"<commit>\" | \"none\" | \"any\"}";
        String type = ctx.request().getHeader(HttpHeaders.CONTENT_TYPE);
        if (type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data")) throw new Refused(shape + ": it is a form");
        if (ctx.body().isEmpty()) throw new Refused(shape + ": there is none");
        Object json;
        try {
            json = io.vertx.core.json.Json.decodeValue(ctx.body().buffer());
        } catch (DecodeException notJson) {
            throw new Refused(shape + ": it is not JSON");
        }
        boolean dry = check.equals("true");
        if (!(json instanceof JsonObject o) || !(o.getValue("repository") instanceof String repository)
                || !(o.getValue("commit") instanceof String commit)) {
            throw new Refused(shape);
        }
        boolean named = o.containsKey("expect");   // a null is no state: 400, as any value other than a string
        if (o.size() != (named ? 3 : 2) || named && !(o.getValue("expect") instanceof String)) throw new Refused(shape);
        if (!named && !dry) throw new Refused(shape + ": a deploy names the state it expects, and this one has no expect");
        String expect = named ? o.getString("expect") : Deployments.ANY;   // a check without one compares nothing
        try {
            DeployResult r = dry ? deployments.check(repository, commit, expect) : deployments.deploy(repository, commit, expect, by);
            DeployedPackage p = r.pkg();
            return Json.of(200, new JsonObject().put("package", p.name()).put("version", p.version()).put("repository", p.repository().orElse(null))
                    .put("commit", p.commit().orElse(null)).put("flows", new JsonArray(p.flows()))
                    .put("added", new JsonArray(r.added())).put("swapped", new JsonArray(r.swapped())).put("removed", new JsonArray(r.removed()))
                    .put("replaces", r.replaces().orElse(null)));
        } catch (DeployRefusedException refused) {
            return refusal(refused);
        }
    }

    /**
     * {@code GET /admin/pool/packages}: the pool's records and what each node whose lease holds runs; a read that fails, or a pool
     * holding more than one read takes, is the deploy's {@code FAILED}, 500 {@code {error}}.
     */
    private static Reply poolPackages(Deployments deployments) {
        DeploymentStore.Listings listings;
        try {
            listings = deployments.listings();
        } catch (DeployRefusedException refused) {
            return refusal(refused);
        }
        JsonArray records = new JsonArray();
        listings.records().forEach(r -> records.add(new JsonObject().put("name", r.name()).put("version", r.version()).put("repository", r.repository())
                .put("commit", r.commit())));
        JsonArray nodes = new JsonArray();
        listings.nodes().forEach((node, runs) -> {
            JsonArray packages = new JsonArray();
            runs.forEach(p -> packages.add(new JsonObject().put("name", p.name()).put("version", p.version()).put("repository", p.repository())
                    .put("commit", p.commit()).put("deployedAt", p.deployedAt().toString()).put("state", p.state().name()).put("behind", new JsonArray(p.behind()))));
            nodes.add(new JsonObject().put("node", node).put("packages", packages));
        });
        return Json.of(200, new JsonObject().put("records", records).put("nodes", nodes));
    }

    private static Reply packages(Deployments deployments) {
        JsonArray items = new JsonArray();
        for (DeployedPackage p : deployments.packages()) {
            JsonArray files = new JsonArray();
            for (DeployedPackage.PackageFile f : p.files()) files.add(new JsonObject().put("path", f.path()).put("sha256", f.sha256()));
            JsonArray folders = new JsonArray();   // every folder below the root, null where its folder.yaml sets nothing
            p.folders().forEach(f -> folders.add(new JsonObject().put("path", f.path()).put("namespace", f.namespace().orElse(null))
                    .put("description", f.description().orElse(null))));
            JsonArray artifacts = new JsonArray();   // a test's id is null
            p.artifacts().forEach(a -> artifacts.add(new JsonObject().put("path", a.path()).put("kind", a.kind()).put("id", a.id().orElse(null))));
            JsonArray dependencies = new JsonArray();   // the closure, each marked direct or not
            p.dependencies().forEach(d -> dependencies.add(new JsonObject().put("name", d.name()).put("version", d.version()).put("content", d.content())
                    .put("repository", d.repository()).put("commit", d.commit()).put("direct", d.direct())));
            items.add(new JsonObject().put("name", p.name()).put("version", p.version()).put("repository", p.repository().orElse(null))
                    .put("commit", p.commit().orElse(null)).put("deployedAt", p.deployedAt().toString()).put("flows", new JsonArray(p.flows())).put("files", files)
                    .put("folders", folders).put("artifacts", artifacts).put("state", p.state().name()).put("problems", new JsonArray(p.problems()))
                    .put("content", p.content()).put("dependencies", dependencies));
        }
        return Json.of(200, new JsonObject().put("items", items));
    }

    /**
     * {@code DELETE /admin/packages/{name}}: the undeploy's result, or its refusal (the pool's refusal of its queue question or its
     * record delete included: 503); a record delete or a queue question that fails otherwise throws, a 500.
     */
    private static Reply undeployed(RoutingContext ctx, Caller who, Deployments deployments) throws IOException {
        Actor by = who.acting(Action.PACKAGE_UNDEPLOY, PackageRef.ANY, remote(ctx));
        try {
            UndeployResult r = deployments.undeploy(ctx.pathParam("name"), by);
            return Json.of(200, new JsonObject().put("name", r.name()).put("version", r.version()).put("flowsRemoved", new JsonArray(r.flowsRemoved())));
        } catch (DeployRefusedException refused) {
            return refusal(refused);
        }
    }

    /**
     * A refusal by its kind: 400, 403, 404, 409, 412 (with the state the pool records), 422 (with every problem), 500 (with every
     * problem of a redeploy rolled back), 503 (the node stops; the pool refused the record's write, with every problem of a
     * redeploy rolled back, a deploy's or an undeploy's queue question, or an undeploy's record delete).
     */
    private static Reply refusal(DeployRefusedException refused) {
        int status = switch (refused.kind()) {
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case CONFLICT -> 409;
            case INVALID -> 422;
            case FAILED -> 500;
            case STOPPING, BUSY -> 503;
            case NOT_FOUND -> 404;
            case STALE -> 412;
        };
        JsonObject body = error(refused.code(), refused.error(), refused.values());
        if (!refused.problems().isEmpty()) body.put("problems", new JsonArray(refused.problems()));
        refused.replaces().ifPresent(replaces -> body.put("replaces", replaces));
        return Json.of(status, body);
    }

    /** 503 for a request the node's Postgres pool refused a connection: a fixed text, the client's message not shown. */
    private static Reply busy() {
        return Json.of(503, error(RefusalCode.POOL_BUSY, "the database pool is busy; retry", Map.of()));
    }

    private static <T> JsonObject page(Page<T> page, Function<T, Object> item) {
        JsonArray items = new JsonArray();
        for (T t : page.items()) items.add(item.apply(t));
        return new JsonObject().put("items", items).put("next", page.next().orElse(null));
    }

    private static JsonObject step(Snapshot s) {
        return new JsonObject()
                .put("step", s.step())
                .put("attempt", s.attempt())
                .put("name", s.stepName())
                .put("outcome", s.outcome().name())
                .put("error", s.error().orElse(null))
                .put("startedAt", s.startedAt().toString())
                .put("wallNanos", s.wallNanos())
                .put("cpuNanos", s.cpuNanos() == Snapshot.CPU_UNKNOWN ? null : s.cpuNanos())
                .put("headers", new JsonObject(new HashMap<>(s.headers().asMap())))
                .put("props", props(s.props().asMap()))
                .put("keys", new JsonObject(new HashMap<>(s.keys().asMap())))
                .put("body", new JsonObject().put("hash", hash(s.bodyHash())).put("size", s.bodySize()).put("kept", s.bodyKey().isPresent()))
                .put("versions", new JsonObject(new HashMap<>(s.versions())))
                .put("log", StepLogJson.json(s.log()));
    }

    private static JsonObject listed(PgMessageList.Listed m) {
        return new JsonObject()
                .put("id", m.id())
                .put("flow", m.flow())
                .put("status", m.status().name().toLowerCase(Locale.ROOT))
                .put("startedAt", m.startedAt().toString())
                .put("durationNanos", m.durationNanos())
                .put("keys", new JsonObject(new HashMap<>(m.keys())));
    }

    private static JsonObject parked(Parked p) {
        return new JsonObject()
                .put("id", p.messageId())
                .put("flow", p.flow())
                .put("step", p.step())
                .put("name", p.stepName())
                .put("error", p.error())
                .put("parkedAt", p.parkedAt().toString())
                .put("headers", new JsonObject(new HashMap<>(p.headers().asMap())))
                .put("props", props(p.props().asMap()))
                .put("keys", new JsonObject(new HashMap<>(p.keys().asMap())))
                .put("body", new JsonObject().put("hash", hash(p.bodyHash())).put("size", p.bodySize()))
                .put("versions", new JsonObject(new HashMap<>(p.versions())));
    }

    private static JsonObject held(WorkQueue.Held h) {
        WorkQueue.Item i = h.item();
        return new JsonObject()
                .put("seq", i.seq())
                .put("messageId", i.messageId())
                .put("flow", i.flow())
                .put("step", i.step())
                .put("partitionKey", i.partitionKey())
                .put("attempts", i.attempts())
                .put("heldAt", h.heldAt().toString())
                .put("blocks", h.blocks());
    }

    private static JsonObject stopped(WorkQueue.Stopped s) {
        WorkQueue.Item i = s.item();
        return new JsonObject()
                .put("seq", i.seq())
                .put("messageId", i.messageId())
                .put("flow", i.flow())
                .put("step", i.step())
                .put("partitionKey", i.partitionKey())
                .put("attempts", i.attempts())
                .put("stoppedAt", s.stoppedAt().toString())
                .put("blocks", s.blocks())
                .put("error", s.error().orElse(null));
    }

    private static JsonObject lost(WorkQueue.Lost l) {
        WorkQueue.Item i = l.item();
        return new JsonObject()
                .put("seq", i.seq())
                .put("messageId", i.messageId())
                .put("flow", i.flow())
                .put("partitionKey", i.partitionKey())
                .put("lostAt", l.lostAt().toString())
                .put("blocks", l.blocks());
    }

    private static String hash(Hash h) {
        return h.algorithm() + ":" + h.hex();
    }

    /** Props as JSON: JSON-native values as they are, anything else by its string form (as the Postgres store keeps them). */
    private static JsonObject props(Map<String, Object> props) {
        JsonObject o = new JsonObject();
        props.forEach((k, v) -> o.put(k, v instanceof Number || v instanceof Boolean || v instanceof String ? v : String.valueOf(v)));
        return o;
    }

    /** A refusal's body: {@code {error, code, values}}, the English text, its code and the values it names. */
    private static JsonObject error(RefusalCode code, String message, Map<String, Object> values) {
        return new JsonObject().put("error", message).put("code", code.code()).put("values", new JsonObject(new TreeMap<>(values)));
    }

    /** The ids of {@code roles}, in the order of {@link Role}. */
    private static JsonArray ids(Set<Role> roles) {
        JsonArray ids = new JsonArray();
        roles.stream().sorted().forEach(role -> ids.add(role.id()));
        return ids;
    }

    /** 201 {@code {id, name, expiresAt, token}}: the one answer that carries the token. */
    private static Reply issued(Access.Issued issued) {
        return Json.of(201, new JsonObject().put("id", issued.id()).put("name", issued.name()).put("expiresAt", issued.expiresAt().toString())
                .put("token", issued.token()));
    }

    /** 200 {@code {tokens, maxDays, defaultDays}}. */
    private static Reply tokens(Access.Tokens listed) {
        JsonArray tokens = new JsonArray();
        listed.tokens().forEach(t -> tokens.add(token(t)));
        return Json.of(200, new JsonObject().put("tokens", tokens).mergeIn(ceiling(listed.ceiling())));
    }

    private static JsonObject ceiling(Access.Ceiling ceiling) {
        return new JsonObject().put("maxDays", ceiling.maxDays()).put("defaultDays", ceiling.defaultDays());
    }

    /** A live token by its id: never the secret. */
    private static JsonObject token(Access.Token t) {
        return new JsonObject().put("id", t.id()).put("user", t.user()).put("name", t.name()).put("createdAt", t.createdAt().toString())
                .put("createdBy", t.createdBy()).put("expiresAt", t.expiresAt().toString())
                .put("lastUsedAt", t.lastUsedAt().map(Instant::toString).orElse(null));
    }

    /** A user as an administrator sees them: never a secret. */
    private static JsonObject user(Access.User u) {
        JsonArray roles = new JsonArray();
        u.roles().forEach(g -> roles.add(new JsonObject().put("role", g.role().id()).put("grantedAt", g.grantedAt().toString())
                .put("grantedBy", g.grantedBy()).put("grantedVia", g.grantedVia())));
        JsonArray sessions = new JsonArray();
        u.sessions().forEach(live -> sessions.add(new JsonObject().put("id", live.id()).put("createdAt", live.createdAt().toString())
                .put("lastSeenAt", live.lastSeenAt().toString()).put("expiresAt", live.expiresAt().toString())));
        return new JsonObject().put("name", u.name()).put("kind", u.kind().id()).put("roles", roles)
                .put("blockedAt", u.blockedAt().map(Instant::toString).orElse(null)).put("firstPassword", u.firstPassword())
                .put("createdAt", u.createdAt().toString()).put("createdBy", u.createdBy()).put("sessions", sessions)
                .put("tokens", new JsonArray(u.tokens().stream().map(AdminServer::token).toList()));
    }

    // --- answers (on the verticle's context)

    private interface Reply {
        /** Sends the answer; completes once it is written. */
        Future<?> send(HttpServerResponse res);
    }

    /**
     * A JSON answer of {@code type} with {@code headers}, encoded where it is built (the route's virtual thread); its content-length
     * overrides any set before.
     */
    private record Json(int status, String type, Buffer body, Map<String, String> headers) implements Reply {
        static Json of(int status, JsonObject body) {
            return new Json(status, JSON, body.toBuffer(), Map.of());
        }

        @Override
        public Future<?> send(HttpServerResponse res) {
            res.setStatusCode(status).putHeader("content-type", type);
            headers.forEach(res::putHeader);
            return res.putHeader("content-length", Integer.toString(body.length())).end(body);
        }
    }

    /** 204: done, nothing to say. */
    private static final Reply NO_CONTENT = res -> res.setStatusCode(204).end();

    /**
     * The stored bytes, streamed with back-pressure under the head checked on the virtual thread (content-type and length
     * from the snapshot). A stream that fails is never a complete answer: before the head is out a 500 — or, when it fails
     * with a {@link BlobNotFoundException}, the store holding nothing under the key (a durable tier opens lazily and finds
     * no row only when read), the 404 {@code gone} — after it a reset, whatever the failure.
     */
    private record Bytes(MultiMap head, ReadStream<Buffer> bytes, JsonObject gone) implements Reply {
        @Override
        public Future<?> send(HttpServerResponse res) {
            res.setStatusCode(200).headers().setAll(head);
            return bytes.pipe().endOnFailure(false).to(res).onFailure(failed -> {
                if (failed instanceof BlobNotFoundException && !res.headWritten()) {
                    Json.of(404, gone).send(res);
                    return;
                }
                LOG.log(System.Logger.Level.WARNING, "streaming a stored body failed", failed);
                fail(res);
            });
        }
    }
}
