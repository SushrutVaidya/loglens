package dev.sushrut.loglens;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * loglens — readable logs in the terminal.
 *
 * Reads JSON, logfmt or klog logs from a file or stdin and prints one scannable
 * coloured line per entry instead of a wall of braces. Built around a workflow
 * that recurs constantly when operating a service: correlate one request across
 * many log lines by its request id.
 *
 * Streaming by design — reads line by line and never holds the log in memory, so
 * the idiomatic live-follow is a pipe:
 *
 *     kubectl logs -f mypod | loglens --trace 9f2c-a1
 *
 * Exit codes: 0 normal, 1 on I/O error (matching the grep family).
 */
@Command(
    name = "loglens",
    versionProvider = VersionProvider.class,
    sortOptions = false,
    description = "Pretty-print, filter, trace and summarise structured logs."
)
public final class LogLens implements Callable<Integer> {

    @Parameters(
        arity = "0..1",
        paramLabel = "FILE",
        description = "Log file to read. Omit to read stdin (e.g. `kubectl logs -f pod | loglens`)."
    )
    private Path file;

    @Option(
        names = {"-t", "--trace"},
        paramLabel = "ID",
        description = "Show only lines whose correlation id equals ID, and highlight it."
    )
    private String trace;

    @Option(
        names = "--trace-field",
        paramLabel = "NAME",
        description = "Field holding the correlation id, if not an auto-detected one "
            + "(requestId, X-Request-Id, traceId, ...)."
    )
    private String traceField;

    @Option(
        names = {"-l", "--level"},
        paramLabel = "LEVEL",
        description = "Minimum level to show: TRACE, DEBUG, INFO, WARN, ERROR, FATAL."
    )
    private Level minLevel;

    @Option(
        names = {"-w", "--where"},
        paramLabel = "KEY=VALUE",
        description = "Keep only lines where field KEY equals VALUE. Repeatable (AND)."
    )
    private Map<String, String> where = new LinkedHashMap<>();

    @Option(
        names = {"-g", "--grep"},
        paramLabel = "TEXT",
        description = "Keep only lines whose raw text contains TEXT (case-insensitive)."
    )
    private String grep;

    @Option(
        names = {"-s", "--stats"},
        description = "Print a triage summary: counts by level and source, distinct "
            + "problems with occurrence counts, and when errors peaked."
    )
    private boolean stats;

    @Option(
        names = "--quiet",
        description = "With --stats, suppress the log lines and print only the summary."
    )
    private boolean quiet;

    @Option(names = "--color", negatable = true,
        description = "Force colour on/off (default: on; disable with --no-color or NO_COLOR).")
    private Boolean color;

    @Option(names = {"-h", "--help"}, usageHelp = true,
        description = "Show this help message and exit.")
    private boolean helpRequested;

    @Option(names = {"-v", "-V", "--version"}, versionHelp = true,
        description = "Print version information and exit.")
    private boolean versionRequested;

    @Override
    public Integer call() {
        Ansi ansi = Ansi.resolve(color);
        LogParser parser = new LogParser(traceField);
        String needle = grep == null ? null : grep.toLowerCase();
        StatsCollector collector = stats ? new StatsCollector() : null;
        boolean printLines = !(stats && quiet);

        // autoFlush so a piped `kubectl logs -f` shows lines as they arrive
        // rather than only when the buffer fills.
        PrintWriter out = new PrintWriter(
            new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);

        // Whether the most recent real entry survived filtering. Continuation
        // lines inherit that decision — see keep().
        boolean lastEntryKept = false;
        // Source tags only earn their space once a stream carries more than one.
        String firstSource = null;
        boolean multiSource = false;

        try (BufferedReader reader = openReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                LogEntry entry = parser.parse(line);

                if (entry.source != null) {
                    if (firstSource == null) firstSource = entry.source;
                    else if (!multiSource && !firstSource.equals(entry.source)) multiSource = true;
                }

                if (collector != null) collector.add(entry);

                boolean keep;
                if (entry.kind == LogEntry.Kind.CONTINUATION) {
                    // A stack frame belongs to the entry above it. Judging it on
                    // its own dropped stack traces out of --trace output, which
                    // is exactly the context you want when tracing a failure.
                    keep = lastEntryKept;
                } else {
                    keep = keep(entry, needle);
                    lastEntryKept = keep;
                }

                if (keep && printLines) {
                    out.println(new LogRenderer(ansi, trace, multiSource).render(entry));
                }
            }
        } catch (IOException io) {
            System.err.println("loglens: " + io.getMessage());
            return 1;
        }

        if (collector != null && !collector.isEmpty()) {
            out.print(collector.render(ansi));
            out.flush();
        }
        return 0;
    }

    private BufferedReader openReader() throws IOException {
        if (file != null) {
            return Files.newBufferedReader(file, StandardCharsets.UTF_8);
        }
        return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    /** All active filters AND together; a filtered-out entry is simply not printed. */
    private boolean keep(LogEntry e, String needle) {
        if (needle != null && !e.raw.toLowerCase().contains(needle)) {
            return false;
        }
        // A trace filter means "only this request", which an unstructured line
        // can never satisfy on its own.
        if (trace != null && !trace.equals(e.requestId)) {
            return false;
        }
        // Level and field filters need fields. An unparseable line is kept as
        // context only while no structured filter is active.
        if (!e.structured()) {
            return minLevel == null && where.isEmpty();
        }
        if (minLevel != null && e.level.compareTo(minLevel) < 0 && e.level != Level.UNKNOWN) {
            return false;
        }
        for (Map.Entry<String, String> w : where.entrySet()) {
            Object actual = e.extras.get(w.getKey());
            // Fall back to source so `--where service=x` also matches a
            // kubectl/compose prefix, not just a logged field.
            if (actual == null && w.getKey().equals("service") && e.source != null) {
                actual = e.source;
            }
            if (actual == null || !actual.toString().equals(w.getValue())) {
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new LogLens())
            .setCaseInsensitiveEnumValuesAllowed(true)
            .execute(args);
        System.exit(exit);
    }
}
