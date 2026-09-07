#!/usr/bin/env bash
# Checks every datastore is reachable *from the host*, which is where the services actually run
# during development. A container reporting healthy only proves it can talk to itself.
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

check "kafka        localhost:9092" \
  docker exec dh-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
check "redis        localhost:6379   (P1 dedupe)" \
  docker exec dh-redis redis-cli ping
check "postgres     localhost:5433   (P2 archive)" \
  docker exec dh-pg-archive psql -U archive -d archive -c "select 1"
check "postgres     localhost:5434   (P4 cases)" \
  docker exec dh-pg-cases psql -U cases -d cases -c "select 1"
check "postgres     localhost:5435   (P5 audit)" \
  docker exec dh-pg-audit psql -U audit -d audit -c "select 1"
check "elastic      localhost:9200   (P3 index)" \
  curl -sf http://localhost:9200/_cluster/health
check "minio        localhost:9000   (P2/P5 blobs)" \
  curl -sf http://localhost:9000/minio/health/live
check "kafka-ui     localhost:8090" \
  curl -sf http://localhost:8090/actuator/health

echo
echo "topics"
docker exec dh-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe 2>/dev/null | grep -E "^Topic:" | awk '{printf "  %-22s %s\n", $1, $2" "$3" "$4" "$5" "$6}'

echo
echo "buckets"
docker exec dh-minio mc alias set smoke http://localhost:9000 minioadmin minioadmin >/dev/null 2>&1
docker exec dh-minio mc ls smoke 2>/dev/null | awk '{printf "  %s\n", $NF}'

# Round trip a message through the broker from the host's perspective. This is the check that
# catches a misconfigured advertised.listeners, which is the failure everyone hits and nobody
# diagnoses quickly.
echo
echo "kafka round trip"
docker exec dh-kafka bash -c \
  'echo "smoke-$$" | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic audit.events >/dev/null 2>&1'
got=$(docker exec dh-kafka timeout 20 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic audit.events --from-beginning --max-messages 1 2>/dev/null | head -1)
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
