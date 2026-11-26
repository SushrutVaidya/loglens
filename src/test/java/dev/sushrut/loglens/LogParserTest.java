package dev.sushrut.loglens;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for format detection.
 *
 * Two things are being defended here. First, that each supported format yields
 * the right level and message - the visible behaviour. Second, and more
 * important, that the ordered fall-through does not mis-claim lines: a permissive
 * matcher stealing a line from a specific one is how this parser breaks, and it
 * breaks silently.
 */
class LogParserTest {

    private final LogParser parser = new LogParser(null);

    @Nested
    @DisplayName("JSON")
    class Json {

        @Test
        @DisplayName("well-known fields are lifted out, others preserved")
        void liftsKnownFields() {
            LogEntry e = parser.parse(
                "{\"timestamp\":\"2026-08-15T09:00:03Z\",\"level\":\"ERROR\","
                + "\"requestId\":\"9f2c-a1\",\"message\":\"boom\",\"service\":\"api\"}");
            assertEquals(LogEntry.Kind.JSON, e.kind);
            assertEquals(Level.ERROR, e.level);
            assertEquals("boom", e.message);
            assertEquals("9f2c-a1", e.requestId);
            assertEquals("api", e.extras.get("service"));
        }

        @Test
        @DisplayName("alternate field spellings are understood")
        void alternateSpellings() {
            LogEntry e = parser.parse(
                "{\"ts\":\"2026-08-15T09:00:04Z\",\"severity\":\"SEVERE\","
                + "\"msg\":\"disk full\",\"trace_id\":\"abc\"}");
            assertEquals(Level.ERROR, e.level);
            assertEquals("disk full", e.message);
            assertEquals("abc", e.requestId);
        }

        @Test
        @DisplayName("malformed JSON falls through rather than throwing")
        void malformedJsonFallsThrough() {
            LogEntry e = parser.parse("{\"level\":\"ERROR\", broken");
            assertEquals(LogEntry.Kind.PLAIN, e.kind);
        }
    }

    @Nested
    @DisplayName("Airflow")
    class Airflow {

        @Test
        @DisplayName("scheduler format, no {file:line}")
        void schedulerFormat() {
            LogEntry e = parser.parse(
                "[2026-08-15T09:00:49.306661Z +0000] INFO - Kubernetes watch timed out");
            assertEquals(LogEntry.Kind.AIRFLOW, e.kind);
            assertEquals(Level.INFO, e.level);
            assertEquals("Kubernetes watch timed out", e.message);
        }

        @Test
        @DisplayName("task format carries {file:line} as a field")
        void taskFormat() {
            LogEntry e = parser.parse(
                "[2026-08-15T09:00:49.306661Z +0000] {taskinstance.py:1234} WARNING - retry 2");
            assertEquals(Level.WARN, e.level);
            assertEquals("retry 2", e.message);
            assertEquals("taskinstance.py:1234", e.extras.get("at"));
        }

        /**
         * Regression: the kubectl --prefix pattern ^\[([^\]]+)]\s+ matched
         * Airflow's leading timestamp bracket and treated the timestamp as a pod
         * name, so every Airflow line was mis-sourced.
         */
        @Test
        @DisplayName("the timestamp bracket is not mistaken for a pod prefix")
        void timestampIsNotAPodPrefix() {
            LogEntry e = parser.parse(
                "[2026-08-15T09:00:49.306661Z +0000] INFO - watch restarted");
            assertNull(e.source, "a timestamp must never be parsed as a source");
        }
    }

    @Nested
    @DisplayName("log4j family")
    class Log4j {

        @Test
        @DisplayName("Spark's two-digit date, with logger")
        void spark() {
            LogEntry e = parser.parse("26/08/15 09:00:49 INFO SparkContext: Running Spark 3.5.0");
            assertEquals(Level.INFO, e.level);
            assertEquals("Running Spark 3.5.0", e.message);
            assertEquals("SparkContext", e.extras.get("logger"));
        }

        @Test
        @DisplayName("Hadoop's comma millis and fully-qualified logger")
        void hadoop() {
            LogEntry e = parser.parse(
                "2026-08-15 09:00:51,306 ERROR org.apache.hadoop.hdfs.DataStreamer: broken pipe");
            assertEquals(Level.ERROR, e.level);
            assertEquals("broken pipe", e.message);
            assertEquals("org.apache.hadoop.hdfs.DataStreamer", e.extras.get("logger"));
        }

        @Test
        @DisplayName("log4j2 thread is captured separately from logger")
        void log4j2WithThread() {
            LogEntry e = parser.parse(
                "2026-08-15 09:00:53,102 INFO  [main] o.a.s.executor.Executor: running task");
            assertEquals("main", e.extras.get("thread"));
            assertEquals("o.a.s.executor.Executor", e.extras.get("logger"));
        }

        @Test
        @DisplayName("Kafka's bracketed timestamp")
        void kafkaBracketed() {
            LogEntry e = parser.parse("[2026-08-15 09:00:54,306] INFO started broker 1");
            assertEquals(Level.INFO, e.level);
            assertTrue(e.message.contains("started broker 1"));
        }

        @Test
        @DisplayName("Python logging's dash-separated shape")
        void pythonLogging() {
            LogEntry e = parser.parse(
                "2026-08-15 09:00:56,306 - airflow.models.dagbag - INFO - Filling up the DagBag");
            assertEquals(Level.INFO, e.level);
            assertEquals("Filling up the DagBag", e.message);
            assertEquals("airflow.models.dagbag", e.extras.get("logger"));
        }
    }

    @Nested
    @DisplayName("klog and logfmt")
    class KlogAndLogfmt {

        @Test
        @DisplayName("klog severity letter maps to a level")
        void klogSeverityLetter() {
            LogEntry e = parser.parse("E0815 09:00:07.001456    1 kubelet.go:1893] Failed to sync pod");
            assertEquals(LogEntry.Kind.KLOG, e.kind);
            assertEquals(Level.ERROR, e.level);
            assertEquals("Failed to sync pod", e.message);
        }

        @Test
        @DisplayName("logfmt key/value pairs become fields")
        void logfmt() {
            LogEntry e = parser.parse(
                "level=warn ts=2026-08-15T09:00:05Z msg=\"retry budget exhausted\" service=ingress");
            assertEquals(LogEntry.Kind.LOGFMT, e.kind);
            assertEquals(Level.WARN, e.level);
            assertEquals("retry budget exhausted", e.message);
            assertEquals("ingress", e.extras.get("service"));
        }

        /**
         * The logfmt guard: prose containing an equals sign must not be claimed as
         * structured. Without the "line must open with key=" and coverage checks,
         * ordinary sentences were misclassified.
         */
        @Test
        @DisplayName("prose containing '=' is not misread as logfmt")
        void proseIsNotLogfmt() {
            LogEntry e = parser.parse("the connection = refused by upstream host and we gave up");
            assertEquals(LogEntry.Kind.PLAIN, e.kind);
        }
    }

    @Nested
    @DisplayName("prefixes")
    class Prefixes {

        @Test
        @DisplayName("kubectl --prefix yields the pod as source")
        void kubectlPrefix() {
            LogEntry e = parser.parse(
                "[pod/backend-7d9f-x4k2/app] {\"level\":\"INFO\",\"message\":\"hi\"}");
            assertEquals("backend-7d9f-x4k2", e.source);
            assertEquals("hi", e.message);
        }

        @Test
        @DisplayName("docker compose prefix yields the service as source")
        void composePrefix() {
            LogEntry e = parser.parse(
                "backend-1  | {\"level\":\"INFO\",\"message\":\"hi\"}");
            assertEquals("backend-1", e.source);
            assertEquals("hi", e.message);
        }

        @Test
        @DisplayName("prose containing a pipe is not treated as prefixed")
        void pipeInProseIsNotAPrefix() {
            LogEntry e = parser.parse("choose a | b to continue");
            assertEquals(LogEntry.Kind.PLAIN, e.kind);
            assertNull(e.source);
        }
    }

    @Nested
    @DisplayName("continuations")
    class Continuations {

        @Test
        @DisplayName("an indented stack frame is a continuation")
        void indentedFrame() {
            LogEntry e = parser.parse("\tat com.foo.Client.connect(Client.java:42)");
            assertEquals(LogEntry.Kind.CONTINUATION, e.kind);
        }

        @Test
        @DisplayName("Caused by is a continuation")
        void causedBy() {
            assertEquals(LogEntry.Kind.CONTINUATION,
                parser.parse("Caused by: java.net.SocketException").kind);
        }

        @Test
        @DisplayName("... N more is a continuation")
        void nMore() {
            assertEquals(LogEntry.Kind.CONTINUATION, parser.parse("\t... 24 more").kind);
        }

        @Test
        @DisplayName("an indented JSON object is a real entry, not a continuation")
        void indentedJsonIsAnEntry() {
            LogEntry e = parser.parse("  {\"level\":\"INFO\",\"message\":\"indented\"}");
            assertEquals(LogEntry.Kind.JSON, e.kind);
        }

        @Test
        @DisplayName("a compose-wrapped frame is still a continuation")
        void prefixedFrame() {
            LogEntry e = parser.parse("backend-1  | \tat com.foo.Client.connect(Client.java:42)");
            assertEquals(LogEntry.Kind.CONTINUATION, e.kind);
        }
    }

    @Nested
    @DisplayName("fall-through")
    class FallThrough {

        @Test
        @DisplayName("a line with neither timestamp nor level stays verbatim")
        void plainStaysPlain() {
            String raw = "Running /usr/local/airflow_interceptor/start_airflow scheduler";
            LogEntry e = parser.parse(raw);
            assertEquals(LogEntry.Kind.PLAIN, e.kind);
            assertEquals(raw, e.raw);
        }

        @Test
        @DisplayName("an empty line does not throw")
        void emptyLine() {
            assertNotNull(parser.parse(""));
        }

        @Test
        @DisplayName("--trace-field overrides correlation detection")
        void traceFieldOverride() {
            LogParser custom = new LogParser("myCorrelation");
            LogEntry e = custom.parse(
                "{\"level\":\"INFO\",\"message\":\"x\",\"myCorrelation\":\"zz\",\"requestId\":\"ignored\"}");
            assertEquals("zz", e.requestId);
            // The default field must remain an ordinary field when overridden.
            assertEquals("ignored", e.extras.get("requestId"));
        }
    }

    @Nested
    @DisplayName("syslog / journald")
    class Syslog {

        @Test
        @DisplayName("journalctl short-iso around a logfmt payload: payload level and fields win")
        void shortIsoLogfmtPayload() {
            LogEntry e = parser.parse("2026-11-24T10:22:01+0530 omnissiah dockerd[812]: "
                + "time=\"2026-11-24T10:22:01.123+05:30\" level=error msg=\"resolver failed\" dns-server=1.1.1.1:53");
            assertEquals(LogEntry.Kind.SYSLOG, e.kind);
            assertEquals(Level.ERROR, e.level);
            assertEquals("resolver failed", e.message);
            assertEquals("dockerd", e.source);
            assertEquals("2026-11-24T10:22:01+0530", e.timestamp);
            assertEquals("1.1.1.1:53", e.extras.get("dns-server"));
            assertEquals("812", e.extras.get("pid"));
        }

        @Test
        @DisplayName("short-precise (microseconds) and classic syslog timestamps are accepted")
        void otherTimestampDialects() {
            assertEquals(LogEntry.Kind.SYSLOG,
                parser.parse("2026-11-24T10:22:01.123456+0530 h sshd[9]: Accepted publickey").kind);
            LogEntry classic = parser.parse("Nov  4 10:22:12 omnissiah systemd[1]: Started Daily apt.");
            assertEquals(LogEntry.Kind.SYSLOG, classic.kind);
            assertEquals("Nov  4 10:22:12", classic.timestamp);
            assertEquals("systemd", classic.source);
        }

        @Test
        @DisplayName("a payload with no stated level stays UNKNOWN rather than guessed")
        void noLevelStaysUnknown() {
            LogEntry e = parser.parse(
                "2026-11-24T10:22:09+0530 h systemd-resolved[640]: Failed to send hostname reply: Connection timed out");
            assertEquals(LogEntry.Kind.SYSLOG, e.kind);
            assertEquals(Level.UNKNOWN, e.level);
        }

        @Test
        @DisplayName("an explicit level marker in an unstructured payload is honoured")
        void explicitMarker() {
            assertEquals(Level.WARN, parser.parse(
                "2026-11-24T10:22:11+0530 h NetworkManager[901]: <warn>  dhcp4 (eth0): request timed out").level);
            assertEquals(Level.ERROR, parser.parse(
                "2026-11-24T10:22:05+0530 h tailscaled[1]: 2026/11/24 10:22:05 magicsock: error: no DERP").level);
        }

        @Test
        @DisplayName("a bare word in prose is not a severity")
        void proseIsNotSeverity() {
            assertEquals(Level.UNKNOWN, parser.parse(
                "2026-11-24T10:22:12+0530 h systemd[1]: Stopped Error Reporting Service.").level);
        }

        @Test
        @DisplayName("log4j lines with a T timestamp are not stolen by the syslog parser")
        void log4jNotStolen() {
            LogEntry e = parser.parse("2026-08-15T09:00:49,306 INFO SparkContext: Running Spark");
            assertEquals(LogEntry.Kind.LOG4J, e.kind);
            assertEquals(Level.INFO, e.level);
        }

        @Test
        @DisplayName("journalctl banner lines print verbatim")
        void bannerIsPlain() {
            assertEquals(LogEntry.Kind.PLAIN, parser.parse("-- Boot 1a2b3c4d5e6f --").kind);
        }
    }
}
