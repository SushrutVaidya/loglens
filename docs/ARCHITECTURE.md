# loglens — architecture

Notes on how loglens is put together: the data flow, what each class does, the
handful of algorithms that aren't obvious from the code, and the decisions that
constrain where it can go next.

Version 0.2.0, targets Java 21, two runtime dependencies (Picocli and Jackson).

## What it is

A read-only, streaming log formatter. It reads lines from stdin or a file, works
out what format each line is in, normalises it, filters, renders, and optionally
builds a triage summary at the end.

No daemon, no network, nothing written to disk, nothing to embed in the app you're
watching. It's built to live in a pipe:

```
kubectl logs -f pod | loglens --trace 9f2c-a1
```

Things it deliberately doesn't do, and why:

- **Collect or tail logs.** `kubectl logs -f`, `stern` and `tail -f` already do
  that well. Owning it would mean owning pod discovery, auth and reconnection,
  which is a different tool.
- **Store or index.** That's what Loki and Splunk are for. The whole point of
  loglens is the case where none of that is set up.
- **Run a full-screen TUI.** Output that composes with pipes was the goal; a TUI
  fights that.
- **Write back to the source.** Read-only is a hard rule.

## Data flow

One pass, one line at a time, nothing buffered.

```
                stdin / FILE
                     │
                     ▼
        ┌────────────────────────┐
        │  LogParser.parse()     │   per line, no shared state
        │                        │
        │  strip prefix      ────┼──►  source = pod / container
        │  continuation?     ────┼──►  Kind.CONTINUATION (early return)
        │  JSON, Airflow, klog,  │
        │  Python, log4j, logfmt │
        │  generic ts+level  ────┼──►  first match wins
        │  none of the above ────┼──►  Kind.PLAIN (verbatim)
        └───────────┬────────────┘
                    │  LogEntry
        ┌───────────┴────────────┐
        │                        │
        ▼                        ▼
┌───────────────┐      ┌──────────────────┐
│ LogLens.keep()│      │ StatsCollector   │   sees every entry, before
│  grep         │      │  .add()          │   filtering, so the summary
│  trace        │      │                  │   describes the whole stream
│  level        │      │  MessageTemplate │   and not just what survived
│  where        │      │   .collapse()    │
└───────┬───────┘      └────────┬─────────┘
        │ kept                  │
        ▼                       │  at EOF
┌───────────────┐               ▼
│ LogRenderer   │      ┌──────────────────┐
│  .render()    │      │ .render(Ansi)    │
└───────┬───────┘      └────────┬─────────┘
        └────────────┬───────────┘
                     ▼
                  stdout
```

Lines come out in the order they went in. The `--stats` block prints once at the
end, or on its own if you pass `--quiet`.

Note that stats runs *before* the filter. A summary that only counted the lines
surviving `--level ERROR` would be useless ("3 errors" tells you nothing about the
other 14,000 lines), so `StatsCollector.add()` is called ahead of `keep()`.

## The classes

Eight of them, each with one job.

| Class | What it does |
|---|---|
| `LogLens` | The Picocli command. Owns the read loop, the filter check, and the one bit of continuation state. |
| `LogParser` | Line to `LogEntry`: prefix stripping, format detection, field lifting. |
| `LogEntry` | The normalised line. Immutable. Its `Kind` says how it was recognised. |
| `Level` | Ordered severity enum plus tolerant parsing. |
| `MessageTemplate` | Collapses a message to a template so repeats group together. |
| `StatsCollector` | Streaming aggregation, and renders the summary. |
| `LogRenderer` | `LogEntry` to one display line. |
| `Ansi` | The single yes/no on colour, plus the wrap helpers. |

### LogEntry

Four well-known fields get lifted out; everything else is kept as-is.

```java
Kind kind;                  // JSON | KLOG | LOGFMT | AIRFLOW | LOG4J | CONTINUATION | PLAIN
String raw;                 // original text, for verbatim rendering
String source;              // pod/container from a prefix, else null
String timestamp;           // as the source printed it
Level level;
String message;
String requestId;
Map<String,Object> extras;  // LinkedHashMap, so field order is preserved
```

It's immutable, with one copy-on-write helper (`withSource`). `extras` is a
`LinkedHashMap` on purpose: the order fields appear in a log line is something the
author chose, and shuffling it makes the output harder to read than the original.

Nothing gets thrown away. Unknown fields go in `extras`, and a line no parser
understood keeps its `raw` text. A formatter that silently drops data isn't much
use mid-incident.

### Level

The enum's declared order *is* the comparison `--level` uses, so `--level WARN`
means `level.compareTo(WARN) >= 0`.

`UNKNOWN` is declared first, below `TRACE`, so a level we couldn't parse never
outranks a real one. It's also skipped by the level filter on purpose: if we
failed to read a line's severity, we shouldn't hide it behind a threshold it might
have cleared anyway.

`Level.from()` handles the spellings real loggers use (`WARNING`, `SEVERE`,
`CRIT`, syslog numbers) and returns `UNKNOWN` instead of throwing. An
unrecognised level is data, not a crash.

### Ansi

Colour is decided once at startup, in this order:

1. an explicit `--color` / `--no-color`
2. the `NO_COLOR` env var ([no-color.org](https://no-color.org))
3. otherwise, `System.console() != null`, which is null when stdout is piped or
   redirected

When colour is off, every method just returns its argument. That's what lets the
renderer call `ansi.red(...)` everywhere without ever checking whether colour is on.

## The parts that aren't obvious

### Format detection is an ordered fall-through

`LogParser.parse()` tries each format in a fixed order and takes the first that
matches:

```
JSON     first non-space char is '{', then Jackson parses it
Airflow  [ts] {file:line} LEVEL - msg
klog     I0815 10:22:01.101234  1 controller.go:123] msg
Python   ts - logger - LEVEL - msg
log4j    ts LEVEL [thread] logger: msg   (Spark/Hadoop/Kafka/Flink/…)
logfmt   line opens with key= and tokens cover most of it
generic  a timestamp then a level word somewhere near the front
PLAIN    nothing matched
```

The order isn't arbitrary. The generic matcher will grab almost anything with a
timestamp and a level word in it, so it has to run last, after every specific
parser has had its shot. If it ran earlier it would swallow lines the specific
parsers should own, and render them worse.

logfmt is the one that's easy to get wrong. A prose line like
`error: connection = refused` has an `=` in it and a naive token scan would call
it structured. Two guards stop that: the line has to *start* with `key=`, and the
matched tokens have to cover at least half the characters. Miss either and it
falls through to PLAIN and prints verbatim, which is the safe outcome.

Prefix stripping happens before any of this, and only the stripped remainder is
handed to the matchers. A `kubectl logs --prefix` wrapper like
`[pod/name/container]` yields the pod name; a `docker compose` prefix like
`svc | ...` yields the service. The compose pattern insists on a literal `|`,
otherwise it'd eat the first word of ordinary prose. If neither prefix matches,
the *original* line goes forward untouched, never a half-chewed one.

### Continuations are attached by inheritance, not lookahead

A JVM stack frame has no level and no request id, so it can't satisfy `--trace` or
`--level` on its own. Judge it independently and it gets dropped, which tears the
stack trace off the very error you're chasing. (This was a real bug in v0.1.)

Detection runs after prefix stripping, so compose-wrapped frames still register.
A line is a continuation if it's indented (unless the trimmed remainder starts
with `{`, which is an indented JSON entry) or opens with a known marker: `at `,
`Caused by:`, `Suppressed:`, `... N more`, or a `com.foo.SomeException:` line.

Attachment is a single boolean in the read loop:

```java
if (entry.kind == CONTINUATION) {
    keep = lastEntryKept;          // ride on the parent's decision
} else {
    keep = keep(entry, needle);
    lastEntryKept = keep;
}
```

The alternative would be to buffer an entry, peek at the next line, then emit. But
that makes `kubectl logs -f | loglens` lag a line behind forever, because the
pending line only flushes when the next one arrives. Inheritance keeps the stream
live; lookahead wouldn't. That constraint drove the whole design.

Indentation is normalised to four spaces at render time rather than echoed,
because the prefix regex eats the original leading tab and sources indent to
different depths anyway.

### Message templating is what makes --stats useful

Counting raw messages is pointless: 400 timeouts that differ only in IP and
duration count as 400 problems. `MessageTemplate.collapse()` swaps the varying
parts for placeholders so the repeats group:

```
Connection to 10.0.3.14:5432 timed out after 30012ms
Connection to 10.0.7.82:5432 timed out after 29984ms
        becomes
Connection to <ip>:<num> timed out after <num>ms     2×
```

Same idea as Drain/logreduce, but done as an ordered list of substitutions instead
of a parse tree. That's enough for single-line messages and costs nothing per line.

The order of the rules matters. Specific patterns have to consume their text
before general ones get to it:

```
uuid → timestamp → ip → email → url → @hash → pod-suffix
     → path → long-hex → short-id → quoted-string → number (last)
```

A UUID that reached the number rule first would come out as `<num>-<num>-...` and
never group with its siblings.

One regression here is worth knowing about, because it's why the templating has
tests. The number rule started life as `\b\d+(\.\d+)?\b`. The trailing `\b` never
matches in `30012ms`, since a digit followed by a letter isn't a word boundary, so
the varying duration survived into the template and identical failures never
grouped: three Postgres timeouts showed up as three separate `1×` rows, which
defeats the point of the feature. Dropping the trailing `\b` fixed it; the leading
one stays, and is what keeps digits from being pulled out of ids like `x4k2`. It
was caught by running `collapse()` on known-equal inputs, not by eyeballing output.

Templates are whitespace-normalised and capped at 160 characters. Two messages
that agree that far are the same problem.

## Filtering

`LogLens.keep()` ANDs the active filters together:

```
grep    substring of raw, case-insensitive
trace   requestId equals the argument exactly
level   level.compareTo(min) >= 0, with UNKNOWN exempt
where   every KEY=VALUE matches extras[KEY] exactly
```

Two choices here are deliberate:

Unstructured lines are kept only while no field-based filter is on. With no
filters, a plaintext startup banner is useful context. Under `--level ERROR`, a
line with no level can't be an error, so it drops. Keeping it would mean
`--level ERROR` printing non-errors.

`--where service=x` falls back to the source. If a line has no `service` field but
came in with a kubectl/compose prefix, the prefix is the answer to the question the
user asked. It's special-cased to `service` only, not a general alias table,
because that's the one field where the prefix and a logged value mean the same
thing.

## Aggregation

`StatsCollector` keeps, per line:

- running totals and an unparsed count
- counts by level (a `Map<Level,Long>`, at most 7 keys)
- counts by source (a `HashMap`, one key per pod)
- distinct problems, keyed by *template* rather than raw message
- errors per minute, in a `TreeMap` so the buckets stay ordered for the peak line

Two scoping calls keep the output dense: continuations aren't counted (a stack
frame isn't its own event, and counting frames inflates totals by stack depth),
and only WARN and above get clustered (INFO patterns dominate by volume and bury
the real findings).

Memory is bounded by cardinality, not stream length. A 10 GB log with 20 distinct
failure modes uses the same memory as a 10 MB one.

## Performance

- Time per line is O(rules): about a dozen regex applications worst case, and only
  on WARN+ lines.
- Memory is O(distinct templates + distinct sources), independent of log size.
- Allocation per line is one `LinkedHashMap` and one `StringBuilder`.
- Output goes through a `PrintWriter` with autoFlush on, so a piped `-f` streams
  live instead of appearing in 8 KB chunks.

Nothing here has been benchmarked. Jackson's `readTree` builds a full node tree
per JSON line, and a flat scanner would probably be faster, but that's a guess
until someone profiles it.

## Adding a format

It's meant to be one method plus one enum constant:

1. Add a constant to `LogEntry.Kind`.
2. Write `tryXxx(String rest)` in `LogParser`, returning `null` when it doesn't
   match.
3. Slot the call into the `parse()` chain by specificity. More specific formats go
   before more permissive ones.
4. For a key/value format, reuse `build(kind, raw, extras)` to lift the well-known
   fields. For a positional one, reuse `logEntry(...)`.
5. Add a guard against false positives and a test with a representative line.

Airflow, log4j, Python and the generic fallback were all added exactly this way in
v0.2.

## Build and distribution

```
mvn package              target/loglens.jar   (shaded fat jar)
mvn -Pnative package     target/loglens       (GraalVM native image)
```

`picocli-codegen` runs as an annotation processor and generates the GraalVM
reflection metadata for the command tree, so the native build needs no
hand-written reflection config.

The native binary matters more than it looks. For a CLI you run constantly inside
pipes, ~200 ms of JVM startup per call is the difference between a tool people keep
and one they drop. It's also the only form that installs without a JVM present.
Building it per platform (macOS arm64, Linux x64, Windows x64) belongs in CI.

## Known gaps

- **No CI.** The 44 tests exist but nothing runs them automatically, and no release
  binaries are built. This is the first thing to fix.
- **No time filtering.** `--since 10m` still means piping through `awk`.
- **Timestamps stay strings.** They're never parsed into an instant, so time
  filtering and cross-source ordering aren't possible yet.
- **Postgres renders badly.** The generic fallback leaves noise like `UTC [1234] :`
  in the message. A dedicated matcher would fix it.
- **Old cmd.exe colour.** Escape codes can be emitted where `System.console()` is
  non-null but ANSI isn't supported. Modern Windows Terminal and PowerShell 7 are
  fine.

The tests are worth their weight: writing them caught a wrong assertion about
number/null grouping, which turned out to be a misleading comment rather than a
bug (see `MessageTemplateTest.numberAndNullDoNotGroup`). Every bug noted in this
document has a regression test pinning it.
