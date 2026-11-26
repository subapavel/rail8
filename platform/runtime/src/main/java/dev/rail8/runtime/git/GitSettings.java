package dev.rail8.runtime.git;

import org.eclipse.jgit.lib.Repository;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a node pool's package repositories are and how they are read: the {@code RAIL8_GIT_*} settings. Which schemes an environment
 * may use is the node's configuration to check; this record holds every setting to the rules that hold in any environment. No
 * refusal repeats the base. The pool's credentials are not a setting: they are the secret store's {@code rail8.git}, read by
 * {@link GitCache} for each git operation.
 *
 * @param base {@code RAIL8_GIT_BASE}: an absolute {@code https://} URI with a host, or a {@code file://} URI, with no user
 *             information, query or fragment; the repositories' URIs are the base followed by their names, so a base whose path does
 *             not end in {@code /} is given one
 * @param path {@code RAIL8_GIT_PATH}: the package's folder in each repository, relative, its segments neither empty nor {@code .}
 *             nor {@code ..}; empty for the root
 * @param ref {@code RAIL8_GIT_REF}: a full branch or tag name ({@code refs/heads/…}, {@code refs/tags/…}), the same in every
 *            repository; when it is set, every commit a deploy fetches must be reachable from it
 * @param timeout {@code RAIL8_GIT_TIMEOUT}: the longest the fetches of one deploy, or of one package's restore, may take together,
 *                PT1S to PT10M
 * @param maxBytes {@code RAIL8_GIT_MAX_BYTES}: the largest the node's git cache may grow, 1 MiB to 64 GiB
 * @param checkEvery {@code RAIL8_GIT_CHECK_EVERY}: the least time between the end of one check of the credentials
 *                   ({@link CredentialsCheck}) and the start of the next, PT1M to PT24H
 * @param checkTimeout {@code RAIL8_GIT_CHECK_TIMEOUT}: the longest one check of the credentials may take, PT1S to PT5M
 * @since 0.1
 */
public record GitSettings(URI base, String path, Optional<String> ref, Duration timeout, long maxBytes, Duration checkEvery,
                          Duration checkTimeout) {

    /** {@code RAIL8_GIT_TIMEOUT} unset. */
    public static final Duration TIMEOUT_DEFAULT = Duration.ofSeconds(60);
    public static final Duration TIMEOUT_MIN = Duration.ofSeconds(1);
    public static final Duration TIMEOUT_MAX = Duration.ofMinutes(10);
    /** {@code RAIL8_GIT_MAX_BYTES} unset: 1 GiB. */
    public static final long MAX_BYTES_DEFAULT = 1L << 30;
    public static final long MAX_BYTES_MIN = 1L << 20;
    public static final long MAX_BYTES_MAX = 64L << 30;
    /** {@code RAIL8_GIT_CHECK_EVERY} unset. */
    public static final Duration CHECK_EVERY_DEFAULT = Duration.ofMinutes(15);
    public static final Duration CHECK_EVERY_MIN = Duration.ofMinutes(1);
    public static final Duration CHECK_EVERY_MAX = Duration.ofHours(24);
    /** {@code RAIL8_GIT_CHECK_TIMEOUT} unset. */
    public static final Duration CHECK_TIMEOUT_DEFAULT = Duration.ofSeconds(10);
    public static final Duration CHECK_TIMEOUT_MIN = Duration.ofSeconds(1);
    public static final Duration CHECK_TIMEOUT_MAX = Duration.ofMinutes(5);

    public GitSettings {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(checkEvery, "checkEvery");
        Objects.requireNonNull(checkTimeout, "checkTimeout");
        base = checked(base);
        if (!path.isEmpty()) {
            for (String segment : path.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.contains("\\")) {
                    throw new IllegalArgumentException("RAIL8_GIT_PATH must be a relative folder such as examples/demo, with no empty, '.' or '..'"
                            + " segment and no backslash, was '" + path + "'");
                }
            }
        }
        ref.ifPresent(name -> {
            if (!(name.startsWith("refs/heads/") || name.startsWith("refs/tags/")) || !Repository.isValidRefName(name)) {
                throw new IllegalArgumentException("RAIL8_GIT_REF must be a full branch or tag name such as refs/heads/release, was '" + name + "'");
            }
        });
        if (timeout.compareTo(TIMEOUT_MIN) < 0 || timeout.compareTo(TIMEOUT_MAX) > 0) {
            throw new IllegalArgumentException("RAIL8_GIT_TIMEOUT " + timeout + " must be from " + TIMEOUT_MIN + " to " + TIMEOUT_MAX);
        }
        if (maxBytes < MAX_BYTES_MIN || maxBytes > MAX_BYTES_MAX) {
            throw new IllegalArgumentException("RAIL8_GIT_MAX_BYTES " + maxBytes + " must be from " + MAX_BYTES_MIN + " to " + MAX_BYTES_MAX);
        }
        if (checkEvery.compareTo(CHECK_EVERY_MIN) < 0 || checkEvery.compareTo(CHECK_EVERY_MAX) > 0) {
            throw new IllegalArgumentException("RAIL8_GIT_CHECK_EVERY " + checkEvery + " must be from " + CHECK_EVERY_MIN + " to " + CHECK_EVERY_MAX);
        }
        if (checkTimeout.compareTo(CHECK_TIMEOUT_MIN) < 0 || checkTimeout.compareTo(CHECK_TIMEOUT_MAX) > 0) {
            throw new IllegalArgumentException("RAIL8_GIT_CHECK_TIMEOUT " + checkTimeout + " must be from " + CHECK_TIMEOUT_MIN + " to " + CHECK_TIMEOUT_MAX);
        }
    }

    /** The settings given, with the defaults of the check of the credentials: every PT15M, within PT10S. */
    public GitSettings(URI base, String path, Optional<String> ref, Duration timeout, long maxBytes) {
        this(base, path, ref, timeout, maxBytes, CHECK_EVERY_DEFAULT, CHECK_TIMEOUT_DEFAULT);
    }

    /** {@code base} with the defaults of the other settings: the root of each repository, no ref, PT60S, 1 GiB. */
    public GitSettings(URI base) {
        this(base, "", Optional.empty(), TIMEOUT_DEFAULT, MAX_BYTES_DEFAULT);
    }

    /** Whether the base is a {@code file://} URI: repositories in a folder, read with no network and no credentials. */
    public boolean local() {
        return base.getScheme().equals("file");
    }

    /** The URI of the repository {@code name}, one the name rule accepted: the base followed by the name. */
    URI repository(String name) {
        return base.resolve(name);
    }

    private static URI checked(URI base) {
        if (base.getRawUserInfo() != null) {
            throw refused("it carries user information: enter the user and the token in the secret store as rail8.git");
        }
        String scheme = Objects.toString(base.getScheme(), "");
        if (!(scheme.equals("https") && base.getHost() != null) && !scheme.equals("file")) {
            throw refused("it is neither an https:// URI with a host nor a file:// URI");
        }
        if (base.isOpaque() || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw refused("it is not hierarchical, or it has a query or a fragment");
        }
        String path = base.getRawPath().isEmpty() ? "/" : base.getRawPath();
        if (!path.endsWith("/")) path = path + "/";
        return URI.create(scheme + "://" + Objects.toString(base.getRawAuthority(), "") + path).normalize();
    }

    private static IllegalArgumentException refused(String why) {
        return new IllegalArgumentException("RAIL8_GIT_BASE is refused: " + why + " (the value is not shown)");
    }
}
