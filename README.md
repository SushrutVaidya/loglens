# loglens

Make structured JSON logs readable. Pretty-print, filter, and follow one
request across lines — from the terminal, with no config.

```
tail -f app.log | loglens --trace 9f2c-a1
```

Structured (JSON-lines) logging is great for machines and miserable for humans:

```
{"timestamp":"2026-08-15T10:22:03.010Z","level":"error","trace_id":"7b0e-c3","message":"query failed: timeout","sql":"SELECT ..."}
```

`loglens` turns that into one scannable, colour-coded line:

```
10:22:03  ERROR  query failed: timeout  req=7b0e-c3  sql=SELECT ...
```

## Why

It came out of running a Spring Boot service that logged single-line JSON with
an `X-Request-Id` on every line. Debugging meant `grep`-ing one id out of
thousands of lines and squinting at braces. `loglens --trace <id>` is that
workflow as one flag.

## Features

- **Pretty-print** JSON, logfmt and klog logs as `time  LEVEL  message  key=val ...`, colour-coded by level.
- **`--stats`** — a triage summary instead of a scroll. Counts by level and source, **distinct problems with occurrence counts**, and when errors peaked. Near-identical messages collapse by template, so 400 instances of one timeout become one finding rather than 400 lines.
- **`--trace <id>`** — show only lines for one request and highlight the id. Auto-detects the common correlation fields (`requestId`, `X-Request-Id`, `traceId`, `trace_id`, ...); override with `--trace-field`. Stack traces stay attached to the entry they belong to.
- **`--level WARN`** — minimum level threshold (`TRACE < DEBUG < INFO < WARN < ERROR < FATAL`).
- **`--where key=value`** — keep only matching lines; repeatable (AND).
- **`--grep text`** — substring match on the raw line.
- **Multi-format, in one stream** — JSON, logfmt (`level=info msg=...`) and klog (`I0815 10:22:01.101 controller.go:123]`) parse side by side, so a `kubectl logs` containing your app *and* the control plane reads uniformly.
- **Prefix-aware** — strips `kubectl logs --prefix` (`[pod/name/container]`) and `docker compose` (`backend-1 |`) wrappers, showing the pod as a source tag only when a stream carries more than one.
- **Field-name agnostic** — understands `message`/`msg`/`@message`, `level`/`severity`/`lvl`, `timestamp`/`ts`/`@timestamp`, so it works across Logback, zap, pino, bunyan and friends.
- **Streaming** — reads line by line, never buffers the whole log. Pipe `kubectl logs -f` straight in.
- **Well-behaved output** — auto-detects when it's piped and drops colour, honours [`NO_COLOR`](https://no-color.org), passes unparseable lines through untouched.

## Triage an incident in one command

```
$ kubectl logs backend-7d9f-x4k2 | loglens --stats --quiet

9 lines  ·  10:22:01 → 10:24:52
ERROR 5   WARN 1   INFO 3

by source
  backend-7d9f-x4k2                      5
  nginx-ingress                          2

distinct problems (warn and above)
       3× ERROR  Connection to 10.0.3.14:5432 timed out after 30012ms
       2× ERROR  upstream connection refused
       1× ERROR  Failed to sync pod

errors peaked at 10:24 (5 in that minute)
```

Five errors, three actual problems, and the minute to point `--trace` at. Drop
`--quiet` to get the summary after the log lines.

## Format support

| Format | Status |
|---|---|
| JSON lines | Supported |
| logfmt (`level=info msg=...`) | Supported |
| klog (`I0815 ... controller.go:123]`) | Supported |
| `kubectl logs --prefix` / `docker compose` prefixes | Supported |
| JVM stack traces | Attached to their parent entry |
| Airflow (`[ts] {file:line} LEVEL - msg`) | **Not yet** |

## Install

Requires JDK 21+ to build.

```bash
mvn package                      # runs the test suite, then builds the jar
java -jar target/loglens.jar app.log
```

Run the tests alone with `mvn test` - 44 cases covering every supported format,
the fall-through guards that stop one parser stealing another's lines, and the
message-template grouping that `--stats` depends on.

### Native binary (no JVM startup lag)

With a [GraalVM](https://www.graalvm.org/) JDK on the path:

```bash
mvn -Pnative package
./target/loglens app.log
```

## Usage

```
loglens [FILE] [options]

  FILE                 log file to read; omit to read stdin
  -t, --trace ID       show only lines whose correlation id equals ID
      --trace-field N  field holding the correlation id
  -l, --level LEVEL    minimum level: TRACE|DEBUG|INFO|WARN|ERROR|FATAL
  -w, --where KEY=VAL  keep lines where field KEY equals VAL (repeatable)
  -g, --grep TEXT      keep lines whose raw text contains TEXT
  -s, --stats          print a triage summary after the lines
      --quiet          with --stats, print only the summary
      --[no-]color     force colour on/off (default: auto)
  -h, --help           show help
  -V, --version        show version
```

### Examples

```bash
# Follow one request end to end, live
tail -f app.log | loglens --trace 9f2c-a1

# Errors only, from a file
loglens --level ERROR app.log

# One service's warnings, this request
loglens -w service=auth -l WARN --trace 7b0e-c3 app.log
```

## License

Apache License 2.0 - see [LICENSE](LICENSE).
