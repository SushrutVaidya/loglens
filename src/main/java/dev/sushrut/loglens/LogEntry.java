package dev.sushrut.loglens;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One parsed log line.
 *
 * The well-known fields (timestamp, level, message, requestId) are lifted out
 * for rendering and filtering; everything else stays in {@link #extras} in
 * source order so nothing is silently dropped. {@link #raw} is retained so a
 * line that isn't JSON — or a JSON line the user filters on by exact text —
 * can still be shown verbatim.
 */
final class LogEntry {
    final boolean json;        // false => not JSON; render raw, dimmed
    final String raw;
    final String timestamp;    // as printed by the source; not reformatted blindly
    final Level level;
    final String message;
    final String requestId;    // null if no correlation field was found
    final Map<String, Object> extras;

    private LogEntry(boolean json, String raw, String timestamp, Level level,
                     String message, String requestId, Map<String, Object> extras) {
        this.json = json;
        this.raw = raw;
        this.timestamp = timestamp;
        this.level = level;
        this.message = message;
        this.requestId = requestId;
        this.extras = extras;
    }

    static LogEntry plain(String raw) {
        return new LogEntry(false, raw, null, Level.UNKNOWN, null, null, new LinkedHashMap<>());
    }

    static LogEntry structured(String raw, String timestamp, Level level, String message,
                               String requestId, Map<String, Object> extras) {
        return new LogEntry(true, raw, timestamp, level, message, requestId, extras);
    }
}
