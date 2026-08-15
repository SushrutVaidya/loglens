# loglens — readable logs in the terminal

**One line:** pipe any structured log stream in, get a scannable, filterable view out. No agent, no backend, no schema, no changes to your application.

MIT licensed · Java 21+ · single binary or jar · reads stdin or a file

---

## The problem

A production incident, viewed the way we actually view it — `kubectl logs <pod>`:

```
{"timestamp":"2026-08-15T10:22:01.101Z","level":"INFO","logger":"c.s.p.b.controller","requestId":"9f2c-a1","message":"GET /api/stats","service":"portfolio-backend","pod":"backend-7d9f-x4k2"}
{"timestamp":"2026-08-15T10:22:01.118Z","level":"DEBUG","logger":"c.s.p.b.service.StatsService","requestId":"9f2c-a1","message":"cache miss, querying db","service":"portfolio-backend","key":"stats:latest"}
{"timestamp":"2026-08-15T10:24:10.402Z","level":"WARN","logger":"com.zaxxer.hikari.pool.HikariPool","requestId":"9f2c-a1","message":"HikariPool-1 - Connection is not available, request timed out after 30000ms","service":"portfolio-backend","activeConnections":10,"idleConnections":0}
{"timestamp":"2026-08-15T10:24:10.489Z","level":"ERROR","logger":"c.s.p.b.config.GlobalExceptionHandler","requestId":"9f2c-a1","message":"Unhandled exception: could not obtain connection","service":"portfolio-backend","exceptionClass":"org.springframework.jdbc.CannotGetJdbcConnectionException","status":500}
	at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:696)
{"timestamp":"2026-08-15T10:24:11.003Z","level":"ERROR","logger":"c.s.p.b.controller","requestId":"3e81-b7","message":"Unhandled exception: could not obtain connection","service":"portfolio-backend","exceptionClass":"org.springframework.jdbc.CannotGetJdbcConnectionException","status":500}
```

Structured logging is excellent for machines and hostile to humans. The timestamp is 24 characters of which 8 matter. The level is buried mid-line. The message — the only part you're reading — sits behind four keys. Multiply by 14,000 lines.

The usual workaround is a bespoke `jq` incantation per service, because every service names its fields differently.

## The same logs through loglens

```
$ kubectl logs backend-7d9f-x4k2 | loglens

10:22:01  INFO   GET /api/stats  req=9f2c-a1  logger=c.s.p.b.controller  service=portfolio-backend  pod=backend-7d9f-x4k2
10:22:01  DEBUG  cache miss, querying db  req=9f2c-a1  logger=c.s.p.b.service.StatsService  key=stats:latest
10:24:10  WARN   HikariPool-1 - Connection is not available, request timed out after 30000ms  req=9f2c-a1  activeConnections=10  idleConnections=0
10:24:10  ERROR  Unhandled exception: could not obtain connection  req=9f2c-a1  exceptionClass=org.springframework.jdbc.CannotGetJdbcConnectionException  status=500
	at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:696)
10:24:11  ERROR  Unhandled exception: could not obtain connection  req=3e81-b7  status=500
```

Time, level, message first — because that's the reading order. Levels are colour-coded (ERROR red, WARN yellow) and column-aligned so a wall of text becomes a scannable list. Everything else trails behind, dimmed. Nothing is discarded.

## Three things it does

### 1. Errors only

```
$ loglens --level ERROR incident.log

10:24:10  ERROR  Unhandled exception: could not obtain connection  req=9f2c-a1  status=500
10:24:11  ERROR  Unhandled exception: could not obtain connection  req=3e81-b7  status=500
10:24:12  ERROR  Unhandled exception: could not obtain connection  req=c0d4-e2  status=500
```

`--level` is a threshold, so `WARN` includes ERROR and FATAL.

### 2. Follow one request end to end

The headline feature. Given a correlation id, show only that request's journey:

```
$ loglens --trace 9f2c-a1 incident.log

10:22:01  INFO   GET /api/stats  req=9f2c-a1
10:22:01  DEBUG  cache miss, querying db  req=9f2c-a1  key=stats:latest
10:24:10  WARN   HikariPool-1 - Connection is not available, request timed out after 30000ms  req=9f2c-a1
10:24:10  ERROR  Unhandled exception: could not obtain connection  req=9f2c-a1  status=500
10:24:52  INFO   200 OK  req=9f2c-a1  status=200  durationMs=171014
```

Five lines, and the whole story is visible: cache miss → connection pool exhausted → 30s timeout → 500 → eventually a 200 after **171 seconds**. That is the incident, extracted from 14,000 lines by one flag.

It auto-detects the common correlation fields (`requestId`, `X-Request-Id`, `traceId`, `trace_id`, `correlationId`, …), so it works without configuration. `--trace-field` overrides for anything unusual.

### 3. Filter on any field

```
$ loglens --where service=auth --level WARN app.log
$ loglens --grep timeout app.log
```

`--where` is repeatable and ANDs together.

## Why it works on logs it has never seen

Every logger spells the same field differently — `message` / `msg` / `@message`, `level` / `severity` / `lvl`, `timestamp` / `ts` / `@timestamp`. loglens maps all of these onto one shape, so the same command works across Logback, zap, pino and bunyan without per-service configuration.

Non-JSON lines — stack traces, startup banners — pass through untouched rather than being dropped, so context survives.

## Design decisions

- **Read-only.** No library, no dependency, no code change in your service. It is a pipe citizen like `grep` or `jq`.
- **Streaming.** Reads line by line; memory is flat whether the log is 100 lines or 10 GB. `kubectl logs -f | loglens` shows lines as they arrive.
- **No live-tail flag.** `kubectl logs -f` and `tail -f` already do this well; reimplementing it would mean owning Kubernetes auth and reconnection.
- **Well-behaved output.** Colour auto-disables when piped, honours `NO_COLOR`. `loglens app.log | grep foo` stays clean.

## Format support

| Format | Status |
|---|---|
| JSON lines | Supported |
| logfmt (`level=info msg=…`) | Supported |
| klog (`I0815 10:22:01.101 controller.go:123]`) | Supported |
| `kubectl logs --prefix` / `docker compose` prefixes | Supported |
| JVM stack traces | Attached to their parent entry |
| Airflow (`[ts] {file:line} LEVEL - msg`) | **Not yet** |

One tool therefore covers a whole cluster: your service's JSON, the control
plane's klog, and the ingress controller's logfmt all render in the same shape,
interleaved, in one `kubectl logs` stream.

## Roadmap

1. **Airflow log format** — `[ts] {file:line} LEVEL - msg`, so Airflow task and
   scheduler pods read as cleanly as everything else.
2. **Time windows** — `--since 10m`, to narrow to the window that matters
   without piping through `awk`.
3. **Unit tests and CI** — the parser and the template collapsing are the two
   places regressions would hide.

## Getting it

```bash
git clone <repo>
cd loglens
mvn package
java -jar target/loglens.jar app.log
```

A GraalVM native build (`mvn -Pnative package`) produces a standalone binary with no JVM startup cost — the preferred form for something invoked constantly in pipes. Cross-platform: macOS, Linux and Windows binaries all build from the same source.

## Usage

```
loglens [FILE] [options]

  FILE                 log file to read; omit to read stdin
  -t, --trace ID       show only lines whose correlation id equals ID
      --trace-field N  field holding the correlation id
  -l, --level LEVEL    minimum level: TRACE|DEBUG|INFO|WARN|ERROR|FATAL
  -w, --where KEY=VAL  keep lines where field KEY equals VAL (repeatable)
  -g, --grep TEXT      keep lines whose raw text contains TEXT
      --[no-]color     force colour on/off (default: auto)
```

## Prior art

Worth knowing what exists: `jq` (needs a per-service schema), `humanlog` (JSON/logfmt pretty-printing), `stern` (multi-pod tailing), `lnav` (multi-format TUI navigator), and Loki / Splunk / Datadog (which solve this properly, at the cost of infrastructure).

loglens aims at the case those leave open: a terminal, a log stream, no observability stack available — a dev cluster, an SSH session, or the incident where the observability stack is itself degraded.
