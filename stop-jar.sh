#!/usr/bin/env bash
# Stop the background Relay API started by ./run-jar.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="${PID_FILE:-$ROOT/relay-api.pid}"

if [[ ! -f "$PID_FILE" ]]; then
  echo "Not running (no $PID_FILE)."
  exit 0
fi

pid="$(cat "$PID_FILE")"
if [[ -z "$pid" ]]; then
  rm -f "$PID_FILE"
  echo "Not running (empty pid file)."
  exit 0
fi

if kill -0 "$pid" 2>/dev/null; then
  kill "$pid"
  # Wait briefly for clean shutdown
  for _ in 1 2 3 4 5; do
    if ! kill -0 "$pid" 2>/dev/null; then
      break
    fi
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill -9 "$pid" 2>/dev/null || true
  fi
  echo "Stopped pid $pid"
else
  echo "Process $pid is not running."
fi
rm -f "$PID_FILE"
