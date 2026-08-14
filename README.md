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

- **Pretty-print** JSON-lines logs as `time  LEVEL  message  key=val …`, colour-coded by level.
- **`--trace <id>`** — show only lines for one request and highlight the id. Auto-detects the common correlation fields (`requestId`, `X-Request-Id`, `traceId`, `trace_id`, …); override with `--trace-field`.
- **`--level WARN`** — minimum level threshold (`TRACE < DEBUG < INFO < WARN < ERROR < FATAL`).
- **`--where key=value`** — keep only matching lines; repeatable (AND).
- **`--grep text`** — substring match on the raw line.
- **Field-name agnostic** — understands `message`/`msg`/`@message`, `level`/`severity`/`lvl`, `timestamp`/`ts`/`@timestamp`, and more, so it works across Logback, zap, pino, bunyan and friends.
- **Streaming** — reads line by line, never buffers the whole log. Pipe `tail -f` straight in.
- **Well-behaved output** — auto-detects when it's piped and drops colour, honours [`NO_COLOR`](https://no-color.org), passes non-JSON lines (stack traces, banners) through untouched.

## Install

Requires JDK 21+ to build.

```bash
mvn package
java -jar target/loglens.jar app.log
```

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

MIT — see [LICENSE](LICENSE).
