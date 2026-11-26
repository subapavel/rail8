package dev.rail8.runtime.config;

import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * What a connection's values send, immutable: its servers, and each secret its secret fields name with each of those
 * servers.
 *
 * @param servers the connection's server, when its form marks one, and a server for each entry of {@code allowedHosts}
 * @param pairs   every secret named, with every server
 */
record Sends(SortedSet<String> servers, SortedSet<Send> pairs) {

    Sends {
        servers = Collections.unmodifiableSortedSet(new TreeSet<>(servers));
        pairs = Collections.unmodifiableSortedSet(new TreeSet<>(pairs));
    }
}
