#!/usr/bin/env bash
# Checks every datastore is reachable and correctly configured. Run it after `docker compose up -d`
# and before blaming your own code.
#
#   ./infra/smoke-test.sh
set -uo pipefail

pass=0; fail=0
check() {
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then
    printf "  \033[32mok\033[0m    %s\n" "$name"; pass=$((pass+1))
  else
    printf "  \033[31mFAIL\033[0m  %s\n" "$name"; fail=$((fail+1))
  fi
}

echo "DiscoveryHub infrastructure"
echo

check "kafka          localhost:9092" \
  docker exec discoveryhub-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
check "redis          localhost:6379    P1 dedupe" \
  docker exec discoveryhub-redis redis-cli ping
check "postgres       localhost:5433    P2 archive" \
  docker exec discoveryhub-postgres-archive psql -U archive -d archive -c "select 1"
check "postgres       localhost:5434    P4 cases" \
  docker exec discoveryhub-postgres-cases psql -U cases -d cases -c "select 1"
check "postgres       localhost:5435    P5 audit" \
  docker exec discoveryhub-postgres-audit psql -U audit -d audit -c "select 1"
check "mongodb        localhost:27017" \
  docker exec discoveryhub-mongodb mongosh --quiet --eval "db.adminCommand('ping')"
check "elasticsearch  localhost:9200    P3 index" \
  curl -sf http://localhost:9200/_cluster/health
check "minio          localhost:9000    P2/P5 blobs" \
  curl -sf http://localhost:9000/minio/health/live

# The checks above run `docker exec`, so they prove the datastore is alive inside its container and
# nothing more. The services run as jars on the host and reach these over published ports, and the
# two can disagree: a bind that loses a port race leaves the container healthy, `docker port`
# empty, and every check above green while storage-service dies on "Connection to localhost:5433
# refused". Test the path the services actually take.
echo
echo "host ports"
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
for entry in "6379 redis" "5433 postgres P2 archive" "5434 postgres P4 cases" \
             "5435 postgres P5 audit" "5436 postgres P4 holds" "5437 postgres P2.2 disposition" \
             "9092 kafka" "9200 elasticsearch" "9000 minio"; do
  set -- $entry
  check "$(printf '%-6s %s' "$1" "${*:2}")" port_open "$1"
done

echo
echo "topics"
docker exec discoveryhub-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe 2>/dev/null | grep -E "^Topic:" | awk '{printf "  %-22s %s %s\n", $2, $5, $6}'

echo
echo "buckets"
docker exec discoveryhub-minio mc alias set smoke http://localhost:9000 minioadmin minioadmin >/dev/null 2>&1
docker exec discoveryhub-minio mc ls smoke 2>/dev/null | awk '{printf "  %s\n", $NF}'

# Catches a misconfigured advertised.listeners, which is the failure everyone hits and nobody
# diagnoses quickly. On a throwaway topic — a plain-text probe sitting in audit.events would be
# the first thing P5's deserializer chokes on, and the last place anyone would look.
echo
echo "kafka round trip"
docker exec discoveryhub-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic smoke.probe --partitions 1 --replication-factor 1 >/dev/null 2>&1
docker exec discoveryhub-kafka bash -c \
  'echo "smoke-$$" | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic smoke.probe >/dev/null 2>&1'
got=$(docker exec discoveryhub-kafka timeout 20 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic smoke.probe --from-beginning --max-messages 1 2>/dev/null | head -1)
if [ -n "$got" ]; then
  printf "  \033[32mok\033[0m    produced and consumed (%s)\n" "$got"; pass=$((pass+1))
else
  printf "  \033[31mFAIL\033[0m  could not round trip a message\n"; fail=$((fail+1))
fi

echo
if [ "$fail" -eq 0 ]; then
  printf "\033[32m%d checks passed.\033[0m Stack is ready.\n" "$pass"
else
  printf "\033[31m%d passed, %d failed.\033[0m\n" "$pass" "$fail"
  exit 1
fi
