#!/usr/bin/env bash
set -euo pipefail
PATCH=/tmp/relay-env-patch.env
ENV_FILE=/opt/relay/.env
cd /opt/relay

touch "$ENV_FILE"
sed -i 's/\r$//' "$ENV_FILE"
sed -i 's/\r$//' "$PATCH"

upsert() {
  local key="$1"
  local val="$2"
  if grep -qE "^${key}=" "$ENV_FILE"; then
    local escaped
    escaped=$(printf '%s' "$val" | sed -e 's/[\/&|]/\\&/g')
    sed -i "s|^${key}=.*|${key}=${escaped}|" "$ENV_FILE"
  else
    printf '%s=%s\n' "$key" "$val" >> "$ENV_FILE"
  fi
}

while IFS= read -r line || [ -n "$line" ]; do
  [ -z "$line" ] && continue
  case "$line" in
    \#*) continue ;;
  esac
  key=${line%%=*}
  val=${line#*=}
  upsert "$key" "$val"
done < "$PATCH"

sed -i 's/\r$//' "$ENV_FILE"

host=$(grep -E '^SPRING_DATASOURCE_URL=' "$ENV_FILE" | head -1 | sed -E 's#^SPRING_DATASOURCE_URL=jdbc:postgresql://([^/:?]+).*#\1#')
echo "DB_HOST=$host"
user=$(grep -E '^SPRING_DATASOURCE_USERNAME=' "$ENV_FILE" | head -1 | cut -d= -f2-)
echo "USER_SET=$([ -n "$user" ] && echo yes || echo no)"
g=$(grep -E '^GOOGLE_OAUTH_CLIENT_IDS=' "$ENV_FILE" | head -1 | cut -d= -f2-)
echo "GOOGLE_SET=$([ -n "$g" ] && echo yes || echo no)"

rm -f "$PATCH"

chmod +x run-jar.sh stop-jar.sh
./stop-jar.sh || true
./run-jar.sh
sleep 20
if curl -fsS http://127.0.0.1:8080/actuator/health; then
  echo
  echo LOCAL_OK
else
  echo LOCAL_FAIL
  tail -n 80 logs/application.log || true
  exit 1
fi
curl -fsS https://dev-api-relay.saungera.com/actuator/health
echo
echo HTTPS_OK
