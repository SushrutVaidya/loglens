# loglens — Architecture

**Scope.** 991 lines of Java across 8 classes, two runtime dependencies. This document covers the data flow, each component's contract, the three non-obvious algorithms, and the decisions that constrain future work.

**Version.** 0.2.0 · Java 21 target · Picocli 4.7.6 · Jackson Databind 2.18.2

---

## 1. What it is

A read-only, streaming log formatter. It consumes lines from stdin or a file, classifies each into a normalised entry, filters, renders, and optionally accumulates a triage summary.

It is a **pipe citizen**: no daemon, no network, no state on disk, no library to embed in the application being observed. The unit of composition is a Unix pipe.

```
kubectl logs -f pod | loglens --trace 9f2c-a1
```

### Non-goals, and why

| Not implemented | Reason |
|---|---|
| Log collection / tailing | `kubectl logs -f`, `stern` and `tail -f` already do this. Owning it means owning Kubernetes auth, pod discovery and reconnection — a different product. |
| Storage or indexing | That is Loki/Splunk. Competing on retrieval loses; the niche is *no infrastructure available*. |
| A TUI | Terminal output composes with pipes; a full-screen UI does not. Also weak library support on the JVM. |
| Writing to the log source | Read-only is a hard invariant. Nothing here can mutate what it observes. |

---

## 2. Data flow

Single pass, one line at a time, no buffering of the stream.

```
                stdin / FILE
                     │
                     ▼
        ┌────────────────────────┐
        │  LogParser.parse()     │   per line, no shared mutable state
        │                        │
        │  1. strip prefix   ────┼──►  source = pod / container
        │  2. continuation?  ────┼──►  Kind.CONTINUATION (early return)
        │  3. try JSON           │
        │  4. try klog           │
        │  5. try logfmt         │
        │  6. fall through   ────┼──►  Kind.PLAIN (verbatim)
        └───────────┬────────────┘
                    │  LogEntry
        ┌───────────┴────────────┐
        │                        │
        ▼                        ▼
┌───────────────┐      ┌──────────────────┐
│ LogLens.keep()│      │ StatsCollector   │   sees EVERY entry,
│  grep         │      │  .add()          │   pre-filter — a summary
│  trace        │      │                  │   must describe the whole
│  level        │      │  MessageTemplate │   stream, not the filtered
│  where        │      │   .collapse()    │   subset
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

**Ordering guarantee.** Lines are emitted in input order. `--stats` output is appended after the final line, or replaces it entirely under `--quiet`.

**Why stats sees unfiltered entries.** A summary describing only what survived the filter would be misleading — "3 errors" when you asked for `--level ERROR` tells you nothing about the other 14,000 lines. `StatsCollector.add()` is therefore called before `keep()`.

---

## 3. Components

| Class | LOC | Responsibility |
|---|---|---|
| `LogLens` | 207 | Picocli command; owns the read loop, filter predicate, and continuation-inheritance state |
| `LogParser` | 264 | Line → `LogEntry`. Prefix stripping, format detection, field normalisation |
| `StatsCollector` | 192 | Streaming aggregation; renders the triage summary |
| `LogRenderer` | 94 | `LogEntry` → one display line |
| `MessageTemplate` | 74 | Collapses a message to a template so occurrences group |
| `LogEntry` | 73 | Immutable parsed line. `Kind` discriminates JSON/KLOG/LOGFMT/CONTINUATION/PLAIN |
| `Ansi` | 47 | Single authority on whether colour is enabled |
| `Level` | 40 | Ordered severity enum + tolerant parsing |

### 3.1 `LogEntry` — the normalised shape

Four well-known fields are lifted out; everything else is preserved.

```java
Kind kind;                  // JSON | KLOG | LOGFMT | CONTINUATION | PLAIN
String raw;                 // original text, for verbatim rendering
String source;              // pod/container from a prefix, else null
String timestamp;           // as printed by the source
Level level;
String message;
String requestId;
Map<String,Object> extras;  // LinkedHashMap — source order preserved
```

Immutable, with one copy-on-write mutator (`withSource`). `extras` is a `LinkedHashMap` deliberately: field order in a log line carries author intent, and reordering it makes output harder to scan than the original.

**Nothing is discarded.** Unrecognised fields land in `extras`; unparseable lines keep `raw`. A formatter that silently drops data is not trustworthy during an incident.

### 3.2 `Level` — ordered, tolerant

Enum ordering *is* the comparison used by `--level`, so `--level WARN` is `level.compareTo(WARN) >= 0`.

`UNKNOWN` is declared **first** (below `TRACE`) so an unrecognised level never outranks a real one. It is also explicitly exempted from level filtering — a line whose severity we failed to parse should not be hidden by a threshold it might well have exceeded.

`Level.from()` maps ~25 spellings (`WARNING`, `SEVERE`, `CRIT`, syslog numerics) and returns `UNKNOWN` rather than throwing. An unparseable level is data, not an error.

### 3.3 `Ansi` — one decision point

Colour is resolved once at startup, in priority order:

1. explicit `--color` / `--no-color`
2. `NO_COLOR` environment variable ([no-color.org](https://no-color.org))
3. `System.console() != null` — null when stdout is piped or redirected

When disabled, every method is identity. Call sites never branch on colour, which is what keeps the renderer readable.

---

## 4. The three non-obvious algorithms

### 4.1 Format detection — ordered, guarded fall-through

`LogParser.parse()` tries formats in a fixed order and takes the first that matches. Order is not arbitrary: cheapest and least ambiguous first.

```
JSON    → first non-space char is '{', then Jackson readTree
klog    → regex anchored on ^[IWEF]\d{4}\s+HH:mm:ss …\S+:\d+]
logfmt  → line must OPEN with key=, and tokens must cover ≥50% of it
PLAIN   → nothing matched
```

**The guards matter more than the patterns.** logfmt is the ambiguous case: a prose line such as `error: connection = refused` contains an `=` and would misclassify under a naive token scan. Two conditions defend against it — the line must begin with `key=`, and matched tokens must cover at least half the line's characters. Failing either, it falls through to `PLAIN` and renders verbatim, which is the safe outcome.

**Prefix stripping runs first**, and only its result is passed to the format detectors:

| Wrapper | Pattern | Extracted |
|---|---|---|
| `kubectl logs --prefix` | `^\[([^\]]+)]\s+(.*)$` | `pod/name/container` → the **pod** segment |
| `docker compose` | `^(\S+)\s*\|\s(.*)$` | the service name |

The compose pattern requires a literal `|`. Without that constraint it would consume the first word of ordinary prose. When neither matches, the *original* line is carried forward untouched — never a partially-consumed one.

### 4.2 Continuation attachment — inheritance, not lookahead

A JVM stack frame has no fields, so it cannot satisfy `--trace` or `--level` on its own. Judged independently it gets dropped — which removes the stack trace from the very error you are tracing. This was a real defect in v0.1, found while writing documentation.

**Detection** (after prefix stripping, so compose-wrapped frames still register):

- leading whitespace — *unless* the trimmed remainder starts with `{`, which is an indented JSON entry
- or an opening marker: `at <frame>`, `Caused by:`, `Suppressed:`, `... N more`, `com.foo.SomeException:`

**Attachment** is one boolean in the read loop:

```java
if (entry.kind == CONTINUATION) {
    keep = lastEntryKept;          // inherit the parent's verdict
} else {
    keep = keep(entry, needle);
    lastEntryKept = keep;
}
```

The alternative — buffer an entry, peek at the next line, then emit — would make `kubectl logs -f | loglens` lag one line behind indefinitely, because the pending line only flushes when another arrives. **Inheritance preserves streaming semantics; lookahead would break them.** That constraint drove the design.

Indentation is *normalised* to four spaces at render time rather than echoed, because the `\s+` in the kubectl prefix pattern consumes the original leading tab, and sources differ in indent depth anyway.

### 4.3 Message templating — why `--stats` is useful

Counting raw messages is worthless: 400 timeouts differing only in IP and duration count as 400 problems. `MessageTemplate.collapse()` replaces the varying parts with placeholders so occurrences group.

```
Connection to 10.0.3.14:5432 timed out after 30012ms
Connection to 10.0.7.82:5432 timed out after 29984ms
        ↓
Connection to <ip>:<num> timed out after <num>ms      →  2×
```

Same premise as Drain/logreduce, implemented as an **ordered substitution list** rather than a parse tree — sufficient for single-line messages and O(rules) per line with no state.

**Order is load-bearing.** Specific patterns must consume their text before general ones:

```
uuid → timestamp → ip → email → url → @hash → pod-suffix
     → path → long-hex → short-id → quoted-string → NUMBER (last)
```

A UUID reaching the number rule first would be shredded into `<num>-<num>-…` and never group.

> **Regression worth recording.** The number rule was originally `\b\d+(\.\d+)?\b`. The trailing `\b` never matches in `30012ms`, because a digit followed by a letter is not a word boundary. The varying duration therefore survived into the template and identical failures never grouped — three Postgres timeouts rendered as three separate `1×` rows, defeating the entire feature. The trailing `\b` was removed; the leading one is retained, and is what prevents digits being chewed out of identifiers like `x4k2`.
>
> This was found by exercising `collapse()` directly on known-equivalent inputs, not by reading output. It is the strongest argument in the codebase for unit tests.

Templates are whitespace-normalised and capped at 160 characters — two messages agreeing that far are the same problem.

---

## 5. Filtering

`LogLens.keep()` — all active filters AND together.

```
grep    → substring of raw, case-insensitive
trace   → requestId equals the argument exactly
level   → level.compareTo(min) >= 0, with UNKNOWN exempt
where   → every KEY=VALUE matches extras[KEY] exactly
```

Two deliberate asymmetries:

**Unstructured lines are kept only while no structured filter is active.** With no filters, a startup banner is useful context. Under `--level ERROR`, a line with no level cannot satisfy the request, so it drops. Retaining it would mean `--level ERROR` printing non-errors.

**`--where service=x` falls back to `source`.** If a line has no `service` field but arrived with a `kubectl`/compose prefix, the prefix answers the question the user asked. Special-cased to the `service` key only, rather than a general alias table, because that is the one case where prefix and field are semantically the same thing.

---

## 6. Aggregation

`StatsCollector` maintains, per line:

| Accumulator | Structure | Bound |
|---|---|---|
| totals, unparsed count | `long` | O(1) |
| by level | `Map<Level,Long>` | ≤ 7 entries |
| by source | `HashMap<String,Long>` | distinct pods |
| distinct problems | `HashMap<String,Pattern>` | distinct **templates**, not lines |
| errors per minute | `TreeMap<String,Long>` | minutes spanned |

Two scoping decisions keep the output signal-dense:

- **Continuations are not counted.** A stack frame is not an event; counting frames inflates totals by stack depth.
- **Only WARN and above are clustered.** INFO patterns dominate by volume and bury real findings.

`TreeMap` for the time buckets gives ordered iteration for free, which is what makes the peak-minute line cheap to compute.

**Memory is bounded by cardinality, not stream length** — a 10 GB log with 20 distinct failure modes uses the same memory as a 10 MB one.

---

## 7. Performance

| Property | Value |
|---|---|
| Time per line | O(rules); ~12 regex applications worst case, only on WARN+ |
| Memory | O(distinct templates + distinct sources) — independent of log size |
| Allocation per line | one `LinkedHashMap`, one `StringBuilder` |
| Output | `PrintWriter(autoFlush=true)` so piped `-f` streams live |

`autoFlush` trades throughput for latency deliberately: an interactive tail that only appears when an 8 KB buffer fills is unusable.

**Unmeasured.** No benchmarks exist. Jackson's `readTree` allocates a full node tree per line; a streaming parser or a hand-rolled flat-object scanner would likely be faster, but nothing has been profiled, so that remains a hypothesis rather than a plan.

---

## 8. Extension: adding a format

Contained by design — one method plus one enum constant.

1. Add a constant to `LogEntry.Kind`.
2. Write `tryXxx(String rest)` in `LogParser`, returning `null` on no-match.
3. Insert the call into the `parse()` chain, ordered by specificity — **more specific formats must precede more permissive ones**.
4. If it is a key/value format, reuse `build(kind, raw, extras)` for field lifting.
5. Add a guard against false positives, then confirm a representative line still classifies correctly.

Adding Airflow's `[ts] {file:line} LEVEL - msg` is exactly this shape: one anchored regex, placed before `logfmt`.

---

## 9. Build and distribution

```
mvn package              → target/loglens.jar   (shade fat jar, manifest main-class)
mvn -Pnative package     → target/loglens       (GraalVM native-image)
```

`picocli-codegen` runs as an annotation processor, generating GraalVM reflection metadata for the `@Command` tree — which is why the native build needs no hand-written reflection config.

The native binary matters more than it appears: for a CLI invoked constantly inside pipes, ~200 ms of JVM startup per invocation is the difference between a tool people keep and one they abandon. It is also the only form that installs without a JVM present.

Targets are per-platform (macOS arm64, Linux x64, Windows x64) and belong in CI, not on a laptop.

---

## 10. Known gaps

| Gap | Consequence |
|---|---|
| **No tests** | The template regex bug in §4.3 shipped and was caught by luck. The parser and collapser are the two highest-risk surfaces and both are untested. |
| No CI | Nothing verifies the build, and no release binaries are produced. |
| Airflow format unsupported | The author's primary daily log source still renders as `PLAIN`. |
| No time filtering | `--since 10m` requires piping through `awk` today. |
| Timestamps are strings | Never parsed into an instant, so time filtering and cross-source ordering are not currently possible. |
| Old `cmd.exe` colour | Escape codes emitted where `System.console()` is non-null but ANSI is unsupported. Modern Windows Terminal and PowerShell 7 are fine. |

Ordering by risk, tests come first: a parsing tool relied on during an incident must not silently misclassify, and §4.3 is proof that it can.
