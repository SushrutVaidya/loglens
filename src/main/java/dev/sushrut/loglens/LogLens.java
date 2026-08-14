package dev.sushrut.loglens;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * loglens — make structured JSON logs readable.
 *
 * Reads JSON-lines logs from a file or stdin, and prints one scannable coloured
 * line per entry instead of a wall of braces. Built around a workflow the
 * author hit repeatedly running a Spring Boot service: correlate one request
 * across many log lines by its X-Request-Id.
 *
 * Streaming by design — it reads line by line and never holds the whole log in
 * memory, so the idiomatic live-follow is a pipe:
 *
 *     tail -f app.log | loglens --trace 9f2c-...
 *
 * Exit codes: 0 normal, 1 on I/O error (matches grep-family conventions).
 */
@Command(
    name = "loglens",
    mixinStandardHelpOptions = true,
    version = "loglens 0.1.0",
    sortOptions = false,
    description = "Pretty-print, filter, and trace structured JSON logs."
)
public final class LogLens implements Callable<Integer> {

    @Parameters(
        arity = "0..1",
        paramLabel = "FILE",
        description = "Log file to read. Omit to read stdin (e.g. `tail -f app.log | loglens`)."
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

    @Option(names = "--color", negatable = true, description = "Force colour on/off (default: auto).")
    private Boolean color;

    @Override
    public Integer call() {
        Ansi ansi = Ansi.resolve(color);
        LogParser parser = new LogParser(traceField);
        LogRenderer renderer = new LogRenderer(ansi, trace);
        String needle = grep == null ? null : grep.toLowerCase();

        // autoFlush=true so a piped `tail -f` shows lines as they arrive rather
        // than only when the buffer fills.
        PrintWriter out = new PrintWriter(
            new java.io.OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);

        try (BufferedReader reader = openReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                LogEntry entry = parser.parse(line);
                if (keep(entry, needle)) {
                    out.println(renderer.render(entry));
                }
            }
        } catch (IOException io) {
            System.err.println("loglens: " + io.getMessage());
            return 1;
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
        // A trace filter means "only this request". A non-JSON line has no
        // correlation id and can never match, so this drops context lines too.
        if (trace != null && !trace.equals(e.requestId)) {
            return false;
        }
        // Level and field filters only make sense on parsed lines. A non-JSON
        // line (stack trace, banner) is kept as context ONLY when no structured
        // filter is active — once you ask for a specific level or field, a line
        // with no fields can't satisfy it, so it drops, same as --trace does.
        if (!e.json) {
            return minLevel == null && where.isEmpty();
        }
        if (minLevel != null && e.level.compareTo(minLevel) < 0 && e.level != Level.UNKNOWN) {
            return false;
        }
        for (Map.Entry<String, String> w : where.entrySet()) {
            Object actual = e.extras.get(w.getKey());
            if (actual == null || !actual.toString().equals(w.getValue())) {
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) {
        // Filters are the tool's job, not a reason to error — but a bad flag or
        // unreadable file should still exit non-zero for scripts.
        int exit = new CommandLine(new LogLens())
            .setCaseInsensitiveEnumValuesAllowed(true)
            .execute(args);
        System.exit(exit);
    }
}
