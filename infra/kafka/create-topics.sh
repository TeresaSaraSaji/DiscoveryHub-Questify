#!/usr/bin/env bash
# Creates the topics the architecture calls for. Broker-side auto-create is disabled, so this is
# the single place topics come into existence — a typo in a consumer's topic name then fails
# loudly instead of quietly creating a one-partition topic nothing produces to.
#
# Idempotent: --if-not-exists means re-running after a restart is a no-op.
set -euo pipefail

BOOTSTRAP="kafka:19092"
CLI="/opt/kafka/bin/kafka-topics.sh"

# topic:partitions
#
# Partition counts are about consumer parallelism, not volume. Six on the ingestion path so P2 and
# P3 can scale out; three on the hold and export paths where per-case ordering matters more than
# throughput.
TOPICS=(
  "messages.ingested:6"
  "messages.archived:6"
  "holds.commands:3"
  "holds.events:3"
  "cases.events:3"
  "export.jobs:3"
  "audit.events:6"
)

# Must match KAFKA_MESSAGE_MAX_BYTES on the broker. A topic silently keeps the 1 MiB default
# otherwise, so the broker accepts the setting, the producer is configured for it, and the write
# still fails — at the topic, which is the last place anyone looks.
MAX_MESSAGE_BYTES=10485760

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
    --replication-factor 1 \
    --config "max.message.bytes=${MAX_MESSAGE_BYTES}"
  # --create is a no-op on an existing topic, so apply the limit separately too. Without this, a
  # topic created before this setting existed keeps the 1 MiB default forever and the failure
  # only appears for whoever first sends a large attachment.
  /opt/kafka/bin/kafka-configs.sh --bootstrap-server "${BOOTSTRAP}" \
    --alter --entity-type topics --entity-name "${name}" \
    --add-config "max.message.bytes=${MAX_MESSAGE_BYTES}" >/dev/null
  echo "  ${name} (${partitions} partitions, max ${MAX_MESSAGE_BYTES} bytes)"
done

echo
echo "topics now present:"
"${CLI}" --bootstrap-server "${BOOTSTRAP}" --list
