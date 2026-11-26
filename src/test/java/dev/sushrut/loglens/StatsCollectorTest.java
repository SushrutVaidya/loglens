package dev.sushrut.loglens;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
