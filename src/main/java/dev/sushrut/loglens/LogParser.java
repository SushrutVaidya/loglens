package dev.sushrut.loglens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a raw line into a {@link LogEntry}, whatever format it arrived in.
 *
 * Kubernetes logs are a mix: your service might emit JSON, but the controllers
 * and kubelet around it emit klog ({@code I0815 ...}), and much of the Go
 * ecosystem emits logfmt ({@code level=info msg=...}). Whatever the format,
 * `kubectl logs --prefix` (or stern, or docker compose) wraps each line in a
 * pod/container prefix. So parsing is a pipeline:
 *
 *   1. detect a continuation line (stack trace) — those belong to the entry above
 *   2. strip a recognised prefix, remembering the source pod/container
 *   3. try JSON, then klog, then logfmt on the remainder
 *   4. if nothing matches, keep the ORIGINAL line verbatim — including any
 *      prefix — so a plain sentence containing a '|' is never mangled
 */
final class LogParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> TS_KEYS =
        List.of("timestamp", "ts", "time", "@timestamp", "@t", "eventTime");
    private static final List<String> LEVEL_KEYS =
        List.of("level", "severity", "lvl", "levelname", "@l", "log.level");
    private static final List<String> MSG_KEYS =
        List.of("message", "msg", "@message", "text", "event");

    // kubectl --prefix emits "[pod/backend-abc/container] ..."
    private static final Pattern KUBECTL_PREFIX = Pattern.compile("^\\[([^\\]]+)]\\s+(.*)$");
    // docker compose emits "backend-1  | ...". The pipe is the signal; requiring
    // it avoids swallowing the first word of ordinary prose.
    private static final Pattern COMPOSE_PREFIX = Pattern.compile("^(\\S+)\\s*\\|\\s(.*)$");

    // JVM stack frames and their friends. Leading whitespace alone is also a
    // continuation signal, handled separately so indented non-"at" frames count.
    private static final Pattern CONTINUATION_TEXT = Pattern.compile(
        "^(at\\s+\\S|Caused by:|Suppressed:|\\.{3}\\s*\\d+\\s+more|"
        + "[\\w.$]+(Exception|Error|Throwable)(:|\\s|$))");

    // klog: I0815 10:22:01.101234   1 controller.go:123] message
    private static final Pattern KLOG = Pattern.compile(
        "^([IWEF])\\d{4}\\s+(\\d{2}:\\d{2}:\\d{2})(?:\\.\\d+)?\\s+\\d+\\s+\\S+:\\d+]\\s?(.*)$");

    // logfmt token: key=value | key="quoted value" | bare key
    private static final Pattern LOGFMT_TOKEN =
        Pattern.compile("([\\w.\\-]+)(?:=(\"(?:[^\"\\\\]|\\\\.)*\"|[^\\s]*))?");
    // Only treat a line as logfmt if it opens with `key=`; stops prose like
    // "error: connection = refused" being classified as structured.
    private static final Pattern LOGFMT_START = Pattern.compile("^[\\w.\\-]+=");

    private final List<String> requestIdKeys;

    LogParser(String traceFieldOverride) {
        this.requestIdKeys = traceFieldOverride != null
            ? List.of(traceFieldOverride)
            : List.of("requestId", "request_id", "X-Request-Id", "x-request-id",
                      "traceId", "trace_id", "correlationId", "correlation_id");
    }

    LogEntry parse(String line) {
        if (line.isEmpty()) return LogEntry.plain(line);

        String[] split = stripPrefix(line);
        String source = split[0];        // null when no prefix matched
        String rest = split[1];

        // Continuation check runs after prefix stripping, so compose-wrapped
        // stack frames ("backend-1 |     at com.foo...") are still recognised.
        // The stripped remainder is kept as the raw text: the prefix has been
        // positively identified, so removing it lets the frame render indented
        // under its parent entry instead of restating the pod on every line.
        if (isContinuation(rest)) {
            return LogEntry.continuation(rest).withSource(source);
        }

        LogEntry entry = tryJson(rest);
        if (entry == null) entry = tryKlog(rest);
        if (entry == null) entry = tryLogfmt(rest);

        if (entry != null) return entry.withSource(source);
        return LogEntry.plain(line);
    }

    /**
     * A continuation is either indented (the JVM convention for stack frames)
     * or opens with a recognised frame marker. Indentation alone is deliberately
     * enough — some loggers emit frames without the "at " prefix.
     */
    private static boolean isContinuation(String rest) {
        if (rest.isEmpty()) return false;
        char first = rest.charAt(0);
        if (first == '\t' || first == ' ') {
            // Indented, but an indented JSON object is still a real entry.
            return !rest.strip().startsWith("{");
        }
        return CONTINUATION_TEXT.matcher(rest).find();
    }

    /** @return [source|null, remainder]; remainder is the original line if no prefix matched. */
    private static String[] stripPrefix(String line) {
        Matcher k = KUBECTL_PREFIX.matcher(line);
        if (k.matches()) {
            // "pod/backend-abc/container" — keep the pod name, the useful part.
            String tag = k.group(1);
            String[] parts = tag.split("/");
            return new String[]{parts.length >= 2 ? parts[1] : tag, k.group(2)};
        }
        Matcher c = COMPOSE_PREFIX.matcher(line);
        if (c.matches()) {
            return new String[]{c.group(1), c.group(2)};
        }
        return new String[]{null, line};
    }

    // ---- JSON ---------------------------------------------------------------

    private LogEntry tryJson(String rest) {
        String trimmed = rest.strip();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') return null;

        JsonNode root;
        try {
            root = MAPPER.readTree(trimmed);
        } catch (Exception notJson) {
            return null;
        }
        if (root == null || !root.isObject()) return null;

        Map<String, Object> extras = new LinkedHashMap<>();
        root.properties().forEach(e -> extras.put(e.getKey(), scalarOrNode(e.getValue())));
        return build(LogEntry.Kind.JSON, rest, extras);
    }

    // ---- klog ---------------------------------------------------------------

    private LogEntry tryKlog(String rest) {
        Matcher m = KLOG.matcher(rest.strip());
        if (!m.matches()) return null;

        Level level = switch (m.group(1)) {
            case "I" -> Level.INFO;
            case "W" -> Level.WARN;
            case "E" -> Level.ERROR;
            case "F" -> Level.FATAL;
            default -> Level.UNKNOWN;
        };

        // Structured klog appends key="value" pairs after the message; peel any
        // trailing run of them into extras, leaving the human-readable part.
        Map<String, Object> extras = new LinkedHashMap<>();
        String message = splitTrailingPairs(m.group(3), extras);
        String requestId = takeFirst(extras, requestIdKeys);

        return LogEntry.structured(LogEntry.Kind.KLOG, rest, m.group(2), level, message, requestId, extras);
    }

    // ---- logfmt -------------------------------------------------------------

    private LogEntry tryLogfmt(String rest) {
        String trimmed = rest.strip();
        if (!LOGFMT_START.matcher(trimmed).find()) return null;

        Map<String, Object> extras = new LinkedHashMap<>();
        Matcher m = LOGFMT_TOKEN.matcher(trimmed);
        int covered = 0;
        while (m.find()) {
            if (m.group().isEmpty()) continue;
            extras.put(m.group(1), m.group(2) == null ? Boolean.TRUE : unquote(m.group(2)));
            covered += m.group().length();
        }
        // Require the tokens to cover most of the line, else it's prose that
        // merely happened to start with something=something.
        if (extras.isEmpty() || covered < trimmed.length() / 2) return null;

        return build(LogEntry.Kind.LOGFMT, rest, extras);
    }

    // ---- shared -------------------------------------------------------------

    /** Lift the well-known fields out of a key/value map (JSON and logfmt share this). */
    private LogEntry build(LogEntry.Kind kind, String raw, Map<String, Object> extras) {
        String timestamp = takeFirst(extras, TS_KEYS);
        String level = takeFirst(extras, LEVEL_KEYS);
        String message = takeFirst(extras, MSG_KEYS);
        String requestId = takeFirst(extras, requestIdKeys);
        return LogEntry.structured(kind, raw, timestamp, Level.from(level), message, requestId, extras);
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

    /**
     * Splits a klog message into its human part and any trailing key=value
     * pairs. klog only appends pairs at the end, so scan from the right and stop
     * at the first token that isn't one.
     */
    private static String splitTrailingPairs(String tail, Map<String, Object> out) {
        List<String> tokens = splitRespectingQuotes(tail);
        int firstPair = tokens.size();
        for (int i = tokens.size() - 1; i >= 0; i--) {
            if (tokens.get(i).matches("[\\w.\\-]+=.*")) firstPair = i;
            else break;
        }
        for (int i = firstPair; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int eq = token.indexOf('=');
            out.put(token.substring(0, eq), unquote(token.substring(eq + 1)));
        }
        return String.join(" ", tokens.subList(0, firstPair));
    }

    private static List<String> splitRespectingQuotes(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            if (c == ' ' && !inQuotes) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static Object unquote(String v) {
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            return v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return v;
    }

    private static Object scalarOrNode(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isNumber()) return node.numberValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNull()) return null;
        return node.toString();
    }
}
