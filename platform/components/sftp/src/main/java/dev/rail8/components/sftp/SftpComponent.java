package dev.rail8.components.sftp;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobStore;
import dev.rail8.api.Body;
import dev.rail8.api.BodyTooLargeException;
import dev.rail8.api.Component;
import dev.rail8.api.Connection;
import dev.rail8.api.Consumer;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Health;
import dev.rail8.api.IdGenerator;
import dev.rail8.api.Inbound;
import dev.rail8.api.Key;
import dev.rail8.api.Outbound;
import dev.rail8.api.Producer;
import dev.rail8.api.Secret;
import dev.rail8.api.Timer;
import dev.rail8.api.settings.Choice;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Forms;
import dev.rail8.api.settings.Settings;
import dev.rail8.api.settings.Tab;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The {@code sftp} component: a polling consumer on the Apache MINA SSHD client; no producer yet. Its sender reaches a
 * connection, which says where the server is and how to log in: {@code host} (required), {@code port} (1–65535, 22),
 * {@code user}, exactly one of the secrets {@code password} (a plain value, or credentials that also give the user, and then
 * {@code user} is left out) / {@code privateKey} (an OpenSSH private key as text, or a key pair), the secret {@code hostKey} (the server
 * key's {@code SHA256:} fingerprint — a mismatch refuses the session, there is no accept-all), {@code connectTimeoutMs}
 * (10 000) and {@code ioTimeoutMs} (30 000). The flow says what is polled: {@code dir} (none: the login directory),
 * {@code glob} ({@code *}), {@code intervalMs} (10 000), {@code maxFilesPerPoll} (100), {@code minAgeMs} (0),
 * {@code afterSuccess} ({@code delete} | {@code move}, then {@code moveTo}) and an optional {@code errorDir}; the largest
 * body is the flow's {@link Inbound#maxBodyBytes}. The secrets are read once at each login, never when a consumer is built,
 * so a changed secret is used from the next login on; a {@code hostKey} that is not a {@code SHA256:} fingerprint fails that
 * login, naming the field and never its value.
 *
 * <p>A round runs on a virtual thread the consumer starts (the timer only fires it; the next round is scheduled
 * {@code intervalMs} after it ended): it lists the directory, keeps the regular files whose name matches the glob and whose
 * server mtime is at least {@code minAgeMs} behind the injected clock, takes the oldest (mtime, name) first, at most
 * {@code maxFilesPerPoll}, one at a time. A file listed above {@code maxBodyBytes} is refused finally without being read;
 * otherwise it streams into the {@link BlobStore} bounded by {@code maxBodyBytes} and goes to the handler on a virtual thread
 * of its own, the round waiting for the answer. {@link Ack.Accepted} or {@link Ack.Reply} (the durable save is the
 * handler's) deletes the file or renames it into {@code moveTo} ({@code <name>.<id>} when the name is taken, the id of the
 * message the file was delivered as); a file already gone counts as done. Nothing is kept to recognise a file delivered
 * before: a file whose delete or move failed after it was accepted is delivered again in a later round as a new message,
 * and so is a file uploaded again. A retryable Nack, a failed answer, a throw or null keep the file and end the round (the poller pauses).
 * A final Nack, or a body above the limit, moves the file to {@code errorDir} if set, else leaves it and never takes it
 * again in this consumer's life. Any I/O failure ends the round and drops the session; the file stays. A round that cannot
 * connect, log in or list the directory also breaks the consumer ({@link Consumer#broken()}): it schedules no further round,
 * and the node starts a fresh consumer after its delay, longer with each failure in a row.
 *
 * <p>One SSH session per consumer, reused across rounds; one MINA client per consumer, its NIO2 pool sized to one thread
 * (transport-internal). Health is up while at least one consumer runs and none's last round failed to connect or list.
 *
 * @since 0.1
 */
public final class SftpComponent implements Component {

    static {
        // SSHD registers Bouncy Castle with the JVM when that jar is on the classpath, and then makes its ciphers, keys and
        // signatures there; it stays on the JDK's providers instead. This runs before any SSHD client of this class exists.
        SecurityUtils.setAPrioriDisabledProvider(SecurityUtils.BOUNCY_CASTLE, true);
    }

    /** The file's name. */
    public static final Key<String> FILE = new Key<>("sftp.file");
    /** The file's full remote path. */
    public static final Key<String> PATH = new Key<>("sftp.path");
    /** The file's server mtime, ISO-8601. */
    public static final Key<String> MTIME = new Key<>("sftp.mtime");

    static final int DEFAULT_PORT = 22;
    /** The tab of the settings that say which files a round takes and what becomes of them. */
    static final Tab POLLING = Tab.of("polling", "Polling");
    /** What a {@code hostKey} is: a server key's SHA256 fingerprint as OpenSSH prints it. */
    static final Pattern FINGERPRINT = Pattern.compile("SHA256:[A-Za-z0-9+/]+=*");
    /**
     * The forms, all for the in direction: the connection holds the server, the login and the timeouts; the flow holds which
     * files a round takes and what becomes of them.
     */
    static final Forms FORMS = new Forms(
            Settings.of(
                    Field.text("host", "Host", "The server's name or address.").required().in(),
                    Field.integer("port", "Port", "The server's port.").range(1, 65535).byDefault(DEFAULT_PORT).in(),
                    Field.text("user", "User", "The user to log in as; none when the password is credentials, which name it.").tab(Tab.SECURITY).in(),
                    Field.secret("password", "Password", "The user's password, or credentials of the user and the password; or set privateKey.",
                            Secret.Kind.VALUE, Secret.Kind.CREDENTIALS).tab(Tab.SECURITY).in(),
                    Field.secret("privateKey", "Private key", "The user's private key in OpenSSH form, or a key pair; or set password.",
                            Secret.Kind.VALUE, Secret.Kind.KEY_PAIR).tab(Tab.SECURITY).in(),
                    Field.secret("hostKey", "Host key", "The server key's fingerprint as OpenSSH prints it, SHA256: and its base64; a "
                            + "session with any other key is refused.").required().tab(Tab.SECURITY).in(),
                    Field.integer("connectTimeoutMs", "Connect timeout (ms)", "How long connecting and logging in may take, in milliseconds.")
                            .min(1).byDefault(10_000).in(),
                    Field.integer("ioTimeoutMs", "Idle timeout (ms)", "How long the session may sit idle, in milliseconds.")
                            .min(1).byDefault(30_000).in())
                .exactlyOne("password", "privateKey"),
            Settings.of(
                    Field.text("dir", "Directory", "The directory polled; none: the login directory.").tab(POLLING).in(),
                    Field.text("glob", "File names", "Which file names a round takes, as a glob such as *.xml.").byDefault("*").tab(POLLING).in(),
                    Field.integer("intervalMs", "Interval (ms)", "How long after a round the next one starts, in milliseconds.")
                            .min(1).byDefault(10_000).tab(POLLING).in(),
                    Field.integer("maxFilesPerPoll", "Files per round", "The most files one round takes.").min(1).byDefault(100).tab(POLLING).in(),
                    Field.integer("minAgeMs", "Minimum age (ms)", "How old a file must be before it is taken, in milliseconds: one still "
                            + "being written is left.").min(0).byDefault(0).tab(POLLING).in(),
                    Field.choice("afterSuccess", "After success", "What becomes of a file once it is taken in.",
                            Choice.of("delete", "Delete"), Choice.of("move", "Move")).byDefault("delete").tab(POLLING).in(),
                    Field.text("moveTo", "Move to", "The directory a file taken in is moved to.").required()
                            .visibleWhen("afterSuccess", "move").tab(POLLING).in(),
                    Field.text("errorDir", "Error directory", "The directory a refused file is moved to; none: it stays and is not "
                            + "taken again.").tab(POLLING).in()),
            Map.of(Direction.IN, Forms.Reach.CONNECTION));

    /** The MINA client's NIO2 I/O threads: the minimum; a consumer's round blocks on its own virtual thread. */
    static final int NIO_WORKERS = 1;

    private final BlobStore blobs;
    private final IdGenerator ids;
    private final Timer timer;
    private final Clock clock;
    private final Map<SftpConsumer, Boolean> running = new ConcurrentHashMap<>();   // value: its last round connected and listed
    private final AtomicLong handled = new AtomicLong();   // numbers the handler threads

    public SftpComponent(BlobStore blobs, IdGenerator ids, Timer timer, Clock clock) {
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public String scheme() { return "sftp"; }

    @Override public Forms forms() { return FORMS; }

    @Override
    public Consumer consumer(Inbound in, Function<Envelope, Future<Ack>> handler) {
        return new SftpConsumer(in, handler);
    }

    /**
     * Every sftp sender polls a directory other nodes may poll too: {@code <connection key> sftp://<host>:<port> <dir> <glob>},
     * the host lower-cased and an IPv6 one bracketed, {@code .} for the login directory. Nothing is opened.
     */
    @Override
    public Optional<String> polledPlace(Inbound in) {
        Connection c = connection(in);
        String host = required(c, "host");
        String literal = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return Optional.of(c.key() + " sftp://" + literal.toLowerCase(Locale.ROOT) + ":" + number(c.values(), "port", DEFAULT_PORT)
                + " " + text(in.settings(), "dir", ".") + " " + text(in.settings(), "glob", "*"));
    }

    /** Refused, as the component has no producer yet: the flow build reports it. */
    @Override
    public Producer producer(Outbound out) {
        throw new IllegalArgumentException("sftp has no producer before Phase 4");
    }

    @Override
    public Future<Health> health() {
        if (running.isEmpty()) return Future.succeededFuture(Health.down("no consumer polling"));
        return Future.succeededFuture(running.containsValue(Boolean.FALSE) ? Health.down("a consumer's last round could not connect or list") : Health.up());
    }

    // --- options

    private static String text(Map<String, Object> options, String key, String fallback) {
        Object v = options.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    /** The connection {@code in} names; an {@link IllegalArgumentException} when it names none. */
    private static Connection connection(Inbound in) {
        return Objects.requireNonNull(in, "in").connection().orElseThrow(() -> new IllegalArgumentException(
                "flow '" + in.flow() + "': an sftp sender polls through a connection, and none is named"));
    }

    private static String required(Connection c, String key) {
        Object v = c.values().get(key);
        if (v == null) throw new IllegalArgumentException("connection '" + c.key() + "': the setting '" + key + "' is required");
        return String.valueOf(v);
    }

    private static long number(Map<String, Object> options, String key, long fallback) {
        return options.get(key) instanceof Number n ? n.longValue() : fallback;
    }

    /** {@code name} in the remote directory {@code dir}. */
    private static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private static boolean noSuchFile(SftpException e) {
        return e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE;
    }

    /** A listed file. */
    private record Remote(String name, String path, long size, Instant mtime) {
        /** What a final refusal without an error directory skips: this path at this mtime and size. */
        String refusedAs() { return path + "@" + mtime + "/" + size; }
    }

    // --- consumer

    /** @since 0.1 */
    public final class SftpConsumer implements Consumer {
        private final Function<Envelope, Future<Ack>> handler;
        private final String name;
        private final String host;
        private final int port;
        private final String dir;          // null: the login directory
        private final String user;         // null when the password is credentials, which give the user
        private final Secret password;     // null when the connection logs in with a private key; read at each login
        private final Secret privateKey;   // null when it logs in with a password; read at each login
        private final Secret hostKey;      // read at each login
        private final long maxBodyBytes;
        private final String flow;
        private final PathMatcher glob;
        private final Duration interval;
        private final long maxFilesPerPoll;
        private final Duration minAge;
        private final String moveTo;       // null: delete after success
        private final String errorDir;     // null: a final refusal leaves the file and skips it
        private final Duration connectTimeout;
        private final SshClient client;
        private final Set<String> refused = ConcurrentHashMap.newKeySet();
        private final Promise<Void> stopped = Promise.promise();
        private final Promise<Void> broken = Promise.promise();
        // the round's thread only, and close() once the last round ended (ordered by the monitor)
        private ClientSession session;
        private SftpClient sftp;
        private String polled;             // the directory listed: dir, or the session's login directory
        // guarded by this
        private Timer.Cancellable next;
        private boolean roundInFlight;
        private boolean stopping;

        SftpConsumer(Inbound in, Function<Envelope, Future<Ack>> handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
            Connection c = connection(in);
            Map<String, Object> o = in.settings();
            this.name = c.key();
            this.host = required(c, "host");
            this.port = (int) number(c.values(), "port", DEFAULT_PORT);
            this.password = c.secret("password").orElse(null);
            boolean credentials = password != null && password.kind() == Secret.Kind.CREDENTIALS;   // its kind, which is no read
            if (credentials && c.values().get("user") != null) {
                throw new IllegalArgumentException("connection '" + name + "': the setting 'user' is given by the credentials of 'password'; leave it out");
            }
            this.user = credentials ? null : required(c, "user");
            this.privateKey = c.secret("privateKey").orElse(null);
            this.hostKey = c.secret("hostKey").orElseThrow(() -> new IllegalArgumentException("connection '" + name + "': the setting 'hostKey' is required"));
            this.dir = text(o, "dir", null);
            this.maxBodyBytes = in.maxBodyBytes();
            this.flow = in.flow();
            this.glob = FileSystems.getDefault().getPathMatcher("glob:" + text(o, "glob", "*"));
            this.interval = Duration.ofMillis(number(o, "intervalMs", 10_000));
            this.maxFilesPerPoll = number(o, "maxFilesPerPoll", 100);
            this.minAge = Duration.ofMillis(number(o, "minAgeMs", 0));
            if ("move".equals(o.get("afterSuccess")) && o.get("moveTo") == null) {
                throw new IllegalArgumentException("flow '" + flow + "': the setting 'moveTo' is required when afterSuccess is move");
            }
            this.moveTo = "move".equals(o.get("afterSuccess")) ? String.valueOf(o.get("moveTo")) : null;
            this.errorDir = text(o, "errorDir", null);
            this.connectTimeout = Duration.ofMillis(number(c.values(), "connectTimeoutMs", 10_000));
            this.client = SshClient.setUpDefaultClient();
            CoreModuleProperties.NIO_WORKERS.set(client, NIO_WORKERS);
            CoreModuleProperties.IO_CONNECT_TIMEOUT.set(client, connectTimeout);
            // bounds every SFTP answer; a session idle that long (a slow handler) is closed and reopened for the delete
            CoreModuleProperties.IDLE_TIMEOUT.set(client, Duration.ofMillis(number(c.values(), "ioTimeoutMs", 30_000)));
            client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);    // never ~/.ssh/config
            client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);   // never ~/.ssh/id_*
        }

        /** Schedules round 0 and completes at once: a partner down never fails a deploy. Called once. */
        @Override
        public Future<Void> start() {
            client.start();
            synchronized (this) {
                running.put(this, Boolean.TRUE);
                next = timer.schedule(Duration.ZERO, this::fire);
            }
            return Future.succeededFuture();
        }

        /**
         * Stops: no further round, no new file; the answer in flight is awaited and its delete or move done, then
         * the session and the client close. Health no longer counts this consumer from now on.
         */
        @Override
        public Future<Void> stop() {
            boolean idle;
            synchronized (this) {
                stopping = true;
                running.remove(this);
                if (next != null) next.cancel();
                idle = !roundInFlight;
            }
            if (idle) Thread.ofVirtual().name("rail8-sftp-stop").start(this::close);
            return stopped.future();
        }

        /** Fails once a round could not connect, log in or list the directory before a stop; no round follows it. */
        @Override
        public Future<Void> broken() {
            return broken.future();
        }

        /** The timer's task: starts the round on a virtual thread of its own, never on the timer's thread. */
        private void fire() {
            synchronized (this) {
                if (stopping) return;   // fired as it was cancelled
                roundInFlight = true;
            }
            Thread.ofVirtual().name("rail8-sftp-round-" + name).start(this::round);
        }

        private void round() {
            try {
                List<Remote> files;
                try {
                    files = due();
                } catch (IOException | RuntimeException unreachable) {
                    drop();
                    boolean tell;
                    synchronized (this) {
                        tell = !stopping;   // a stop is not a break
                    }
                    if (tell) broken.tryFail(unreachable);   // the node stops this consumer and starts a fresh one after its delay
                    return;
                }
                for (Remote file : files) {
                    synchronized (this) {
                        if (stopping) break;   // stop takes no new file
                    }
                    if (!take(file)) break;
                }
            } catch (IOException | RuntimeException failed) {
                drop();   // the file stays; the next round opens a new session
            } finally {
                ended();
            }
        }

        /** The round ended: the next is scheduled {@code intervalMs} later, unless the consumer stops or broke. */
        private void ended() {
            boolean close;
            synchronized (this) {
                roundInFlight = false;
                close = stopping;
                if (!stopping && !broken.future().isComplete()) next = timer.schedule(interval, this::fire);
            }
            if (close) close();
        }

        private void close() {
            try {
                drop();
                client.stop();
            } finally {
                stopped.tryComplete();
            }
        }

        /** The files this round takes, oldest first; a failure to connect or list turns health down while this consumer is not stopped. */
        private List<Remote> due() throws IOException {
            List<Remote> listed = new ArrayList<>();
            try {
                SftpClient remote = sftp();
                String where = polled;
                for (SftpClient.DirEntry entry : remote.readDir(where)) {
                    SftpClient.Attributes a = entry.getAttributes();
                    listed.add(new Remote(entry.getFilename(), join(where, entry.getFilename()), a.getSize(),
                            a.isRegularFile() ? a.getModifyTime().toInstant() : null));
                }
                running.replace(this, Boolean.TRUE);
            } catch (IOException | RuntimeException failed) {
                running.replace(this, Boolean.FALSE);
                throw failed;
            }
            Instant oldEnough = clock.instant().minus(minAge);
            return listed.stream()
                    .filter(f -> f.mtime() != null && glob.matches(Path.of(f.name())) && !f.mtime().isAfter(oldEnough) && !refused.contains(f.refusedAs()))
                    .sorted(Comparator.comparing(Remote::mtime).thenComparing(Remote::name))
                    .limit(maxFilesPerPoll)
                    .toList();
        }

        /** Takes one file; whether the round goes on. */
        private boolean take(Remote file) throws IOException {
            if (file.size() > maxBodyBytes) return refuse(file, ids.next());   // never read
            String id = ids.next();
            SftpClient remote = sftp();
            Download download = new Download(() -> remote.read(file.path()));
            Future<Body> written = blobs.write(BlobKey.body(id, 0), download, maxBodyBytes);
            written.otherwiseEmpty().await();
            download.settle(written.failed());
            if (written.failed()) {
                if (written.cause() instanceof BodyTooLargeException) return refuse(file, id);   // grew past the limit while read
                throw new IOException("reading " + file.path() + " failed", written.cause());
            }
            Envelope in = Envelope.ingress(id, flow, Headers.empty(), written.result())
                    .with(FILE, file.name())
                    .with(PATH, file.path())
                    .with(MTIME, file.mtime().toString());
            return switch (answer(in)) {
                case Ack.Accepted accepted -> done(file, id);
                case Ack.Reply reply -> done(file, id);
                case Ack.Nack nack when nack.retryable() -> false;   // the flow is full: the poller pauses until the next round
                case Ack.Nack nack -> refuse(file, id);
                case null -> false;   // a failed answer, a throw or null: the file stays, the round ends
            };
        }

        /**
         * Calls the handler on a virtual thread of its own and waits for its answer; null when the answer failed,
         * the handler threw or answered null ({@link Future#future} catches either).
         */
        private Ack answer(Envelope in) {
            Promise<Ack> answer = Promise.promise();
            Thread.ofVirtual().name("rail8-sftp-" + handled.incrementAndGet())
                    .start(() -> Future.<Ack>future(p -> handler.apply(in).onComplete(p)).onComplete(answer));
            return answer.future().otherwiseEmpty().await();
        }

        /** After the handler's Accepted or Reply: delete, or move into {@code moveTo}. */
        private boolean done(Remote file, String id) throws IOException {
            if (moveTo == null) {
                try {
                    sftp().remove(file.path());
                } catch (SftpException e) {
                    if (!noSuchFile(e)) throw e;   // gone already (another node's delete): done
                }
            } else {
                move(file, moveTo, id);
            }
            return true;
        }

        /** A final refusal: into {@code errorDir}, else left where it is and skipped from now on. */
        private boolean refuse(Remote file, String id) throws IOException {
            if (errorDir == null) refused.add(file.refusedAs());
            else move(file, errorDir, id);
            return true;
        }

        /** Renames {@code file} into the existing directory {@code to}, under {@code <name>.<id>} when its name is taken there. */
        private void move(Remote file, String to, String id) throws IOException {
            SftpClient remote = sftp();
            String target = join(to, file.name());
            try {
                remote.lstat(target);
                target = target + "." + id;   // taken
            } catch (SftpException absent) {
                // not there (or not to be known): the rename below never overwrites, so a name taken after all refuses it
            }
            try {
                remote.rename(file.path(), target);
            } catch (SftpException e) {
                if (!noSuchFile(e)) throw e;   // gone already: done
            }
        }

        /**
         * The session's SFTP channel, (re)connected and logged in when there is none or the session is closing or closed (the
         * partner went away, or it sat idle for {@code ioTimeoutMs} while a handler worked). Set and dropped together with it,
         * and with the directory listed: {@code dir}, else the login directory the server names. Each login reads each secret
         * once, anew: the user and the password of credentials, a key pair presented as the session's only key; a
         * {@code hostKey} that is no {@code SHA256:} fingerprint fails it before anything is sent.
         */
        private SftpClient sftp() throws IOException {
            if (sftp != null && session.isOpen()) return sftp;
            drop();
            String expected = hostKey.value();
            if (!FINGERPRINT.matcher(expected).matches()) {
                throw new IOException("connection '" + name + "': the setting 'hostKey' must be a SHA256: fingerprint as OpenSSH prints it");
            }
            client.setServerKeyVerifier((s, address, key) -> expected.equals(KeyUtils.getFingerPrint(key)));
            String login = user;
            String secret = null;
            if (password != null && password.kind() == Secret.Kind.CREDENTIALS) {
                Secret.Credentials credentials = password.credentials();
                login = credentials.user();
                secret = credentials.password();
            } else if (password != null) {
                secret = password.value();
            }
            KeyPair pair = privateKey != null && privateKey.kind() == Secret.Kind.KEY_PAIR ? privateKey.keyPair().pair() : null;
            String pem = privateKey != null && pair == null ? privateKey.value() : null;
            session = client.connect(login, host, port).verify(connectTimeout).getSession();
            if (secret != null) session.addPasswordIdentity(secret);
            if (pair != null) session.setKeyIdentityProvider(KeyIdentityProvider.wrapKeyPairs(pair));
            if (pem != null) {
                for (KeyPair key : keys(pem)) session.addPublicKeyIdentity(key);
            }
            session.auth().verify(connectTimeout);   // a host key other than hostKey fails here, before any credential is sent
            sftp = SftpClientFactory.instance().createSftpClient(session);
            polled = dir != null ? dir : sftp.canonicalPath(".");
            return sftp;
        }

        private Iterable<KeyPair> keys(String pem) throws IOException {
            try {
                return SecurityUtils.loadKeyPairIdentities(session, NamedResource.ofName("privateKey of connection '" + name + "'"),
                        new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)), FilePasswordProvider.EMPTY);
            } catch (GeneralSecurityException e) {
                throw new IOException("connection '" + name + "': privateKey cannot be read", e);
            }
        }

        /** Closes the session at once, if any: after an I/O failure, and at the stop. */
        private void drop() {
            ClientSession s = session;
            session = null;
            sftp = null;
            polled = null;
            if (s != null) s.close(true);
        }
    }
}
