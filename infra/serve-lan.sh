#!/usr/bin/env bash
# Run the stack so other machines on the same network can use it.
#
#   ./infra/serve-lan.sh
#
# One machine runs this and everyone else opens the URL it prints. There is no syncing between
# machines: `docker compose up` on a second laptop creates a second, empty set of databases in its
# own volumes. A case is only shared because everyone is pointed at the one host that owns it.
#
# Datastores are expected to be up already (`docker compose up -d ...`; see AGENTS.md). This script
# only starts the services and the UI.
#
# Override the advertised address with HOST_IP=... if the guess is wrong, which it will be on a
# machine with several interfaces — a VPN or a Docker bridge can easily sort ahead of the LAN.
set -euo pipefail

cd "$(dirname "$0")/.."

detect_ip() {
  if command -v ipconfig >/dev/null 2>&1; then
    for iface in en0 en1 en2; do
      ipconfig getifaddr "$iface" 2>/dev/null && return 0
    done
  fi
  if command -v hostname >/dev/null 2>&1; then
    hostname -I 2>/dev/null | awk '{print $1}' | grep . && return 0
  fi
  return 1
}

HOST_IP="${HOST_IP:-$(detect_ip || true)}"
if [ -z "$HOST_IP" ]; then
  echo "Could not work out this machine's address. Re-run with HOST_IP=192.168.x.x $0" >&2
  exit 1
fi

# The jars are built for 21 and a mismatched default JDK fails at class-load time, well after the
# script looks like it worked.
java_major="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
if [ "$java_major" != "21" ]; then
  echo "java is $java_major, the build targets 21. Set JAVA_HOME to a 21 JDK first:" >&2
  echo "  export JAVA_HOME=\$(/usr/libexec/java_home -v21)" >&2
  exit 1
fi

# Both the app mapping and actuator read this one property, so it covers the status strip as well
# as the panels. Loopback stays in the list so the host's own browser keeps working. This is the
# whole of the CORS change: no rebuild, no edit to any CorsConfig.
export DISCOVERYHUB_WEB_CORS_ALLOWED_ORIGINS="http://localhost:[*],http://127.0.0.1:[*],http://${HOST_IP}:[*]"

# Checked here rather than at the end: node is usually on PATH through nvm, which a non-login
# shell does not source, and finding that out after seven services have started is a waste.
if ! command -v npx >/dev/null 2>&1; then
  echo "npx not found. If you use nvm, source it first:" >&2
  echo "  . \"\$NVM_DIR/nvm.sh\" && nvm use" >&2
  exit 1
fi

mkdir -p /tmp/dh-logs
pids=()
cleanup() {
  echo
  echo "stopping services"
  for pid in "${pids[@]:-}"; do kill "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT INT TERM

for s in ingestion storage search case hold export disposition; do
  jar="services/$s-service/target/$s-service-0.1.0-SNAPSHOT.jar"
  if [ ! -f "$jar" ]; then
    echo "missing $jar — run: mvn -q package -DskipTests" >&2
    exit 1
  fi
  java -jar "$jar" >"/tmp/dh-logs/$s.log" 2>&1 &
  pids+=($!)
done

echo "waiting for services"
for port in 8081 8082 8083 8084 8085 8086 8087; do
  for _ in $(seq 1 60); do
    if curl -sf -m 2 "http://localhost:$port/actuator/health" >/dev/null 2>&1; then
      printf "  ok    %s\n" "$port"; break
    fi
    sleep 2
  done || true
done

cat <<EOF

Share this with the team:

    http://${HOST_IP}:4200

Service logs are in /tmp/dh-logs. Ctrl-C stops the services and the UI.

EOF

cd frontend
npx ng serve --host 0.0.0.0 --port 4200
