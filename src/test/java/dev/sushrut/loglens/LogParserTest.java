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

    @Nested
    @DisplayName("journalctl -o json")
    class JournaldJson {

        @Test
        @DisplayName("maps syslog priority to level and extracts fields")
        void mapsPriorityAndFields() {
            // PRIORITY=3 (err), MESSAGE, SYSLOG_IDENTIFIER, __REALTIME_TIMESTAMP (micros since epoch)
            String json = "{\"MESSAGE\":\"Failed to connect\",\"PRIORITY\":\"3\",\"SYSLOG_IDENTIFIER\":\"sshd\","
                + "\"__REALTIME_TIMESTAMP\":\"1732447321123456\",\"_HOSTNAME\":\"host\",\"_PID\":\"1234\"}";
            LogEntry e = parser.parse(json);
            assertEquals(LogEntry.Kind.JSON, e.kind);
            assertEquals(Level.ERROR, e.level);
            assertEquals("Failed to connect", e.message);
            assertEquals("sshd", e.source);
            assertNotNull(e.timestamp); // converted from micros
            // _PID is kept (explicitly allowed), _UID is dropped (starts with _)
            assertEquals("1234", e.extras.get("_PID"));
            assertNull(e.extras.get("_UID"));
            // Journal fields removed from extras
            assertNull(e.extras.get("MESSAGE"));
            assertNull(e.extras.get("PRIORITY"));
            assertNull(e.extras.get("SYSLOG_IDENTIFIER"));
        }

        @Test
        @DisplayName("all syslog priority levels map correctly")
        void allPriorityLevels() {
            assertEquals(Level.FATAL, parser.parse("{\"MESSAGE\":\"x\",\"PRIORITY\":\"0\"}").level); // emerg
            assertEquals(Level.FATAL, parser.parse("{\"MESSAGE\":\"x\",\"PRIORITY\":\"1\"}").level); // alert
            assertEquals(Level.FATAL, parser.parse("{\"MESSAGE\":\"x\",\"PRIORITY\":\"2\"}").level); // crit
            assertEquals(Level.ERROR, parser.parse("{\"MESSAGE\":\"x\",\"PRIORITY\":\"3\"}").level); // err
            assertEquals(Level.WARN, parser.parse("{\"MESSAGE\":\"x\",\"PRIORITY\":\"4\"}").level);  // warning
            assertEquals(Level.INFO, parser.parse("{\"MESSAGE\":\"x\",\"PRIORITY\":\"5\"}").level);  // notice
            assertEquals(Level.INFO, parser.parse("{\"MESSAGE\":\"x\",\"PRIORITY\":\"6\"}").level);  // info
            assertEquals(Level.DEBUG, parser.parse("{\"MESSAGE\":\"x\",\"PRIORITY\":\"7\"}").level); // debug
        }

        @Test
        @DisplayName("unknown priority falls back to Level.from")
        void unknownPriorityFallback() {
            assertEquals(Level.UNKNOWN, parser.parse("{\"MESSAGE\":\"x\",\"PRIORITY\":\"99\"}").level);
        }

        @Test
        @DisplayName("non-journal JSON without MESSAGE/PRIORITY falls through to generic JSON")
        void nonJournalJsonFallsThrough() {
            LogEntry e = parser.parse("{\"level\":\"INFO\",\"message\":\"hello\",\"service\":\"api\"}");
            assertEquals(LogEntry.Kind.JSON, e.kind);
            assertEquals(Level.INFO, e.level);
            assertEquals("hello", e.message);
            assertEquals("api", e.extras.get("service"));
        }

        @Test
        @DisplayName("requestId detection works in journal JSON")
        void requestIdDetection() {
            String json = "{\"MESSAGE\":\"request done\",\"PRIORITY\":\"6\",\"requestId\":\"abc-123\"}";
            LogEntry e = parser.parse(json);
            assertEquals("abc-123", e.requestId);
            assertNull(e.extras.get("requestId"));
        }

        @Test
        @DisplayName("drops journald internal fields, keeps _PID and _SYSTEMD_UNIT")
        void dropsInternalFields() {
            String json = "{\"MESSAGE\":\"test\",\"PRIORITY\":\"6\",\"SYSLOG_IDENTIFIER\":\"sshd\","
                + "\"__REALTIME_TIMESTAMP\":\"1732447321123456\",\"_PID\":\"1234\",\"_SYSTEMD_UNIT\":\"ssh.service\","
                + "\"__CURSOR\":\"abc\",\"_BOOT_ID\":\"xyz\",\"_MACHINE_ID\":\"mid\",\"_HOSTNAME\":\"host\","
                + "\"_TRANSPORT\":\"journal\",\"_UID\":\"0\",\"_GID\":\"0\",\"_COMM\":\"sshd\","
                + "\"_EXE\":\"/usr/bin/sshd\",\"_CMDLINE\":\"sshd -D\",\"SYSLOG_FACILITY\":\"4\","
                + "\"SYSLOG_TIMESTAMP\":\"Nov 24 10:22:01\",\"SYSLOG_RAW\":\"raw\",\"MESSAGE_ID\":\"abc123\","
                + "\"app_field\":\"keep-me\"}";
            LogEntry e = parser.parse(json);
            assertEquals(LogEntry.Kind.JSON, e.kind);
            assertEquals("test", e.message);
            assertEquals("sshd", e.source);
            // Kept fields
            assertEquals("1234", e.extras.get("_PID"));
            assertEquals("ssh.service", e.extras.get("_SYSTEMD_UNIT"));
            assertEquals("keep-me", e.extras.get("app_field"));
            // Dropped fields
            assertNull(e.extras.get("__CURSOR"));
            assertNull(e.extras.get("_BOOT_ID"));
            assertNull(e.extras.get("_MACHINE_ID"));
            assertNull(e.extras.get("_HOSTNAME"));
            assertNull(e.extras.get("_TRANSPORT"));
            assertNull(e.extras.get("_UID"));
            assertNull(e.extras.get("_GID"));
            assertNull(e.extras.get("_COMM"));
            assertNull(e.extras.get("_EXE"));
            assertNull(e.extras.get("_CMDLINE"));
            assertNull(e.extras.get("SYSLOG_FACILITY"));
            assertNull(e.extras.get("SYSLOG_TIMESTAMP"));
            assertNull(e.extras.get("SYSLOG_RAW"));
            assertNull(e.extras.get("MESSAGE_ID"));
        }

        @Test
        @DisplayName("MESSAGE as byte array decodes to UTF-8 string")
        void messageAsByteArray() {
            // [104, 105] = "hi"
            LogEntry e = parser.parse("{\"MESSAGE\":[104,105],\"PRIORITY\":\"6\"}");
            assertEquals("hi", e.message);
            assertEquals(Level.INFO, e.level);
        }

        @Test
        @DisplayName("MESSAGE as empty byte array gives placeholder")
        void messageAsEmptyByteArray() {
            LogEntry e = parser.parse("{\"MESSAGE\":[],\"PRIORITY\":\"6\"}");
            assertEquals("[binary 0 bytes]", e.message);
        }

        @Test
        @DisplayName("MESSAGE as byte array with invalid bytes still decodes valid ones")
        void messageAsByteArrayWithInvalid() {
            // [104, 256, 105] = "hi" (256 is invalid, skipped)
            LogEntry e = parser.parse("{\"MESSAGE\":[104,256,105],\"PRIORITY\":\"6\"}");
            assertEquals("hi", e.message);
        }

        @Test
        @DisplayName("MESSAGE as UTF-8 multi-byte sequence decodes correctly")
        void messageAsUtf8MultiByte() {
            // café = [99, 97, 102, 195, 169] (UTF-8: c a f é)
            LogEntry e = parser.parse("{\"MESSAGE\":[99,97,102,195,169],\"PRIORITY\":\"6\"}");
            assertEquals("café", e.message);
        }

        @Test
        @DisplayName("MESSAGE null or absent does not crash")
        void messageNullOrAbsent() {
            // MESSAGE null - treated as journal JSON with null message
            LogEntry e1 = parser.parse("{\"MESSAGE\":null,\"PRIORITY\":\"6\"}");
            assertNull(e1.message);
            assertEquals(Level.INFO, e1.level);
            assertEquals(LogEntry.Kind.JSON, e1.kind);

            // MESSAGE absent entirely - not treated as journal JSON (falls through to generic JSON)
            // Generic JSON path accepts any JSON object, so it's Kind.JSON with UNKNOWN level
            LogEntry e2 = parser.parse("{\"PRIORITY\":\"6\",\"other\":\"field\"}");
            assertEquals(LogEntry.Kind.JSON, e2.kind);
            assertEquals(Level.UNKNOWN, e2.level);
        }
    }
}
