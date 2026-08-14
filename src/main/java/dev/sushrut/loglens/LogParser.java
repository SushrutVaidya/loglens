package dev.sushrut.loglens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a raw line into a {@link LogEntry}.
 *
 * The hard part isn't the JSON parse — it's that every logger names its fields
 * differently. Logback calls the message "message", zap calls it "msg", some
 * emit "@message". The candidate lists below map those conventions onto our
 * four well-known fields; the first present candidate wins, and the field is
 * then removed from extras so it isn't printed twice.
 *
 * The correlation-id candidates default to the ones this tool was built for
 * (X-Request-Id / requestId) plus the common tracing spellings, and can be
 * overridden with --trace-field for a log format we don't know about.
 */
final class LogParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> TS_KEYS =
        List.of("timestamp", "ts", "time", "@timestamp", "@t", "eventTime");
    private static final List<String> LEVEL_KEYS =
        List.of("level", "severity", "lvl", "levelname", "@l", "log.level");
    private static final List<String> MSG_KEYS =
        List.of("message", "msg", "@message", "text", "event");

    private final List<String> requestIdKeys;

    LogParser(String traceFieldOverride) {
        this.requestIdKeys = traceFieldOverride != null
            ? List.of(traceFieldOverride)
            : List.of("requestId", "request_id", "X-Request-Id", "x-request-id",
                      "traceId", "trace_id", "correlationId", "correlation_id");
    }

    LogEntry parse(String line) {
        String trimmed = line.strip();
        // Cheap gate before handing anything to Jackson: real log files are full
        // of blank lines and stack-trace continuations that aren't objects.
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
            return LogEntry.plain(line);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(trimmed);
        } catch (Exception parseFailed) {
            return LogEntry.plain(line);
        }
        if (root == null || !root.isObject()) {
            return LogEntry.plain(line);
        }

        Map<String, Object> extras = new LinkedHashMap<>();
        root.properties().forEach(e -> extras.put(e.getKey(), scalarOrNode(e.getValue())));

        String timestamp = takeFirst(extras, TS_KEYS);
        String levelRaw = takeFirst(extras, LEVEL_KEYS);
        String message = takeFirst(extras, MSG_KEYS);
        String requestId = takeFirst(extras, requestIdKeys);

        return LogEntry.structured(line, timestamp, Level.from(levelRaw), message, requestId, extras);
    }

    /** Removes and returns the first candidate key present with a non-null value. */
    private static String takeFirst(Map<String, Object> fields, List<String> candidates) {
        for (String key : candidates) {
            if (fields.containsKey(key)) {
                Object value = fields.remove(key);
                if (value != null) return value.toString();
            }
        }
        return null;
    }

    /** Keep scalars as their natural type; render nested objects/arrays as compact JSON. */
    private static Object scalarOrNode(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isNumber()) return node.numberValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNull()) return null;
        return node.toString();
    }
}
