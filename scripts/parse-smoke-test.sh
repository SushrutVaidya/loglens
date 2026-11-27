#!/usr/bin/env bash
# parse-smoke-test.sh - Shared parser smoke test for CI and release
#
# Usage: ./parse-smoke-test.sh <command>
#   command: the loglens command to test, e.g. "java -jar target/loglens.jar" or "./target/loglens"
#
# Tests every supported format. Fails if any line passes through unparsed or
# if a known level (e.g., payload says error) doesn't render as ERROR.

set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <command>" >&2
    exit 1
fi

CMD="$1"

# Colors for output (work on all platforms)
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PASS_COUNT=0
FAIL_COUNT=0

run_test() {
    local name="$1"
    local input="$2"
    local expect_level="${3:-}"  # Optional: expected level substring like "ERROR"

    # Run the command with --no-color to get clean output
    local out
    out=$(printf '%s\n' "$input" | $CMD --no-color 2>&1)
    local exit_code=$?

    if [[ $exit_code -ne 0 ]]; then
        echo -e "${RED}FAIL${NC} $name: command exited with $exit_code"
        echo "  Output: $out"
        FAIL_COUNT=$((FAIL_COUNT + 1))
        return
    fi

    # Check if line passed through verbatim (no timestamp/level prefix)
    # Parsed output always starts with "HH:MM:SS  LEVEL  "
    if [[ ! "$out" =~ ^[0-9]{2}:[0-9]{2}:[0-9]{2}[[:space:]]+(TRACE|DEBUG|INFO|WARN|ERROR|FATAL) ]]; then
        echo -e "${RED}FAIL${NC} $name: line passed through unparsed"
        echo "  Input:  $input"
        echo "  Output: $out"
        FAIL_COUNT=$((FAIL_COUNT + 1))
        return
    fi

    # If expected level is given, verify it
    if [[ -n "$expect_level" ]]; then
        if [[ ! "$out" =~ [[:space:]]$expect_level[[:space:]] ]]; then
            echo -e "${RED}FAIL${NC} $name: expected level $expect_level not found"
            echo "  Input:  $input"
            echo "  Output: $out"
            FAIL_COUNT=$((FAIL_COUNT + 1))
            return
        fi
    fi

    echo -e "${GREEN}PASS${NC} $name"
    PASS_COUNT=$((PASS_COUNT + 1))
}

echo "=== loglens parse smoke test ==="
echo "Command: $CMD"
echo

# 1. JSON lines
run_test "json" \
    '{"level":"error","msg":"x","ts":"2026-01-01T00:00:00Z"}' \
    "ERROR"

# 2. klog
run_test "klog" \
    'I0101 00:00:00.000000 1 controller.go:1] x' \
    "INFO"

# 3. logfmt
run_test "logfmt" \
    'level=info msg="x" ts=2026-01-01T00:00:00Z' \
    "INFO"

# 4. Airflow
run_test "airflow" \
    '[2026-01-01T00:00:00Z +0000] INFO - x' \
    "INFO"

# 5. log4j family (Spark/Hadoop/Kafka/Flink)
run_test "log4j" \
    '2026-01-01 00:00:00,000 INFO SparkContext: x' \
    "INFO"

# 6. Python logging
run_test "python" \
    '2026-01-01 00:00:00,000 - my.logger - INFO - x' \
    "INFO"

# 7. syslog/journald short-iso with logfmt payload (payload level=error must render as ERROR)
run_test "syslog-short-iso-logfmt" \
    '2026-11-24T10:22:01+0530 omnissiah dockerd[812]: time="2026-11-24T10:22:01.123+05:30" level=error msg="resolver failed" dns-server=1.1.1.1:53' \
    "ERROR"

# 8. journalctl -o json (PRIORITY=3=err must render as ERROR)
run_test "journal-json" \
    '{"MESSAGE":"Failed to connect","PRIORITY":"3","SYSLOG_IDENTIFIER":"sshd","__REALTIME_TIMESTAMP":"1732447321123456","_HOSTNAME":"host","_PID":"1234"}' \
    "ERROR"

# 9. Generic timestamp + level (fallback)
run_test "generic" \
    '2026-01-01 00:00:00 INFO something happened' \
    "INFO"

echo
echo "=== Results: ${PASS_COUNT} passed, ${FAIL_COUNT} failed ==="
if [[ $FAIL_COUNT -gt 0 ]]; then
    echo -e "${RED}SMOKE TEST FAILED${NC}"
    exit 1
fi
echo -e "${GREEN}ALL TESTS PASSED${NC}"
exit 0