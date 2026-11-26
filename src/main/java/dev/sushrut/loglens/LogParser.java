package dev.sushrut.loglens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // kubectl --prefix emits "[pod/backend-abc/container] ...". No whitespace
    // allowed inside the bracket, and a leading date is rejected below: Airflow
    // opens every line with "[2026-08-15T09:00:49.306661Z +0000]", which a looser
    // pattern happily mistook for a pod name.
    private static final Pattern KUBECTL_PREFIX = Pattern.compile("^\\[([^\\]\\s]+)]\\s+(.*)$");
    private static final Pattern LOOKS_LIKE_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}");
    // docker compose emits "backend-1  | ...". The pipe is the signal; requiring
    // it avoids swallowing the first word of ordinary prose.
    private static final Pattern COMPOSE_PREFIX = Pattern.compile("^(\\S+)\\s*\\|\\s(.*)$");

    // JVM stack frames and their friends. Leading whitespace alone is also a
    // continuation signal, handled separately so indented non-"at" frames count.
    private static final Pattern CONTINUATION_TEXT = Pattern.compile(
        "^(at\\s+\\S|Caused by:|Suppressed:|\\.{3}\\s*\\d+\\s+more|"
        + "[\\w.$]+(Exception|Error|Throwable)(:|\\s|$))");

    // Airflow: "[2026-08-15T09:00:49.306661Z +0000] INFO - message" (scheduler,
    // triggerer) and "[ts] {taskinstance.py:1234} INFO - message" (task logs).
    // The {file:line} group is optional because only task logs include it.
    private static final Pattern AIRFLOW = Pattern.compile(
        "^\\[(\\d{4}-\\d{2}-\\d{2}T[\\d:.]+(?:Z|[+-]\\d{2}:?\\d{2})?)\\s*(?:[+-]\\d{4})?]\\s+"
        + "(?:\\{([^}]+)}\\s+)?([A-Z]+)\\s+-\\s?(.*)$");

    // log4j family - Spark, Hadoop, Kafka, Hive, Flink, HBase, ZooKeeper.
    // Three timestamp dialects, an optional [thread] or [context], and an
    // optional "logger:" prefix on the message.
    //   Spark   : 26/08/15 09:00:49 INFO SparkContext: msg
    //   Hadoop  : 2026-08-15 09:00:49,306 INFO org.apache.hadoop.X: msg
    //   log4j2  : 2026-08-15 09:00:49,306 INFO [main] o.a.s.Foo: msg
    private static final Pattern LOG4J = Pattern.compile(
        "^(\\d{2,4}[/-]\\d{2}[/-]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?)\\s+"
        + "([A-Z]{4,8})\\s+"
        + "(?:\\[([^\\]]{1,60})]\\s+)?"
        + "(?:([\\w.$]{2,80}):\\s)?"
        + "(.*)$");

    // Kafka wraps the timestamp in brackets: [2026-08-15 09:00:49,306] INFO msg
    private static final Pattern LOG4J_BRACKETED = Pattern.compile(
        "^\\[(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?)]\\s+"
        + "([A-Z]{4,8})\\s+(.*)$");

    // Python's logging default: ts - logger - LEVEL - message
    private static final Pattern PYTHON_LOGGING = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?)\\s+-\\s+"
        + "([\\w.]{1,60})\\s+-\\s+([A-Z]{4,8})\\s+-\\s?(.*)$");

    // Generic last resort: a timestamp-shaped token at the start, then a level
    // token somewhere in the next few fields. Deliberately imprecise - it will
    // not separate logger from thread - but it renders and filters correctly on
    // formats neither the author nor this file has seen. Tried only after every
    // specific parser has declined.
    private static final Pattern GENERIC_TS = Pattern.compile(
        "^\\[?(\\d{2,4}[/-]\\d{2}[/-]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?Z?"
        + "(?:\\s*[+-]\\d{2}:?\\d{2})?)]?\\s+(.*)$");
    private static final Pattern GENERIC_LEVEL = Pattern.compile(
        "\\b(TRACE|DEBUG|INFO|INFORMATION|NOTICE|WARN|WARNING|ERROR|SEVERE|FATAL|CRITICAL)\\b",
        Pattern.CASE_INSENSITIVE);

    // journalctl -o short / short-iso / short-precise and classic syslog:
    //   2026-11-24T10:22:01+0530 host dockerd[812]: payload
    //   Nov 24 10:22:01 host sshd[1234]: payload
    private static final Pattern SYSLOG = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?"
        + "|[A-Z][a-z]{2}\\s{1,2}\\d{1,2}\\s\\d{2}:\\d{2}:\\d{2})"
        + "\\s+(\\S+)\\s+([^\\s\\[:]+)(?:\\[(\\d+)])?:\\s?(.*)$");
    // A level a syslog payload states outright: <warn>, [ERROR], (err), or "error:".
    // Deliberately not a bare word: "Error Reporting Service" is not an error.
    private static final Pattern SYSLOG_LEVEL = Pattern.compile(
        "[<\\[(]\\s*(trace|debug|info|notice|warn|warning|err|error|fatal|crit|critical)\\s*[>\\])]"
        + "|\\b(trace|debug|info|notice|warn|warning|error|fatal|critical):",
        Pattern.CASE_INSENSITIVE);

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

        LogEntry entry = tryFormats(rest, true);

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
        if (k.matches() && !LOOKS_LIKE_DATE.matcher(k.group(1)).find()) {
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

    /**
     * The ordered format chain. Syslog/journald sits in the middle: it is a
     * wrapper whose payload is parsed by this same chain (minus itself), so it
     * must come after the formats that start with their own timestamp, and
     * before logfmt and the generic heuristic, which would otherwise claim a
     * journald line and get it wrong.
     */
    private LogEntry tryFormats(String rest, boolean allowSyslog) {
        LogEntry entry = tryJson(rest);
        if (entry == null) entry = tryAirflow(rest);
        if (entry == null) entry = tryKlog(rest);
        if (entry == null) entry = tryPythonLogging(rest);
        if (entry == null) entry = tryLog4j(rest);
        if (entry == null && allowSyslog) entry = trySyslog(rest);
        if (entry == null) entry = tryLogfmt(rest);
        // Generic heuristic runs last: it is the most permissive matcher here,
        // so anything it could wrongly claim must get first refusal above.
        if (entry == null) entry = tryGeneric(rest);
        return entry;
    }

    // ---- syslog / journald ---------------------------------------------------

    /**
     * {@code journalctl -o short|short-iso|short-precise} and classic syslog:
     * a {@code timestamp host ident[pid]:} header around an arbitrary payload.
     *
     * The payload is often itself structured (dockerd and tailscaled emit
     * logfmt), so it is run back through the format chain and its level,
     * message and fields win. The ident becomes the source, which is what makes
     * {@code --stats} "by source" and {@code --where service=sshd} work on a
     * journal. The header's timestamp is kept in preference to any inside the
     * payload: it is the one column every line in the stream has, in one shape.
     *
     * Short output drops journald's PRIORITY, so a payload that is not
     * structured gets a level only when it states one unambiguously
     * ({@code <warn>}, {@code [ERROR]}, {@code error:}). Anything else stays
     * UNKNOWN rather than guessing severity from prose.
     */
    private LogEntry trySyslog(String rest) {
        Matcher m = SYSLOG.matcher(rest.strip());
        if (!m.matches()) return null;

        String timestamp = m.group(1);
        String ident = m.group(3);
        String pid = m.group(4);
        String body = m.group(5);

        Map<String, Object> extras = new LinkedHashMap<>();
        Level level;
        String message;
        String requestId = null;

        LogEntry inner = tryFormats(body, false);
        if (inner != null) {
            extras.putAll(inner.extras);
            level = inner.level;
            message = inner.message;
            requestId = inner.requestId;
        } else {
            message = body;
            Matcher lv = SYSLOG_LEVEL.matcher(body);
            level = (lv.find() && lv.start() < 120)
                ? Level.from(lv.group(1) != null ? lv.group(1) : lv.group(2))
                : Level.UNKNOWN;
        }
        if (pid != null) extras.put("pid", pid);

        return LogEntry.structured(LogEntry.Kind.SYSLOG, rest, timestamp, level, message, requestId, extras)
            .withSource(ident);
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

        // journalctl -o json emits uppercase keys:
        //   MESSAGE, PRIORITY (syslog 0-7), SYSLOG_IDENTIFIER, _HOSTNAME,
        //   __REALTIME_TIMESTAMP (microseconds since epoch), _UID, _GID, _SYSTEMD_UNIT, etc.
        // Syslog priority differs from our Level: 3=err, 4=warning, 6=info, 7=debug.
        // We map these explicitly and preserve other fields in extras.
        if (root.has("MESSAGE") && root.has("PRIORITY")) {
            return buildJournaldJson(root, rest);
        }

        Map<String, Object> extras = new LinkedHashMap<>();
        root.properties().forEach(e -> extras.put(e.getKey(), scalarOrNode(e.getValue())));
        return build(LogEntry.Kind.JSON, rest, extras);
    }

    /**
     * Builds a LogEntry from a journalctl -o json node.
     *
     * __REALTIME_TIMESTAMP is converted with ZoneId.systemDefault(), so displayed
     * clock time follows the machine's timezone (this matches journalctl's own output).
     */
    private LogEntry buildJournaldJson(JsonNode root, String raw) {
        // Extract message (handle byte arrays, null, missing)
        String message = extractMessage(root.path("MESSAGE"));

        // Map syslog priority (0-7) to our Level
        // 0=emerg, 1=alert, 2=crit, 3=err, 4=warning, 5=notice, 6=info, 7=debug
        String priority = root.path("PRIORITY").asText("");
        Level level = switch (priority) {
            case "0", "1", "2" -> Level.FATAL;
            case "3" -> Level.ERROR;
            case "4" -> Level.WARN;
            case "5" -> Level.INFO;
            case "6" -> Level.INFO;
            case "7" -> Level.DEBUG;
            default -> Level.from(priority);
        };

        // Convert __REALTIME_TIMESTAMP (microseconds since epoch) to ISO-8601
        String timestamp = null;
        if (root.has("__REALTIME_TIMESTAMP")) {
            try {
                long micros = root.path("__REALTIME_TIMESTAMP").asLong();
                Instant instant = Instant.ofEpochMilli(micros / 1000);
                timestamp = instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (Exception ignore) {
                // fall through to no timestamp
            }
        }

        String source = root.path("SYSLOG_IDENTIFIER").asText(null);

        // Collect all fields first, then filter out journald-internal ones
        Map<String, Object> extras = new LinkedHashMap<>();
        root.properties().forEach(e -> extras.put(e.getKey(), scalarOrNode(e.getValue())));

        // Extract requestId BEFORE dropping fields
        String requestId = takeFirst(extras, requestIdKeys);

        // Drop journald-internal fields (keys starting with _ or __)
        // Keep _PID and _SYSTEMD_UNIT as they help triage
        // Also drop known noisy internal fields
        Set<String> dropKeys = new HashSet<>();
        for (String key : extras.keySet()) {
            if (key.startsWith("_") || key.startsWith("__")) {
                if (!key.equals("_PID") && !key.equals("_SYSTEMD_UNIT")) {
                    dropKeys.add(key);
                }
            }
            // Drop other known internal/noisy fields
            if (key.equals("SYSLOG_FACILITY") || key.equals("SYSLOG_TIMESTAMP") ||
                key.equals("SYSLOG_RAW") || key.equals("MESSAGE_ID")) {
                dropKeys.add(key);
            }
        }
        dropKeys.forEach(extras::remove);

        // Remove the well-known journal fields we've lifted
        extras.remove("MESSAGE");
        extras.remove("PRIORITY");
        extras.remove("SYSLOG_IDENTIFIER");
        extras.remove("_HOSTNAME");
        extras.remove("__REALTIME_TIMESTAMP");

        return LogEntry.structured(LogEntry.Kind.JSON, raw, timestamp, level, message, requestId, extras)
            .withSource(source);
    }

    /**
     * Extracts message from journalctl MESSAGE field.
     * Handles: string, byte array (decodes as UTF-8), null/empty, array of ints.
     * Returns placeholder for binary/empty, null for absent.
     */
    private static String extractMessage(JsonNode messageNode) {
        if (messageNode.isMissingNode() || messageNode.isNull()) {
            return null;
        }
        if (messageNode.isTextual()) {
            String s = messageNode.asText();
            return s.isEmpty() ? null : s;
        }
        if (messageNode.isArray()) {
            // Byte array: decode as UTF-8
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            boolean valid = false;
            for (JsonNode n : messageNode) {
                if (n.isInt()) {
                    int b = n.asInt();
                    if (b >= 0 && b <= 255) {
                        bytes.write(b);
                        valid = true;
                    }
                }
            }
            if (!valid) {
                return "[binary 0 bytes]";
            }
            byte[] byteArray = bytes.toByteArray();
            if (byteArray.length == 0) {
                return "[binary 0 bytes]";
            }
            return new String(byteArray, StandardCharsets.UTF_8);
        }
        // Fallback: toString
        String s = messageNode.toString();
        return s.isEmpty() ? null : s;
    }

    // ---- Airflow ------------------------------------------------------------

    private LogEntry tryAirflow(String rest) {
        Matcher m = AIRFLOW.matcher(rest.strip());
        if (!m.matches()) return null;

        Map<String, Object> extras = new LinkedHashMap<>();
        // Task logs identify the emitting source as {file.py:line}; keep it as a
        // field rather than leaving it embedded in the message.
        if (m.group(2) != null) extras.put("at", m.group(2));

        // Airflow appends nothing structured after the message, so the whole
        // remainder is the message.
        String message = m.group(4);
        String requestId = takeFirst(extras, requestIdKeys);

        return LogEntry.structured(LogEntry.Kind.AIRFLOW, rest, m.group(1),
            Level.from(m.group(3)), message, requestId, extras);
    }

    // ---- log4j family (Spark / Hadoop / Kafka / Flink / Hive) ---------------

    private LogEntry tryLog4j(String rest) {
        String line = rest.strip();

        Matcher b = LOG4J_BRACKETED.matcher(line);
        if (b.matches()) {
            return logEntry(rest, b.group(1), b.group(2), null, null, b.group(3));
        }

        Matcher m = LOG4J.matcher(line);
        if (!m.matches()) return null;
        // group 3 = [thread] or [context], group 4 = logger, group 5 = message
        return logEntry(rest, m.group(1), m.group(2), m.group(3), m.group(4), m.group(5));
    }

    private LogEntry tryPythonLogging(String rest) {
        Matcher m = PYTHON_LOGGING.matcher(rest.strip());
        if (!m.matches()) return null;
        return logEntry(rest, m.group(1), m.group(3), null, m.group(2), m.group(4));
    }

    /**
     * Last-resort parser: timestamp at the start plus a level token nearby.
     *
     * Only the level token is removed from the message; the rest is left intact
     * rather than guessed at, because inventing structure we cannot verify is
     * worse than showing the line as its author wrote it.
     */
    private LogEntry tryGeneric(String rest) {
        Matcher ts = GENERIC_TS.matcher(rest.strip());
        if (!ts.matches()) return null;

        String remainder = ts.group(2);
        Matcher lv = GENERIC_LEVEL.matcher(remainder);
        // Require the level in the first stretch of the line: a "WARNING" buried
        // in prose 200 chars in is part of the message, not a severity field.
        if (!lv.find() || lv.start() > 40) return null;

        String message = (remainder.substring(0, lv.start()) + remainder.substring(lv.end())).strip();
        // Tidy the separators log formats leave behind once the level is removed.
        message = message.replaceFirst("^[\\[\\]\\-:|\\s]+", "").strip();
        // "subsys: error: boom" loses its level word and would read "subsys: : boom".
        message = message.replaceAll(":\\s+:", ":");

        return logEntry(rest, ts.group(1), lv.group(1), null, null, message);
    }

    /** Shared construction for the positional (non key/value) formats. */
    private LogEntry logEntry(String raw, String timestamp, String level,
                              String thread, String logger, String message) {
        Map<String, Object> extras = new LinkedHashMap<>();
        if (thread != null && !thread.isBlank()) extras.put("thread", thread);
        if (logger != null && !logger.isBlank()) extras.put("logger", logger);
        return LogEntry.structured(LogEntry.Kind.LOG4J, raw, timestamp,
            Level.from(level), message, null, extras);
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
