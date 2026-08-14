package dev.sushrut.loglens;

import java.util.Map;

/**
 * Renders a {@link LogEntry} to a single coloured line:
 *
 *     HH:mm:ss  LEVEL  message   key=val key=val
 *
 * One line per entry keeps output scannable — the whole point is to escape the
 * multi-line JSON wall. Extras trail the message, dimmed, so the eye lands on
 * time / level / message first. A matched trace id is reverse-highlighted.
 */
final class LogRenderer {
    private final Ansi ansi;
    private final String highlightRequestId; // the --trace id, so we can flag it

    LogRenderer(Ansi ansi, String highlightRequestId) {
        this.ansi = ansi;
        this.highlightRequestId = highlightRequestId;
    }

    String render(LogEntry e) {
        // Non-JSON lines pass through dimmed rather than dropped: stack traces
        // and startup banners are context you usually want to keep.
        if (!e.json) return ansi.dim(e.raw);

        StringBuilder sb = new StringBuilder();

        if (e.timestamp != null) {
            sb.append(ansi.dim(shortTime(e.timestamp))).append("  ");
        }

        sb.append(levelBadge(e.level)).append("  ");

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

    /** Fixed-width, colour-coded so levels align and ERROR is unmissable. */
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
     * Trims an ISO-8601 timestamp down to HH:mm:ss for scanning — the date is
     * rarely what you're reading logs for. Anything that doesn't look like ISO
     * is passed through untouched rather than mangled.
     */
    private static String shortTime(String ts) {
        int t = ts.indexOf('T');
        if (t >= 0 && ts.length() >= t + 9) {
            return ts.substring(t + 1, t + 9);
        }
        return ts;
    }
}
