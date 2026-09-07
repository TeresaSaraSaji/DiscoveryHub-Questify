# Ingestion — P1

Accepts communications, dedupes them, and publishes them. It does **not** store them: FR-1.4
requires that the component accepting messages is not the component storing them, and keeping P1
stateless means an Archive outage queues in Kafka instead of losing data (NFR-2).

```
Ingestion/
├── contracts/               shared wire types — the frozen team contract
│   └── src/main/java/com/discoveryhub/contracts/
│       ├── Message.java         the message wire format
│       ├── Attachment.java      attachment metadata + ingestion-path bytes
│       ├── MessageType.java     EMAIL | CHAT
│       ├── Custodian.java       roster entry
│       ├── AuditEvent.java      chain-of-custody record (FR-7)
│       ├── Ids.java             deterministic id derivation
│       └── Topics.java          Kafka topic names
└── service/                 the P1 Spring Boot service
    └── src/main/java/com/discoveryhub/ingestion/
        ├── IngestionApplication.java
        ├── IngestionConfig.java      Clock + narrowed KafkaTemplate bean
        ├── api/
        │   ├── IngestController.java     POST /messages
        │   ├── IngestResponse.java       batch totals
        │   └── IngestResult.java         per-message outcome
        └── service/
            ├── IngestService.java        validate → derive ids → dedupe → publish → audit
            ├── MessageIds.java           assigns messageId / attachmentIds from externalId
            ├── DedupeStore.java          the dedupe abstraction
            ├── RedisDedupeStore.java     Redis SETNX, fails open
            ├── EventPublisher.java
            └── KafkaEventPublisher.java  messages.ingested + audit.events
```

`contracts/` is shared by all five services and the corpus generator. It lives here for
convenience, but **it is not P1's to change unilaterally** — renaming or retyping a field is a
breaking change that needs all five owners. See `../message-schema.md`.

## How it behaves

`POST /messages` takes a JSON **array**. A batch is not atomic: each message is accepted, deduped
or rejected on its own, and the response reports per-item outcomes plus totals.

```json
{ "accepted": 2, "duplicates": 0, "rejected": 1, "failed": 0,
  "results": [ { "externalId": "...", "messageId": "...", "outcome": "ACCEPTED" } ] }
```

| Outcome | Meaning | HTTP |
|---|---|---|
| `ACCEPTED` | Published to `messages.ingested` | 200 |
| `DUPLICATE` | `externalId` already seen. Dropped, audited, **not an error** | 200 |
| `REJECTED` | Failed validation, with a `reason` | 200 |
| `FAILED` | Infrastructure failure; retry the message | 503 |

Design points worth knowing:

- **Two identifiers.** `externalId` is the source system's key and the only thing dedupe operates
  on. `messageId` is ours, derived deterministically from `externalId`, and is what every other
  service references. Clients cannot choose it — P1 overwrites it.
- **Dedupe is claim-then-publish.** If publishing fails after a claim, the claim is **released**,
  otherwise a transient broker error would leave the id marked as seen and silently drop the
  retry.
- **Redis is the fast path, not the guarantee.** P2 holds a unique constraint on `externalId`, so
  `RedisDedupeStore` fails *open* on a Redis outage: slower dedupe rather than refused traffic.
- **The same conversation from two mailboxes is not a duplicate.** Two `externalId`s, identical
  body. Both are stored so a hold on one custodian preserves their copy independently.
- **Audit is fire-and-forget.** A failure to publish an audit event never fails an ingestion that
  already succeeded; events buffer in Kafka and P5 catches up.

Because the shared `AuditEvent.Outcome` enum has no `DUPLICATE` value, a deduped and a rejected
message are both `REFUSED` and are told apart by `action` (`message.deduped` vs
`message.rejected`).

## Running it

Needs Kafka and Redis. The compose file is **not in this folder** — it is at
`infra/docker-compose.yml` on the `feat/contracts-corpus-infra` branch of the git repo. If
`docker ps` already shows `dh-kafka` and `dh-redis`, you're set.

```bash
cd ..                      # ~/Desktop/DiscoveryHub
mvn -DskipTests package
java -jar Ingestion/service/target/ingestion-0.1.0-SNAPSHOT.jar
curl -s localhost:8081/actuator/health
```

Configuration is in `service/src/main/resources/application.yml`, overridable by environment:
`SERVER_PORT` (8081), `KAFKA_BOOTSTRAP` (localhost:9092), `REDIS_HOST`, `REDIS_PORT`.

### Load the corpus through it

```bash
java -jar Corpus/corpus-generator/target/corpus-generator.jar \
  --post http://localhost:8081/messages --batch-size 250
```

Expect 12,025 sent and 12,000 accepted with 25 duplicates. That gap is FR-1.6 working.

### Confirm it reached Kafka, not just returned 200

```bash
docker exec dh-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic messages.ingested \
  --from-beginning --max-messages 3 --timeout-ms 6000
```

`audit.events` carries `message.ingested`, `message.deduped`, `message.rejected`. There is also a
Kafka UI on http://localhost:8090.

To clear dedupe state and replay from scratch:

```bash
docker exec dh-redis redis-cli --scan --pattern 'dh:ingest:extid:*' \
  | xargs -r -n1 docker exec dh-redis redis-cli del
```

## Tests

```bash
cd .. && mvn -pl Ingestion/contracts,Ingestion/service test
```

12 tests: `IdsTest` pins deterministic id derivation, `IngestServiceTest` covers dedupe, the
two-mailbox case, per-item rejection, the CHAT/EMAIL subject asymmetry, and claim release on
publish failure. One test injects a broker failure and **logs a `broker down` stack trace on
purpose** — read the `Tests run:` line, not the stack trace.

These are unit tests against in-memory fakes, so they stay green even if the Kafka wiring is
broken. The real path is only covered by the manual checks above; Testcontainers would close that
gap.

## Known gaps

- `sha256` on attachments is accepted on trust — P1 does not recompute it from `contentBase64`,
  even though FR-6.5 anchors the whole chain of custody on that value.
- Batch size is unbounded; a large array is held in memory.
- No Dockerfile, so NFR-4 (single-command startup) is not met yet.
- No JaCoCo, so there is no coverage report for deliverable 3.
- Dedupe keys carry a 7-day TTL, so re-loading the same corpus after a week re-publishes it. P2's
  unique constraint is what catches that.
