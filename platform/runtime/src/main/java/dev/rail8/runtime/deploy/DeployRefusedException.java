package dev.rail8.runtime.deploy;

import dev.rail8.runtime.refusal.RefusalCode;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Why {@link Deployments#deploy} refused a package, or {@link Deployments#undeploy} an undeploy: its {@link Kind} — the admin API
 * answers each with its own status — its code, the error line and the values the line names, and for {@link Kind#INVALID} every
 * problem found, one line each, none with an absolute path; for a redeploy that did not start and rolled back ({@link Kind#FAILED}
 * or {@link Kind#BUSY}) every flow that did not start, build or start again; for {@link Kind#STALE} the state the pool records.
 *
 * @since 0.1
 */
public final class DeployRefusedException extends Exception {

    /** What kind of refusal; the admin API's status in brackets. */
    public enum Kind {
        /** A bad name, repository, commit or ref, or a folder holding anything but files and directories (400). */
        BAD_REQUEST,
        /** The package does not pass validation: every problem in {@link #problems()} (422). */
        INVALID,
        /**
         * Another deploy or undeploy runs on this node: one at a time per node, whatever the package; or the package to undeploy is
         * pinned by another package's record, or the work queue holds items of an at-least-once flow of it (409).
         */
        CONFLICT,
        /** The node is closing (503). */
        STOPPING,
        /**
         * The node's Postgres pool refused a connection (503), retryable: to the deploy record's write — otherwise as
         * {@link #FAILED} for a record not written, a first deploy's flows stopped, a redeploy rolled back — or to an undeploy's
         * work-queue question or record delete, or to a deploy's work-queue question about a flow the new version no longer runs
         * at-least-once (after the package's problems: those are its 422), and only when every failed question was the pool's: a
         * real failure of another flow's question is the node's IOException and, on an undeploy, a flow holding items its CONFLICT;
         * either refusal then changed nothing.
         */
        BUSY,
        /**
         * A flow did not start (500): every flow the deploy built was stopped again; on a redeploy the flows it replaced or removed
         * run again from the directories they ran from, or are listed FAILED.
         */
        FAILED,
        /** A deploy with no origin in a deployed environment, or a deploy on a node without {@code RAIL8_GIT_BASE} (403). */
        FORBIDDEN,
        /** The package to undeploy is not deployed on this node (404). */
        NOT_FOUND,
        /**
         * The deploy expects another state than the pool records of its package: the commit of the package's record, or
         * {@link Deployments#NONE}, is {@link #replaces()} (412). Nothing changed.
         */
        STALE
    }

    private final Kind kind;
    private final RefusalCode code;
    private final String error;
    private final Map<String, Object> values;
    private final List<String> problems;
    private final Optional<String> replaces;

    /** No stack trace is kept; suppression is on: a cleanup that fails after the refusal is added to it, never in its place. */
    public DeployRefusedException(Kind kind, RefusalCode code, String error, Map<String, Object> values, List<String> problems) {
        this(kind, code, error, values, problems, Optional.empty());
    }

    /** As the constructor above, with the state the pool records of the package ({@link #replaces()}). */
    public DeployRefusedException(Kind kind, RefusalCode code, String error, Map<String, Object> values, List<String> problems,
                                  Optional<String> replaces) {
        super(kind + ": " + error + (problems.isEmpty() ? "" : " " + problems), null, true, false);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.code = Objects.requireNonNull(code, "code");
        this.error = Objects.requireNonNull(error, "error");
        this.values = Map.copyOf(values);
        this.problems = List.copyOf(problems);
        this.replaces = Objects.requireNonNull(replaces, "replaces");
    }

    public Kind kind() { return kind; }

    /** The refusal's code. */
    public RefusalCode code() { return code; }

    /** The error line. */
    public String error() { return error; }

    /** The values the error line names, strings or numbers, by name. */
    public Map<String, Object> values() { return values; }

    /** Every problem of an {@link Kind#INVALID} package, or of a {@link Kind#FAILED} or {@link Kind#BUSY} redeploy, one line each; otherwise empty. */
    public List<String> problems() { return problems; }

    /** For {@link Kind#STALE}, the commit of the pool's record of the package, or {@link Deployments#NONE}; otherwise empty. */
    public Optional<String> replaces() { return replaces; }
}
