package dev.sushrut.loglens;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsCollectorTest {

    private final LogParser parser = new LogParser(null);

    @Test
    @DisplayName("error peak and time range work for timestamps without a 'T' (log4j, syslog)")
    void peakMinuteForNonIsoTimestamps() {
        StatsCollector stats = new StatsCollector();
        stats.add(parser.parse("2026-08-15 09:00:49,306 ERROR org.apache.hadoop.X: boom"));
        stats.add(parser.parse("2026-08-15 09:00:51,306 ERROR org.apache.hadoop.X: boom"));
        stats.add(parser.parse("2026-08-15 09:05:01,306 INFO org.apache.hadoop.X: fine"));

        String out = stats.render(Ansi.resolve(false));
        assertTrue(out.contains("errors peaked at 09:00"), out);
        assertTrue(out.contains("09:00:49 → 09:05:01"), out);
    }

    @Test
    @DisplayName("journald lines are grouped by their ident")
    void syslogSources() {
        StatsCollector stats = new StatsCollector();
        stats.add(parser.parse("2026-11-24T10:22:01+0530 h dockerd[1]: level=error msg=\"x\""));
        stats.add(parser.parse("2026-11-24T10:22:02+0530 h sshd[2]: Accepted publickey"));

        String out = stats.render(Ansi.resolve(false));
        assertTrue(out.contains("dockerd") && out.contains("sshd"), out);
    }

    @Test
    @DisplayName("problems with equal counts ordered by level severity then first-seen")
    void problemsTieBreakByLevelThenFirstSeen() {
        StatsCollector stats = new StatsCollector();

        // Insert in order: WARN first, then ERROR, then another ERROR
        // All have count=1. Expected order: ERROR (first ERROR), ERROR (second ERROR), WARN
        stats.add(parser.parse("2026-08-15 09:00:55,000 WARN  Watch died gracefully"));
        stats.add(parser.parse("2026-08-15 09:00:57,000 ERROR Failed to sync pod"));
        stats.add(parser.parse("2026-08-15 09:00:59,000 ERROR payment declined"));

        String out = stats.render(Ansi.resolve(false));

        // Extract the problem lines in order
        Pattern p = Pattern.compile("^\\s+(\\d+)×\\s+(ERROR|WARN)\\s+(.+)$", Pattern.MULTILINE);
        Matcher m = p.matcher(out);
        var problems = new java.util.ArrayList<String>();
        while (m.find()) {
            problems.add(m.group(2) + " " + m.group(3));
        }

        // Expected: ERROR first (higher severity), then within ERROR by first-seen,
        // then WARN
        assertEquals(3, problems.size());
        assertEquals("ERROR Failed to sync pod", problems.get(0));
        assertEquals("ERROR payment declined", problems.get(1));
        assertEquals("WARN Watch died gracefully", problems.get(2));
    }

    @Test
    @DisplayName("problems with equal counts and same level ordered by first-seen")
    void problemsTieBreakSameLevelByFirstSeen() {
        StatsCollector stats = new StatsCollector();

        // Two ERRORs with count=1, inserted in specific order
        stats.add(parser.parse("2026-08-15 09:00:57,000 ERROR payment declined"));
        stats.add(parser.parse("2026-08-15 09:00:59,000 ERROR Failed to sync pod"));

        String out = stats.render(Ansi.resolve(false));

        Pattern p = Pattern.compile("^\\s+(\\d+)×\\s+(ERROR|WARN)\\s+(.+)$", Pattern.MULTILINE);
        Matcher m = p.matcher(out);
        var problems = new java.util.ArrayList<String>();
        while (m.find()) {
            problems.add(m.group(3));
        }

        assertEquals(2, problems.size());
        // First-seen order: payment declined came first
        assertEquals("payment declined", problems.get(0));
        assertEquals("Failed to sync pod", problems.get(1));
    }

    @Test
    @DisplayName("sources with equal counts ordered by first-seen")
    void sourcesTieBreakByFirstSeen() {
        StatsCollector stats = new StatsCollector();

        // Two services with count=1, inserted in specific order using JSON (service extracted)
        stats.add(parser.parse("{\"timestamp\":\"2026-08-15T09:00:59Z\",\"level\":\"ERROR\",\"message\":\"payment declined\",\"service\":\"zeta\"}"));
        stats.add(parser.parse("{\"timestamp\":\"2026-08-15T09:00:57Z\",\"level\":\"ERROR\",\"message\":\"Failed to sync pod\",\"service\":\"alpha\"}"));

        String out = stats.render(Ansi.resolve(false));

        // Extract source lines - format: "  <name>                           <count>"
        Pattern p = Pattern.compile("^\\s{2}(\\S+)\\s+(\\d+)\\s*$", Pattern.MULTILINE);
        Matcher m = p.matcher(out);
        var sources = new java.util.ArrayList<String>();
        while (m.find()) {
            sources.add(m.group(1));
        }

        assertEquals(2, sources.size(), "output:\n" + out);
        // First-seen order: zeta came first (in the first log line added)
        assertEquals("zeta", sources.get(0));
        assertEquals("alpha", sources.get(1));
    }

    @Test
    @DisplayName("order does not depend on insertion order of unrelated keys")
    void orderIndependentOfUnrelatedKeyInsertion() {
        // Create two collectors with same data but different insertion order of
        // unrelated keys (e.g., adding INFO lines before/after)
        StatsCollector stats1 = new StatsCollector();
        stats1.add(parser.parse("2026-08-15 09:00:57,000 ERROR payment declined"));
        stats1.add(parser.parse("2026-08-15 09:00:49,000 INFO unrelated info line"));
        stats1.add(parser.parse("2026-08-15 09:00:59,000 ERROR Failed to sync pod"));

        StatsCollector stats2 = new StatsCollector();
        stats2.add(parser.parse("2026-08-15 09:00:49,000 INFO unrelated info line"));
        stats2.add(parser.parse("2026-08-15 09:00:57,000 ERROR payment declined"));
        stats2.add(parser.parse("2026-08-15 09:00:59,000 ERROR Failed to sync pod"));

        String out1 = stats1.render(Ansi.resolve(false));
        String out2 = stats2.render(Ansi.resolve(false));

        // Problem order should be identical
        Pattern p = Pattern.compile("^\\s+(\\d+)×\\s+(ERROR|WARN)\\s+(.+)$", Pattern.MULTILINE);
        var probs1 = new java.util.ArrayList<String>();
        var probs2 = new java.util.ArrayList<String>();
        for (var m : new java.util.ArrayList<Matcher>()) {
        }
        Matcher m1 = p.matcher(out1);
        Matcher m2 = p.matcher(out2);
        while (m1.find()) probs1.add(m1.group(3));
        while (m2.find()) probs2.add(m2.group(3));

        assertEquals(probs1, probs2);
    }

    @Test
    @DisplayName("problems with different counts ordered by count desc regardless of severity")
    void problemsDifferentCountsOrderedByCountDesc() {
        StatsCollector stats = new StatsCollector();

        // 4× WARN (lower severity, higher count) should come before 1× ERROR (higher severity, lower count)
        for (int i = 0; i < 4; i++) {
            stats.add(parser.parse("2026-08-15 09:00:55,000 WARN  Watch died gracefully"));
        }
        stats.add(parser.parse("2026-08-15 09:00:57,000 ERROR Fatal crash"));

        String out = stats.render(Ansi.resolve(false));

        Pattern p = Pattern.compile("^\\s+(\\d+)×\\s+(ERROR|WARN)\\s+(.+)$", Pattern.MULTILINE);
        Matcher m = p.matcher(out);
        var problems = new java.util.ArrayList<String>();
        var counts = new java.util.ArrayList<Integer>();
        while (m.find()) {
            counts.add(Integer.parseInt(m.group(1)));
            problems.add(m.group(2) + " " + m.group(3));
        }

        assertEquals(2, problems.size());
        // 4× WARN first (higher count), then 1× ERROR
        assertEquals(4, counts.get(0));
        assertEquals("WARN Watch died gracefully", problems.get(0));
        assertEquals(1, counts.get(1));
        assertEquals("ERROR Fatal crash", problems.get(1));

        // Also test 2×, 4×, 1× ordering
        StatsCollector stats2 = new StatsCollector();
        stats2.add(parser.parse("2026-08-15 09:00:57,000 ERROR one"));
        for (int i = 0; i < 2; i++) stats2.add(parser.parse("2026-08-15 09:00:58,000 ERROR two"));
        for (int i = 0; i < 4; i++) stats2.add(parser.parse("2026-08-15 09:00:59,000 WARN three"));

        String out2 = stats2.render(Ansi.resolve(false));
        Matcher m2 = p.matcher(out2);
        var problems2 = new java.util.ArrayList<String>();
        var counts2 = new java.util.ArrayList<Integer>();
        while (m2.find()) {
            counts2.add(Integer.parseInt(m2.group(1)));
            problems2.add(m2.group(3));
        }

        assertEquals(3, problems2.size());
        assertEquals(List.of(4, 2, 1), counts2);
        assertEquals("three", problems2.get(0));   // 4× WARN
        assertEquals("two", problems2.get(1));     // 2× ERROR
        assertEquals("one", problems2.get(2));     // 1× ERROR
    }

    @Test
    @DisplayName("cap keeps highest-count problems when more than 8")
    void capKeepsHighestCountProblems() {
        StatsCollector stats = new StatsCollector();

        // Add 10 distinct problems with counts 1..10 using unique messages
        for (int i = 1; i <= 10; i++) {
            for (int j = 0; j < i; j++) {
                stats.add(parser.parse("{\"timestamp\":\"2026-08-15T09:00:00Z\",\"level\":\"ERROR\",\"message\":\"problem" + i + " unique" + i + "\"}"));
            }
        }

        String out = stats.render(Ansi.resolve(false));

        Pattern p = Pattern.compile("^\\s+(\\d+)×\\s+ERROR\\s+(.+)$", Pattern.MULTILINE);
        Matcher m = p.matcher(out);
        var counts = new java.util.ArrayList<Integer>();
        while (m.find()) {
            counts.add(Integer.parseInt(m.group(1)));
        }

        // Should only show top 8 (counts 10 down to 3)
        assertEquals(8, counts.size());
        assertEquals(List.of(10, 9, 8, 7, 6, 5, 4, 3), counts);
    }
}