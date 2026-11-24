package dev.rail8.api;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * A cache of strings a step's code keeps between messages: a token fetched in an unusual way, a key derived from a
 * secret, anything the step can load again. Which keys a step sees is set by the node ({@link Services#cache()}). What is
 * kept may vanish at any time: at a restart, under the cache's limits, on another node. So a value here is always
 * one the step can load again, and never state whose loss loses a message.
 *
 * <p>Its methods block, so they run only on a virtual thread, such as the message's thread a step runs on. On any other
 * thread they throw {@link IllegalStateException}. A key is 1 to {@link #MAX_KEY_CHARS} characters with no control
 * characters; any other key is refused with an {@link IllegalArgumentException} naming it. A key is not secret: errors may
 * show it. A value is never shown. A key stands for one value wherever the step's flows use it, so a value that depends on a
 * secret has the secret's {@link Secret#reference()} in its key.
 *
 * @since 0.1
 */
public interface Cache {

    /** The longest key, in characters. */
    long MAX_KEY_CHARS = 256;
    /** The largest value, in bytes of its UTF-8 form. */
    long MAX_VALUE_BYTES = 65_536;
    /** The longest time a value is kept; a longer ttl is cut to this. */
    Duration MAX_TTL = Duration.ofHours(24);

    /**
     * The value kept under {@code key}; on a miss, the value {@code loader} loads, kept for its entry's ttl. A value is
     * kept until its ttl has passed on the node's clock, or less.
     *
     * <p>On one node a key is loaded once at a time. A caller that asks for a key while another caller's load of it is
     * under way waits for that load and gets its value. A loader that throws keeps nothing: the caller whose loader ran gets
     * the exception as thrown (a checked one too, undeclared, as {@code Future.await()} does), and each caller that waited
     * gets an {@link IllegalStateException} naming the key, with that exception as its cause. A loader that returns
     * {@code null}, or asks for its own key, fails the same way with an {@link IllegalStateException}.
     *
     * <p>Where the nodes share the cache, a key is also loaded once at a time across them while the loading node keeps its
     * claim on the key; a node that stalls past its claim, or a failover of the shared cache, can let a second node load it,
     * and only the load that still holds the claim is kept. A caller whose key another node is loading waits for that load,
     * for a bounded time, and then fails with an {@link IllegalStateException} naming the key. When that load fails, the caller gets an {@link IllegalStateException} naming the key, without a cause; when it
     * keeps nothing, is removed, or its node stops, the caller loads the key itself.
     *
     * @throws IllegalArgumentException naming the key when it is not a key
     * @throws IllegalStateException    off a virtual thread, and as above
     */
    String get(String key, Loader loader);

    /**
     * Forgets {@code key}: a later {@link #get} loads it again, and a load already under way is not kept. Best effort: a
     * cache that cannot be reached logs it and returns, and the value then ages out on its ttl. For a value seen to fail, use
     * {@link #remove(String, String)}: this method also drops a newer value and a load under way.
     *
     * @throws IllegalArgumentException naming the key when it is not a key
     * @throws IllegalStateException    off a virtual thread
     */
    void remove(String key);

    /**
     * Forgets {@code key} only while the cache keeps {@code value} under it: a value another caller kept since stays, and a load
     * under way is left alone and keeps its value. It is for a value seen to fail, such as a token a partner refused: pass it
     * as {@link #get} returned it, then call {@code get} again. When many callers saw one value fail, the first forgets it and
     * the others find the newer value or its load, so the key is loaded once. The value is compared whole; one too long to be
     * kept matches nothing. Best effort, as {@link #remove(String)}.
     *
     * <pre>{@code
     * String token = cache.get(key, this::fetchToken);
     * Response answer = partner.call(token);
     * if (answer.status() == 401) {
     *     cache.remove(key, token);
     *     answer = partner.call(cache.get(key, this::fetchToken));
     * }
     * }</pre>
     *
     * @throws IllegalArgumentException naming the key when it is not a key; the value is not shown
     * @throws IllegalStateException    off a virtual thread
     * @throws NullPointerException     when {@code value} is null
     * @since 0.3
     */
    void remove(String key, String value);

    /**
     * Loads the value of a key that is not kept. It runs on the thread of the caller that asked, and may block.
     *
     * @since 0.1
     */
    @FunctionalInterface
    interface Loader {
        Entry load() throws Exception;
    }

    /**
     * A value and how long to keep it. A ttl of zero or less hands the value to the callers waiting for it and keeps
     * nothing; a ttl above {@link #MAX_TTL} is cut to it.
     *
     * @throws IllegalArgumentException when the value is longer than {@link #MAX_VALUE_BYTES} in UTF-8; the value is not shown
     * @since 0.1
     */
    record Entry(String value, Duration ttl) {
        public Entry {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(ttl, "ttl");
            long bytes = value.length() > MAX_VALUE_BYTES ? value.length() : value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("a cached value is at most " + MAX_VALUE_BYTES + " bytes in UTF-8, this one is at least " + bytes);
            }
            if (ttl.compareTo(MAX_TTL) > 0) ttl = MAX_TTL;
        }

        /** {@code value}'s length and the ttl, never the value. */
        @Override
        public String toString() {
            return "Entry[" + value.length() + " chars, ttl " + ttl + "]";
        }
    }
}
