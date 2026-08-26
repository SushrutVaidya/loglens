<div align="center">

# loglens

**Make structured logs readable in the terminal — pretty-print, filter, trace one request, and triage an incident, straight from a pipe.**

[![CI](https://github.com/SushrutVaidya/loglens/actions/workflows/ci.yml/badge.svg)](https://github.com/SushrutVaidya/loglens/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](#build-from-source)

![loglens turning a noisy log into a triage summary](docs/img/hero.gif)

</div>

Structured logging is great for machines and hard on people. A real incident looks like this:

```
{"timestamp":"2026-08-15T10:24:10.489Z","level":"error","trace_id":"9f2c-a1","message":"Connection to 10.0.3.14:5432 timed out after 30012ms","service":"api","status":500}
```

Pipe it through loglens and you get one scannable, colour-coded line:

```
10:24:10  ERROR  Connection to 10.0.3.14:5432 timed out after 30012ms  req=9f2c-a1  service=api  status=500
```

No agent, no backend, no change to the app writing the logs. It reads a file or stdin, so the live-follow case is just a pipe:

```
kubectl logs -f mypod | loglens --trace 9f2c-a1
```

## Why

It came out of running a Spring Boot service that logged single-line JSON with an `X-Request-Id` on every line. Debugging meant grepping one id out of thousands of lines and reading braces. `loglens --trace <id>` is that whole workflow as one flag.

## Quickstart

No prebuilt binaries yet, so build the jar once (needs JDK 21+):

```bash
git clone https://github.com/SushrutVaidya/loglens
cd loglens
mvn package                       # runs the tests, then builds target/loglens.jar
java -jar target/loglens.jar --help
```

An alias makes the rest readable:

```bash
alias loglens='java -jar '"$PWD"'/target/loglens.jar'
```

Three commands cover most of the value:

**1. Pretty-print anything.** One shape, colour-coded by level, whatever the input format:

```console
$ loglens demo/mixed.log
09:00:49  INFO   Job 0 finished: collect at Main.scala:24  logger=DAGScheduler
09:00:51  ERROR  Exception in createBlockOutputStream  logger=org.apache.hadoop.hdfs.DataStreamer
09:00:53  INFO   [GroupCoordinator 1]: Preparing to rebalance group
09:00:55  WARN   Watch died gracefully, restarting
09:00:57  ERROR  Failed to sync pod
09:00:58  INFO   Server is ready  caller=main.go:123
09:00:59  ERROR  payment declined  service=billing
```

**2. Follow one request.** `--trace` shows only that id's lines and keeps stack traces attached to the error they belong to:

```console
$ loglens --trace 9f2c-a1 demo/incident.log
10:22:01  INFO   GET /api/stats  req=9f2c-a1  logger=c.s.api.StatsController  service=api  pod=api-7d9f-x4k2
10:22:01  DEBUG  cache miss, querying db  req=9f2c-a1  logger=c.s.api.StatsService  service=api  key=stats:latest
10:24:10  WARN   HikariPool-1 - Connection is not available, request timed out after 30000ms  req=9f2c-a1  logger=com.zaxxer.hikari.pool.HikariPool  service=api  activeConnections=10  idleConnections=0
10:24:10  ERROR  Connection to 10.0.3.14:5432 timed out after 30012ms  req=9f2c-a1  logger=c.s.api.GlobalExceptionHandler  service=api  status=500
    at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:696)
    at org.springframework.jdbc.datasource.DataSourceUtils.fetchConnection(DataSourceUtils.java:159)
10:24:52  INFO   200 OK  req=9f2c-a1  logger=c.s.api.StatsController  service=api  status=200  durationMs=171014
```

Cache miss, pool exhausted, 30s timeout, 500, then a 200 after 171 seconds. Five log lines and their stack trace, and the whole request is legible.

**3. Triage before you read.** `--stats` answers "what is the shape of this" instead of showing every line:

```console
$ loglens --stats --quiet demo/incident.log

────────────────────────────────────────────────────────────────
12 lines  ·  10:22:01 → 10:24:52
ERROR 6   WARN 2   INFO 3   DEBUG 1

by source
  api                                    9
  nginx-ingress                          2

distinct problems (warn and above)
       4× ERROR  Connection to 10.0.3.14:5432 timed out after 30012ms
       2× ERROR  upstream connection refused
       1× ERROR  Failed to sync pod
       1× WARN   HikariPool-1 - Connection is not available, request timed out after 30000ms

errors peaked at 10:24 (6 in that minute)
────────────────────────────────────────────────────────────────
```

## Features

### Triage an incident — `--stats`

![loglens triaging an incident](docs/img/stats.gif)

The question at 3am isn't "show me the lines", it's "what's actually broken". `--stats` counts by level and source, then collapses near-identical messages into distinct problems. Two timeouts that differ only by IP and duration are one finding, not two rows, so a wall of errors becomes a short list. It also tells you the minute errors peaked, which is where to point `--trace` next.

Drop `--quiet` to print the summary after the log lines instead of replacing them.

### Follow one request — `--trace`

![following one request with --trace](docs/img/trace.gif)

Give it a correlation id and it shows only that request, with the id highlighted and stack traces kept attached to their error. It auto-detects the common id fields (`requestId`, `X-Request-Id`, `traceId`, `trace_id`, `correlationId`, ...); use `--trace-field` for anything else.

### One stream, many formats

![one stream, seven formats](docs/img/multiformat.gif)

A real `kubectl logs` isn't one tidy format. It's your service's JSON next to the control plane's klog next to an ingress controller's logfmt. loglens parses each line on its own and renders all of it in the same shape, interleaved, in one pass.

### Filters that compose

```bash
loglens --level ERROR app.log                     # errors and worse
loglens -w service=auth -l WARN app.log           # one service, warnings up
loglens --grep timeout app.log                    # raw substring match
loglens -w service=api --trace 9f2c-a1 app.log    # stack them (AND)
```

## Flags

| Flag | Meaning |
|---|---|
| `FILE` | Log file to read; omit to read stdin |
| `-t, --trace ID` | Show only lines whose correlation id equals `ID`, and highlight it |
| `--trace-field NAME` | Field holding the correlation id, if not an auto-detected one |
| `-l, --level LEVEL` | Minimum level: `TRACE` `DEBUG` `INFO` `WARN` `ERROR` `FATAL` |
| `-w, --where KEY=VALUE` | Keep lines where field `KEY` equals `VALUE`; repeatable (AND) |
| `-g, --grep TEXT` | Keep lines whose raw text contains `TEXT` (case-insensitive) |
| `-s, --stats` | Print a triage summary |
| `--quiet` | With `--stats`, print only the summary |
| `--[no-]color` | Force colour on/off (default: auto) |
| `-h, --help` · `-V, --version` | Help and version |

## Supported formats

| Format | Status |
|---|---|
| JSON lines | Parsed |
| logfmt (`level=info msg=...`) | Parsed |
| klog (`I0815 10:22:01.101 controller.go:123]`) | Parsed |
| Airflow (`[ts] {file:line} LEVEL - msg`) | Parsed |
| log4j family — Spark, Hadoop, Kafka, Hive, Flink | Parsed |
| Python logging (`ts - logger - LEVEL - msg`) | Parsed |
| Generic `timestamp + level` | Fallback |
| `kubectl logs --prefix` / `docker compose` prefixes | Stripped, kept as source tag |
| JVM stack traces | Attached to their parent entry |
| Anything else | Passed through untouched |

Field names are matched loosely, so `message`/`msg`/`@message`, `level`/`severity`/`lvl`, and `timestamp`/`ts`/`@timestamp` all work — which covers Logback, zap, pino, bunyan and friends.

## How it works

Each line runs through an ordered chain of parsers, most specific first, and the first one that matches wins. If none do, the line is printed verbatim rather than guessed at.

```
line ─▶ strip prefix ─▶ continuation? ─▶ JSON ─▶ Airflow ─▶ klog ─▶ Python
                                          ─▶ log4j ─▶ logfmt ─▶ generic ─▶ verbatim
```

It's a single streaming pass, one line in and one line out, so it never holds the log in memory and a piped `-f` shows lines as they arrive. The whole thing is 8 small classes and two runtime dependencies (Picocli, Jackson), covered by 44 tests. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the details.

## Why not grep / jq / lnav?

- **grep / jq** are line tools. jq only speaks JSON, so it can't read a stream that mixes JSON, klog and logfmt, and neither one does correlation or triage. `--trace` (with stack-trace attachment) and `--stats` (with message collapsing) are the parts you'd otherwise hand-roll.
- **lnav** is excellent and does far more, but it's a full-screen TUI with its own log database. loglens is a pipe filter with no UI and no state, for the times you just want a stream readable right now and still composable with `grep`, `less` and the rest.

Things loglens deliberately does **not** do: collect or tail logs (that's `kubectl logs -f`, `stern`, `tail`), store or index anything, run a TUI, or write back to the source. Time-based filtering (`--since`) isn't there yet — timestamps are currently kept as strings.

<details>
<summary><b>Build a native binary (no JVM startup)</b></summary>

With a [GraalVM](https://www.graalvm.org/) JDK on the path:

```bash
mvn -Pnative package
./target/loglens app.log
```

Picocli generates the reflection metadata at compile time, so the native build needs no hand-written config.

</details>

<details>
<summary><b>Run the tests</b></summary>

```bash
mvn test
```

44 cases covering every supported format, the fall-through guards that stop one parser stealing another's lines, and the message-template grouping that `--stats` relies on.

</details>

## Contributing

Issues and PRs welcome. If you're adding a format, it's usually one `tryXxx` method in `LogParser`, slotted into the chain by specificity, plus a test — the parser is built to make that a contained change. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
