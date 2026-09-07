#!/usr/bin/env bash
# Creates the topics from docs/architecture.md. Auto-create is disabled on the broker, so this is
# the single place topics come into existence — a typo in a consumer's topic name fails loudly
# instead of quietly creating a topic nothing produces to.
#
# Idempotent: --if-not-exists means re-running after a restart is a no-op.
set -euo pipefail

BOOTSTRAP="kafka:19092"
CLI="/opt/kafka/bin/kafka-topics.sh"

# topic:partitions
#
# Partition counts are about consumer parallelism, not volume. Six on the ingestion path so P2 and
# P3 can scale out; three on the hold and export paths where ordering per case matters more than
# throughput.
TOPICS=(
  "messages.ingested:6"
  "messages.archived:6"
  "holds.commands:3"
  "holds.events:3"
  "export.jobs:3"
  "audit.events:6"
)

echo "waiting for broker at ${BOOTSTRAP}"
until "${CLI}" --bootstrap-server "${BOOTSTRAP}" --list >/dev/null 2>&1; do
  sleep 2
done

for entry in "${TOPICS[@]}"; do
  name="${entry%%:*}"
  partitions="${entry##*:}"
  "${CLI}" --bootstrap-server "${BOOTSTRAP}" \
    --create --if-not-exists \
    --topic "${name}" \
    --partitions "${partitions}" \
    --replication-factor 1
  echo "  ${name} (${partitions} partitions)"
done

echo
echo "topics now present:"
"${CLI}" --bootstrap-server "${BOOTSTRAP}" --list
