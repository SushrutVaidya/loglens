package dev.sushrut.loglens;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One parsed log line, produced by {@link LogParser} regardless of source
 * format (JSON, klog, logfmt, or unparseable text).
 *
 * Well-known fields (timestamp, level, message, requestId) are lifted out for
 * rendering and filtering; everything else stays in {@link #extras} in source
 * order so nothing is dropped. {@link #source} carries the pod/container that a
 * `kubectl logs --prefix` or `docker compose` prefix identified, or null when
 * the line had no prefix. {@link #raw} is retained so a line no format could
 * parse is still shown verbatim.
 */
final class LogEntry {

    /** How the line was recognised — drives rendering and continuation handling. */
    enum Kind {
        JSON,
        KLOG,
        LOGFMT,
        AIRFLOW,
        /** log4j family: Spark, Hadoop, Kafka, Flink, plus the generic fallback. */
        LOG4J,
        /** Indented / "at ..." / "Caused by:" — belongs to the entry above it. */
        CONTINUATION,
        /** Nothing matched; render verbatim. */
        PLAIN
    }

    final Kind kind;
    final String raw;
    final String source;       // pod/container/service, or null
    final String timestamp;    // as printed by the source; not reformatted blindly
    final Level level;
    final String message;
    final String requestId;    // null if no correlation field was found
    final Map<String, Object> extras;

    private LogEntry(Kind kind, String raw, String source, String timestamp, Level level,
                     String message, String requestId, Map<String, Object> extras) {
        this.kind = kind;
        this.raw = raw;
        this.source = source;
        this.timestamp = timestamp;
        this.level = level;
        this.message = message;
        this.requestId = requestId;
        this.extras = extras;
    }

    boolean structured() {
        return kind == Kind.JSON || kind == Kind.KLOG
            || kind == Kind.LOGFMT || kind == Kind.AIRFLOW
            || kind == Kind.LOG4J;
    }

    static LogEntry plain(String raw) {
        return new LogEntry(Kind.PLAIN, raw, null, null, Level.UNKNOWN, null, null, new LinkedHashMap<>());
    }

    static LogEntry continuation(String raw) {
        return new LogEntry(Kind.CONTINUATION, raw, null, null, Level.UNKNOWN, null, null, new LinkedHashMap<>());
    }

    static LogEntry structured(Kind kind, String raw, String timestamp, Level level, String message,
                               String requestId, Map<String, Object> extras) {
        return new LogEntry(kind, raw, null, timestamp, level, message, requestId, extras);
    }

    /** Returns a copy tagged with the pod/container a prefix identified. */
    LogEntry withSource(String source) {
        if (source == null) return this;
        return new LogEntry(kind, raw, source, timestamp, level, message, requestId, extras);
    }
}
