# loglens

[![CI](https://github.com/SushrutVaidya/loglens/actions/workflows/ci.yml/badge.svg)](https://github.com/SushrutVaidya/loglens/actions/workflows/ci.yml)

Readable logs in the terminal. Pretty-print, filter, trace and summarise
structured logs - JSON, logfmt, klog, Spark, Hadoop, Kafka, Airflow - with no
agent, no backend, and no changes to the application producing them.

```
kubectl logs -f mypod | loglens --trace 9f2c-a1
```

Structured logging is excellent for machines and hostile to humans:

```
{"timestamp":"2026-08-15T10:22:03.010Z","level":"error","trace_id":"7b0e-c3","message":"query failed: timeout","sql":"SELECT ..."}
```

`loglens` turns that into one scannable, colour-coded line:

```
10:22:03  ERROR  query failed: timeout  req=7b0e-c3  sql=SELECT ...
```

![loglens triaging an incident](docs/img/stats.gif)

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

`--stats` answers the question you actually have at 3am - not "show me the lines"
but "what is the shape of this". Near-identical messages collapse by template, so
eight errors become three real problems rather than eight rows to read.

Drop `--quiet` to get the summary after the log lines instead of replacing them.

## Follow one request end to end

![following one request with --trace](docs/img/trace.gif)

Given a correlation id, `--trace` shows only that request's journey - and keeps
the stack traces attached to the errors they belong to. Five lines, and the whole
incident is legible: cache miss, pool exhausted, 30s timeout, 500, then a 200
after 171 seconds.

It auto-detects the usual correlation fields (`requestId`, `X-Request-Id`,
`traceId`, `trace_id`, `correlationId`, ...), so it works without configuration.

## Format support

![one stream, seven formats](docs/img/multiformat.gif)

A real `kubectl logs` is not one tidy format - it is your service's JSON next to
the control plane's klog next to the ingress controller's logfmt. All of it
renders in the same shape, interleaved, in one pass.

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
