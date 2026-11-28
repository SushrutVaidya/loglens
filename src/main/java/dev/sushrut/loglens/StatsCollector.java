package dev.sushrut.loglens;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Accumulates a triage summary over a stream.
 *
 * The premise: nobody reads 14,000 log lines during an incident. In the first
 * thirty seconds you want the *shape* of the problem — how many errors, which
 * services, which distinct failures, and when they clustered. Every other small
 * log CLI gives you nicer lines; none answers that.
 *
 * Everything here is O(1) per line and bounded in memory: counters, plus one
 * map keyed by collapsed message template. A log of any size summarises in a
 * single streaming pass.
 */
final class StatsCollector {

    /** One distinct failure mode, with how many times it occurred. */
    private static final class Pattern {
        final String template;
        String example;
        long count;
        Level worst = Level.UNKNOWN;
        long firstSeen; // lower = seen earlier

        Pattern(String template, String example, long firstSeen) {
            this.template = template;
            this.example = example;
            this.firstSeen = firstSeen;
        }
    }

    /** Service entry with first-seen tracking for deterministic tie-breaking. */
    private static final class ServiceEntry {
        final String name;
        long count;
        final long firstSeen;

        ServiceEntry(String name, long firstSeen) {
            this.name = name;
            this.count = 1;
            this.firstSeen = firstSeen;
        }
    }

    private long total;
    private long unparsed;
    private final Map<Level, Long> byLevel = new LinkedHashMap<>();
    private final Map<String, ServiceEntry> byService = new LinkedHashMap<>();
    private final Map<String, Pattern> problemPatterns = new LinkedHashMap<>();
    /** Errors per minute bucket ("10:24"), ordered, for the spike line. */
    private final TreeMap<String, Long> errorsPerMinute = new TreeMap<>();
    private String firstTimestamp;
    private String lastTimestamp;
    private long sequence = 0; // global insertion counter for first-seen ordering

    void add(LogEntry e) {
        // Continuations are part of the entry above, not separate events —
        // counting them would inflate totals by however deep the stack was.
        if (e.kind == LogEntry.Kind.CONTINUATION) return;

        total++;
        if (!e.structured()) {
            unparsed++;
            return;
        }

        byLevel.merge(e.level, 1L, Long::sum);

        String service = serviceOf(e);
        if (service != null) {
            ServiceEntry se = byService.get(service);
            if (se == null) {
                byService.put(service, new ServiceEntry(service, sequence++));
            } else {
                se.count++;
            }
        }

        if (e.timestamp != null) {
            if (firstTimestamp == null) firstTimestamp = e.timestamp;
            lastTimestamp = e.timestamp;
        }

        // Only WARN and worse are worth clustering — INFO patterns are noise
        // in a triage view, and including them buries the real findings.
        if (e.level.compareTo(Level.WARN) >= 0 && e.message != null) {
            String template = MessageTemplate.collapse(e.message);
            Pattern p = problemPatterns.computeIfAbsent(template,
                key -> new Pattern(key, e.message, sequence++));
            p.count++;
            if (e.level.compareTo(p.worst) > 0) {
                p.worst = e.level;
                p.example = e.message;
            }
        }

        if (e.level.compareTo(Level.ERROR) >= 0 && e.timestamp != null) {
            errorsPerMinute.merge(minuteOf(e.timestamp), 1L, Long::sum);
        }
    }

    /** Where the entry came from — an explicit prefix wins over a logged field. */
    private static String serviceOf(LogEntry e) {
        if (e.source != null) return e.source;
        for (String key : List.of("service", "app", "component", "logger_name", "kubernetes.container_name")) {
            Object v = e.extras.get(key);
            if (v != null) return v.toString();
        }
        return null;
    }

    /** HH:mm bucket. Uses the renderer's clock extraction so every timestamp dialect works. */
    private static String minuteOf(String timestamp) {
        String time = LogRenderer.shortTime(timestamp);
        return time.length() >= 5 ? time.substring(0, 5) : time;
    }

    boolean isEmpty() {
        return total == 0;
    }

    String render(Ansi ansi) {
        StringBuilder sb = new StringBuilder();
        String rule = "─".repeat(64);

        sb.append('\n').append(ansi.dim(rule)).append('\n');

        sb.append(ansi.bold(String.format("%,d lines", total)));
        if (firstTimestamp != null && lastTimestamp != null) {
            sb.append(ansi.dim("  ·  " + shortTime(firstTimestamp) + " → " + shortTime(lastTimestamp)));
        }
        if (unparsed > 0) {
            sb.append(ansi.dim(String.format("  ·  %,d unparsed", unparsed)));
        }
        sb.append('\n');

        // Levels, worst first — the eye should land on ERROR immediately.
        List<Level> order = List.of(Level.FATAL, Level.ERROR, Level.WARN, Level.INFO, Level.DEBUG, Level.TRACE);
        StringBuilder levels = new StringBuilder();
        for (Level level : order) {
            Long count = byLevel.get(level);
            if (count == null) continue;
            String cell = String.format("%s %,d", level.name(), count);
            levels.append(switch (level) {
                case FATAL, ERROR -> ansi.red(ansi.bold(cell));
                case WARN -> ansi.yellow(cell);
                case INFO -> ansi.green(cell);
                default -> ansi.dim(cell);
            }).append("   ");
        }
        if (levels.length() > 0) sb.append(levels).append('\n');

        if (!byService.isEmpty()) {
            sb.append('\n').append(ansi.dim("by source")).append('\n');
            byService.values().stream()
                .sorted(Comparator
                    .comparingLong((ServiceEntry se) -> se.count).reversed()
                    .thenComparingLong(se -> se.firstSeen))
                .limit(8)
                .forEach(se -> sb.append(String.format("  %-38s %s%n",
                    truncate(se.name, 38), ansi.bold(String.format("%,d", se.count)))));
        }

        if (!problemPatterns.isEmpty()) {
            sb.append('\n').append(ansi.dim("distinct problems (warn and above)")).append('\n');
            List<Pattern> ranked = new ArrayList<>(problemPatterns.values());
            ranked.sort(Comparator
                .comparingLong((Pattern p) -> p.count).reversed()
                .thenComparingInt(p -> p.worst.ordinal()).reversed() // higher ordinal = higher severity (FATAL=6, ERROR=5, WARN=4)
                .thenComparingLong(p -> p.firstSeen));
            for (Pattern p : ranked.subList(0, Math.min(8, ranked.size()))) {
                String badge = switch (p.worst) {
                    case FATAL, ERROR -> ansi.red("ERROR");
                    case WARN -> ansi.yellow("WARN ");
                    default -> ansi.dim("     ");
                };
                sb.append(String.format("  %s%s  %s%n",
                    ansi.bold(String.format("%6d× ", p.count)), badge, truncate(p.example, 78)));
            }
            if (ranked.size() > 8) {
                sb.append(ansi.dim(String.format("  … and %d more%n", ranked.size() - 8)));
            }
        }

        // A spike is the single most actionable fact in a summary: it tells you
        // where to point --trace next.
        if (!errorsPerMinute.isEmpty()) {
            Map.Entry<String, Long> peak = errorsPerMinute.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElseThrow();
            if (peak.getValue() > 1) {
                sb.append('\n').append(ansi.dim("errors peaked at "))
                  .append(ansi.bold(peak.getKey()))
                  .append(ansi.dim(String.format(" (%,d in that minute)", peak.getValue())))
                  .append('\n');
            }
        }

        sb.append(ansi.dim(rule)).append('\n');
        return sb.toString();
    }

    private static String shortTime(String ts) {
        return LogRenderer.shortTime(ts);
    }

    private static String truncate(String s, int max) {
        String oneLine = s.replace('\n', ' ').strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }
}
