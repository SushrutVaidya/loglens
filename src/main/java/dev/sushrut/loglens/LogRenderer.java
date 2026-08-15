package dev.sushrut.loglens;

import java.util.Map;

/**
 * Renders a {@link LogEntry} to a single coloured line:
 *
 *     HH:mm:ss  LEVEL  [source]  message   key=val key=val
 *
 * One line per entry keeps output scannable — escaping the multi-line JSON wall
 * is the whole point. Extras trail the message, dimmed, so the eye lands on
 * time / level / message first. A matched trace id is reverse-highlighted.
 */
final class LogRenderer {
    private final Ansi ansi;
    private final String highlightRequestId; // the --trace id, so we can flag it
    private final boolean showSource;

    LogRenderer(Ansi ansi, String highlightRequestId, boolean showSource) {
        this.ansi = ansi;
        this.highlightRequestId = highlightRequestId;
        this.showSource = showSource;
    }

    String render(LogEntry e) {
        // Stack frames are indented a fixed amount rather than echoing their
        // original whitespace: prefix stripping consumes the leading tab, and
        // sources vary in how deeply they indent. Normalising keeps a trace
        // visually nested under the entry it belongs to.
        if (e.kind == LogEntry.Kind.CONTINUATION) {
            return ansi.dim("    " + e.raw.strip());
        }
        // Unparseable lines print verbatim — never reformat what we didn't parse.
        if (!e.structured()) {
            return ansi.dim(e.raw);
        }

        StringBuilder sb = new StringBuilder();

        if (e.timestamp != null) {
            sb.append(ansi.dim(shortTime(e.timestamp))).append("  ");
        }

        sb.append(levelBadge(e.level)).append("  ");

        // Only shown when the stream actually carries multiple sources —
        // a constant tag on every line of a single-pod log is pure noise.
        if (showSource && e.source != null) {
            sb.append(ansi.blue(e.source)).append("  ");
        }

        if (e.message != null) {
            sb.append(e.message);
        }

        if (e.requestId != null) {
            String tag = "req=" + e.requestId;
            sb.append("  ").append(
                e.requestId.equals(highlightRequestId) ? ansi.highlight(tag) : ansi.cyan(tag));
        }

        for (Map.Entry<String, Object> extra : e.extras.entrySet()) {
            if (extra.getValue() == null) continue;
            sb.append("  ").append(ansi.dim(extra.getKey() + "=" + extra.getValue()));
        }

        return sb.toString();
    }

    /** Fixed-width and colour-coded, so levels align and ERROR is unmissable. */
    private String levelBadge(Level level) {
        String padded = String.format("%-5s", level == Level.UNKNOWN ? "·" : level.name());
        return switch (level) {
            case FATAL, ERROR -> ansi.red(ansi.bold(padded));
            case WARN         -> ansi.yellow(padded);
            case INFO         -> ansi.green(padded);
            case DEBUG, TRACE -> ansi.dim(padded);
            case UNKNOWN      -> ansi.dim(padded);
        };
    }

    /**
     * Trims an ISO-8601 timestamp to HH:mm:ss — the date is rarely what you're
     * reading logs for. Anything that doesn't look like ISO passes through
     * untouched rather than being mangled (klog already gives HH:mm:ss).
     */
    private static String shortTime(String ts) {
        int t = ts.indexOf('T');
        if (t >= 0 && ts.length() >= t + 9) {
            return ts.substring(t + 1, t + 9);
        }
        return ts;
    }
}
