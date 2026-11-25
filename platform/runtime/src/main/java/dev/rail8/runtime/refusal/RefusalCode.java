package dev.rail8.runtime.refusal;

/**
 * The code of each refusal the admin API answers, beside its English text: {@code {error, code, values}}, or a problem's
 * {@code code} and {@code values}. A code is kebab-case and names one reason; once shipped it keeps its name and its meaning.
 * Each constant names the values its refusal carries, flat strings or numbers; a client shows its own words for a code it
 * knows, with those values, and the English text for one it does not.
 *
 * @since 0.1
 */
public enum RefusalCode {

    // --- any route

    /** 400: the request does not have the form the route takes: a parameter that does not parse, a body of the wrong shape. */
    BAD_REQUEST("bad-request"),
    /** 404: no route answers the path. */
    NO_ROUTE("no-route"),
    /** 405: the route does not take the method. */
    METHOD_NOT_ALLOWED("method-not-allowed"),
    /** 413: the body is above {@code max} bytes. */
    TOO_LARGE("too-large"),
    /** 415: the body of a POST to a plugin's service is not JSON. */
    UNSUPPORTED_MEDIA_TYPE("unsupported-media-type"),
    /** 400: the body of a POST to a plugin's service is not UTF-8. */
    NOT_UTF_8("not-utf-8"),
    /** 500: the node failed; its log says why. */
    INTERNAL_ERROR("internal-error"),
    /** 500: a plugin's service did not answer as it must; the node's log says why. */
    INVALID_REPLY("invalid-reply"),
    /** 503: the node's Postgres pool is busy. */
    POOL_BUSY("pool-busy"),
    /** 503: the node is busy, hashing passwords, running a plugin's calls or waiting for the secret store's locks; nothing was done. */
    BUSY("busy"),
    /** 503: a required row of the audit was not written, so nothing ran. */
    UNAUDITED("unaudited"),

    // --- who calls

    /** 401: the request carries no credential. */
    NO_CREDENTIAL("no-credential"),
    /** 401: the credential is not known. */
    UNKNOWN_CREDENTIAL("unknown-credential"),
    /** 401: the session ended. */
    SESSION_ENDED("session-ended"),
    /** 401: the caller's user is blocked. */
    BLOCKED("blocked"),
    /** 401: the token was revoked. */
    TOKEN_REVOKED("token-revoked"),
    /** 401: the token expired. */
    TOKEN_EXPIRED("token-expired"),
    /** 401: the name or the password of a login is wrong. */
    LOGIN_REFUSED("login-refused"),
    /** 403: the password given again is wrong. */
    WRONG_PASSWORD("wrong-password"),
    /** 429: too many attempts; the next is counted after {@code seconds}. */
    TOO_MANY_ATTEMPTS("too-many-attempts"),
    /** 403: a browser sent the request. */
    BROWSER("browser"),
    /** 403: a login, a session or a named token on a listener of plain HTTP. */
    TLS_REQUIRED("tls-required"),
    /** 403: the first password is changed before {@code action}. */
    PASSWORD_CHANGE_REQUIRED("password-change-required"),
    /** 403: {@code action} needs a person logged in, not a token. */
    SESSION_REQUIRED("session-required"),
    /** 403: {@code action} is not allowed to {@code caller}. */
    NOT_ALLOWED("not-allowed"),

    // --- users and tokens

    /** 400: only a user has a password. */
    PASSWORD_NEEDS_USER("password-needs-user"),
    /** 400: a password is changed in a session. */
    PASSWORD_NEEDS_SESSION("password-needs-session"),
    /** 400: a password has {@code min} to {@code max} characters. */
    PASSWORD_LENGTH("password-length"),
    /** 400: a password does not contain the user's name. */
    PASSWORD_CONTAINS_NAME("password-contains-name"),
    /** 400: the new password is the current one. */
    PASSWORD_UNCHANGED("password-unchanged"),
    /** 400: only a session logs out. */
    LOGOUT_NEEDS_SESSION("logout-needs-session"),
    /** 400: {@code name} is no user's name. */
    BAD_USER_NAME("bad-user-name"),
    /** 409: the installation holds {@code max} users, the most it holds. */
    USERS_FULL("users-full"),
    /** 409: the name {@code name} is taken. */
    NAME_TAKEN("name-taken"),
    /** 409: nobody blocks themselves. */
    BLOCK_SELF("block-self"),
    /** 409: a technical account has no password. */
    TECHNICAL_HAS_NO_PASSWORD("technical-has-no-password"),
    /** 400: only a person issues their own token. */
    TOKEN_NEEDS_PERSON("token-needs-person"),
    /** 400: a personal token is issued in a session. */
    TOKEN_NEEDS_SESSION("token-needs-session"),
    /** 409: the account changed while the password was checked. */
    ACCOUNT_CHANGED("account-changed"),
    /** 409: an administrator issues tokens only for a technical account; a person issues their own. */
    PERSON_ISSUES_OWN_TOKENS("person-issues-own-tokens"),
    /** 409: {@code user} is blocked. */
    USER_BLOCKED("user-blocked"),
    /** 409: {@code user} holds a live token named {@code name}. */
    TOKEN_NAME_TAKEN("token-name-taken"),
    /** 409: {@code user} holds {@code max} live tokens, the most a user holds. */
    TOKENS_FULL("tokens-full"),
    /** 400: {@code name} is no token's name. */
    BAD_TOKEN_NAME("bad-token-name"),
    /** 400: a token lives 1 to {@code ceiling} days, not {@code days}. */
    TOKEN_LIFE("token-life"),
    /** 400: only a user holds tokens. */
    TOKENS_NEED_USER("tokens-need-user"),
    /** 400: the ceiling of a token's life is 1 to {@code max} days, not {@code days}. */
    CEILING_RANGE("ceiling-range"),
    /** 404: no user {@code name}. */
    NO_USER("no-user"),
    /** 404: no token {@code id}. */
    NO_TOKEN("no-token"),
    /** 409: a technical account never holds the administrator role. */
    TECHNICAL_ADMINISTRATOR("technical-administrator"),
    /** 409: at least one unblocked person holds the administrator role. */
    LAST_ADMINISTRATOR("last-administrator"),

    // --- messages and work

    /** 404: no message {@code id} recorded. */
    NO_MESSAGE("no-message"),
    /** 404: no step {@code step} recorded for message {@code id}. */
    NO_STEP("no-step"),
    /** 404: no step {@code step} of attempt {@code attempt} recorded for message {@code id}. */
    NO_STEP_ATTEMPT("no-step-attempt"),
    /** 404: step {@code step} of message {@code id} was recorded as metadata only. */
    BODY_NOT_KEPT("body-not-kept"),
    /** 404: the bytes of step {@code step} of message {@code id} were removed by retention. */
    BODY_REMOVED("body-removed"),
    /** 410: the bytes of step {@code step} of message {@code id} are gone for good. */
    BODY_LOST("body-lost"),
    /** 400: this node does not list or count messages by time. */
    NO_MESSAGE_LIST("no-message-list"),
    /** 404: no held work item {@code seq}. */
    NO_HELD_WORK("no-held-work"),
    /** 404: no lost work item {@code seq}. */
    NO_LOST_WORK("no-lost-work"),
    /** 404: no DLQ row for message {@code id}. */
    NO_DLQ_ROW("no-dlq-row"),
    /** 404: no flow {@code flow} deployed on this node. */
    NO_FLOW("no-flow"),
    /** 409: flow {@code flow} is not deployed on this node, so message {@code id} is not restarted. */
    RESTART_FLOW_NOT_DEPLOYED("restart-flow-not-deployed"),
    /** 422: message {@code id} is not restarted: its flow {@code flow} is {@code mode}, not at-least-once. */
    RESTART_NOT_AT_LEAST_ONCE("restart-not-at-least-once"),
    /** 422: message {@code id} was not parked by its flow's worker (at step {@code step}, attempt {@code attempt}). */
    RESTART_NOT_PARKED_BY_WORKER("restart-not-parked-by-worker"),
    /** 422: message {@code id} of the keyed flow {@code flow} was parked before its key was kept. */
    RESTART_KEY_NOT_KEPT("restart-key-not-kept"),
    /** 409: message {@code id} is not restarted: its ingress body is no longer kept. */
    RESTART_BODY_GONE("restart-body-gone"),
    /** 409: message {@code id} is not restarted: the node does not admit it. */
    RESTART_NOT_ADMITTED("restart-not-admitted"),
    /** 409: message {@code id} is not restarted: its work item {@code seq} is not settled yet, or its body is lost. */
    RESTART_UNSETTLED("restart-unsettled"),
    /** 409: parked message {@code id} is not cancelled: its work item {@code seq} is not settled yet. */
    CANCEL_UNSETTLED("cancel-unsettled"),
    /** 409: message {@code id} is not cancelled: its work item {@code seq} is held, lost, or not settled yet. */
    END_UNSETTLED("end-unsettled"),
    /** 404: no queued, running or parked at-least-once message {@code id}. */
    NOT_CANCELLABLE("not-cancellable"),

    // --- secrets

    /** 404: the store holds no secret {@code name}, or no source holds it and nothing uses it. */
    NO_SECRET("no-secret"),
    /** 409: the store holds a secret {@code name}. */
    EXISTS("exists"),
    /** 409: the secret {@code name} is of kind {@code kind}, not {@code asked}: it is deleted and created again instead. */
    KIND_DIFFERS("kind-differs"),
    /** 409: a user of the secret {@code name} takes it as another kind than {@code kind}. */
    KIND_IN_USE("kind-in-use"),
    /** 409: the secret {@code name} is used, or may be; a delete names it in {@code ?confirm}. */
    IN_USE("in-use"),
    /** 409: a live node does not hold the key {@code key} this node seals under. */
    KEYS_DIFFER("keys-differ"),
    /** 409: the secret store is full: it holds the most secrets, or the most bytes of sealed values, it holds. */
    STORE_FULL("store-full"),
    /** 409: the name {@code name} is under {@code rail8.}, which belongs to the platform. */
    RESERVED("reserved"),
    /** 422: the secret's {@code field} is of the wrong form or out of its limits; its value is never echoed. */
    INVALID("invalid"),
    /** 503: the change is written, but this node could not read it back: it is in force within one tick. */
    WRITTEN_UNREAD("written-unread"),

    // --- deploys

    /** 403 or 500: the node has no {@code RAIL8_GIT_BASE}. */
    NO_GIT_BASE("no-git-base"),
    /** 403: a deploy with no origin is for dev and ci only. */
    DEPLOY_WITHOUT_ORIGIN("deploy-without-origin"),
    /** 409: package {@code package} is deployed from the repository {@code recorded}, not {@code repository}. */
    PACKAGE_OTHER_REPOSITORY("package-other-repository"),
    /** 412: the pool records {@code records} for package {@code package}, but the deploy expects {@code expect}. */
    DEPLOY_STALE("deploy-stale"),
    /** 503: this node does not run yet what the pool recorded for {@code packages}. */
    POOL_LAGGING("pool-lagging"),
    /** 503: this node is following the pool's records. */
    POOL_FOLLOWING("pool-following"),
    /** 503: the pool's lock could not be taken. */
    POOL_LOCK_FAILED("pool-lock-failed"),
    /** 409: another deploy or undeploy of the pool is running. */
    POOL_DEPLOY_RUNNING("pool-deploy-running"),
    /** 409: another deploy or undeploy is running on this node. */
    DEPLOY_RUNNING("deploy-running"),
    /** 503: the node is closing. */
    NODE_CLOSING("node-closing"),
    /** 422: the package of {@code repository} at {@code commit} cannot be read. */
    PACKAGE_UNREADABLE("package-unreadable"),
    /** 422: package {@code package} cannot be read. */
    PACKAGE_FOLDER_UNREADABLE("package-folder-unreadable"),
    /** 422: the package.yaml of {@code repository} at {@code commit} names no package name: {@code name}. */
    PACKAGE_NAME_INVALID("package-name-invalid"),
    /** 422: the files of {@code repository} at {@code commit} are refused. */
    PACKAGE_TREE_REFUSED("package-tree-refused"),
    /** 422: package {@code package} is invalid: {@code count} problems. */
    PACKAGE_INVALID("package-invalid"),
    /** 404: no package {@code package} is deployed. */
    PACKAGE_NOT_DEPLOYED("package-not-deployed"),
    /** 422: the repository, the commit or the ref is not there. */
    GIT_NOT_FOUND("git-not-found"),
    /** 422: the commit is not reachable from the pool's ref. */
    GIT_NOT_REACHABLE("git-not-reachable"),
    /** 422: the history of the commit is incomplete. */
    GIT_HISTORY_INCOMPLETE("git-history-incomplete"),
    /** 500: the git host refused the node's credentials. */
    GIT_CREDENTIALS_REFUSED("git-credentials-refused"),
    /** 500: git did not answer in time. */
    GIT_TIMED_OUT("git-timed-out"),
    /** 500: the node's git cache is full. */
    GIT_CACHE_FULL("git-cache-full"),
    /** 500: the transport to the git host failed. */
    GIT_TRANSPORT_FAILED("git-transport-failed"),
    /** 500: {@code repository} at {@code commit} holds other files than its record names. */
    CONTENT_MISMATCH("content-mismatch"),
    /** 500: what each node runs could not be read. */
    LISTINGS_UNREADABLE("listings-unreadable"),
    /** 500: flow {@code flow} did not start; nothing of the package runs. */
    FLOW_DID_NOT_START("flow-did-not-start"),
    /** 500: the deploy record was not written; nothing of the package runs. */
    DEPLOY_UNRECORDED("deploy-unrecorded"),
    /** 500: whether the deploy record was written is unknown; nothing of the package runs. */
    DEPLOY_RECORD_UNKNOWN("deploy-record-unknown"),
    /** 500 or 503: version {@code version} did not start; {@code running} runs again. */
    ROLLED_BACK_UNSTARTED("rolled-back-unstarted"),
    /** 422, 500 or 503: version {@code version} was not recorded; {@code running} runs again. */
    ROLLED_BACK_UNRECORDED("rolled-back-unrecorded");

    private final String code;

    RefusalCode(String code) {
        this.code = code;
    }

    /** The code as the API answers it, such as {@code no-message}. */
    public String code() {
        return code;
    }

    @Override
    public String toString() {
        return code;
    }
}
