#!/usr/bin/env bash
# Run the Relay API JAR on the host.
# Optional Playwright sidecar: docker compose up -d
# Default: background, logs/application.log. Foreground: ./run-jar.sh --foreground
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

ENV_FILE="${ENV_FILE:-$ROOT/.env}"
JAR="${JAR:-$ROOT/relay.jar}"
LOG_FILE="${LOG_FILE:-$ROOT/logs/application.log}"
PID_FILE="${PID_FILE:-$ROOT/relay-api.pid}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: $ENV_FILE not found. Copy .env.example to .env and edit values."
  exit 1
fi

if [[ ! -f "$JAR" ]]; then
  echo "ERROR: $JAR not found."
  echo "Build: ./mvnw -B -DskipTests clean package"
  echo "Then: cp target/relay-0.0.1-SNAPSHOT.jar $JAR"
  exit 1
fi

echo "Loading $ENV_FILE ..."
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

if [[ -f "$PID_FILE" ]]; then
  old_pid="$(cat "$PID_FILE")"
  if [[ -n "$old_pid" ]] && kill -0 "$old_pid" 2>/dev/null; then
    echo "Already running (pid $old_pid). Stop first: ./stop-jar.sh"
    exit 1
  fi
  rm -f "$PID_FILE"
fi

if [[ "${1:-}" == "--foreground" || "${RUN_FOREGROUND:-}" == "1" ]]; then
  echo "Starting Relay ($JAR) on port ${SERVER_PORT:-8080} (foreground) ..."
  exec java ${JAVA_OPTS:-} -jar "$JAR"
fi

mkdir -p "$(dirname "$LOG_FILE")"
echo "Starting Relay ($JAR) on port ${SERVER_PORT:-8080} ..."
nohup java ${JAVA_OPTS:-} -jar "$JAR" >> "$LOG_FILE" 2>&1 &
echo $! > "$PID_FILE"
echo "Started pid $(cat "$PID_FILE") — logs: $LOG_FILE"
echo "Stop with: ./stop-jar.sh"
echo "Follow logs: tail -f $LOG_FILE"
