package dev.rail8.components.http;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** The records the component's logger writes while it is open: each one's level, its message and its thrown failure's message. */
final class Logged implements AutoCloseable {

    private final Logger logger = Logger.getLogger(HttpComponent.class.getName());
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { records.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    Logged() {
        logger.addHandler(capture);
    }

    /** Each record as {@code LEVEL message}, followed by {@code | <thrown>} when it carries a failure. */
    List<String> lines() {
        return records.stream().map(r -> r.getLevel() + " " + r.getMessage() + (r.getThrown() == null ? "" : " | " + r.getThrown())).toList();
    }

    /** Whether a record holds {@code text} in its message or its failure. */
    boolean holds(String text) {
        return lines().stream().anyMatch(line -> line.contains(text));
    }

    @Override
    public void close() {
        logger.removeHandler(capture);
    }
}
