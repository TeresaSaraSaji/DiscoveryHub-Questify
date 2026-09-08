# Storage / Archive Service

## Overview

The Archive (storage) service is the **system of record**. It consumes ingested messages from
Kafka, stores them durably, and owns retention / disposition / legal-hold handling.

Data is split across three stores:

| What | Where |
| --- | --- |
| Message metadata (from/to/subject/body, hold state, attachments index) | **PostgreSQL** |
| Attachment **bytes** (the primary, live copy served by the read API) | **Local disk** |
| Attachment **bytes** (optional durable "after use" offload copy) | **S3** (real AWS S3 or MinIO) |

The attachment bytes used to live inline in PostgreSQL (`content BYTEA`). They were moved out: the
table keeps metadata, the chain-of-custody `sha256`, and pointers (`storage_location`,
`s3_key`, `s3_bucket`). See `V4__attachment_storage.sql`.

## Flow

1. Kafka `messages.ingested` → `MessageIngestedListener` → `ArchiveService.ingest(...)`.
2. `ArchiveService` maps the wire message to entities; for each attachment it calls
   `AttachmentStore.store(...)`, which **writes the bytes to local disk** and, when S3 is enabled,
   **also writes an offload copy to S3**. The local path and (optional) S3 key/bucket are recorded
   on the attachment row.
3. Message + attachment metadata are saved in PostgreSQL (idempotent via `UNIQUE(external_id)`).
4. On a store, `messages.archived` + an audit event are published (after the transaction commits,
   so a rollback never advertises a message that isn't stored).

## Reading / deleting bytes

- **Read** (`GET /messages/{id}/attachments/{attId}`): served from local disk; if the local file is
  unavailable but an S3 offload copy exists, the S3 copy is served instead (S3 is the durable
  fallback for after the local copy has aged out).
- **Disposition**: before deleting the DB rows, `AttachmentStore.delete(...)` removes the local
  file and the S3 offload object (best-effort, so a stuck blob store never blocks deletion).
- **Manual delete** (`DELETE /messages/{id}`): the demonstrable legal-hold guard. A held message is
  rejected with `409 CONFLICT`; a non-held one has its blobs + rows removed (same path as
  disposition). The hold check fails closed — if P4 is unreachable, the delete is refused.

## Resiliency

The Kafka consumer (`messages.ingested`) is configured so a Storage or database outage does not
lose messages (checkpoint 6):

- `spring.kafka.listener.ack-mode: record` — an exception on one record does not roll back the
  whole batch; the offset for a record is committed only after its listener returns normally.
- `KafkaConsumerConfig` replaces the default error handler with a `DefaultErrorHandler` using an
  **unbounded fixed backoff** (retry every 3s forever). A transient failure parks the consumer on
  the failing record — it does not commit the offset and does not skip ahead — so Kafka's retained
  records are processed in order once Storage is healthy again. No record is ever silently dropped.
- Expected "bad" records never reach the error handler and are skipped inside the listener:
  malformed JSON (`JacksonException`) is logged and skipped (one bad message cannot wedge the
  consumer); a `DataIntegrityViolationException` from the `UNIQUE(external_id)` constraint is
  caught and recorded as a dedupe (the idempotency guarantee working, not a failure).

Demo of the resiliency NFR: stop the Storage service (or its DB); produce messages to Kafka; they
are retained by the broker. Start Storage again — the consumer seeks back to the uncommitted
offsets and processes the pending messages in order, none lost.

## Configuration

```yaml
discoveryhub:
  archive:
    storage:
      local:
        base-dir: ./data/attachments      # primary on-disk store
      s3:
        enabled: false                    # flip to true to also offload to S3
        endpoint: http://localhost:9000    # empty = real AWS S3; set for MinIO / local S3
        region: us-east-1
        bucket: discoveryhub-archive
        access-key: minioadmin             # leave empty to use the default AWS provider chain
        secret-key: minioadmin
        path-style-access: true            # required for MinIO
        key-prefix: attachments/
```

When `s3.enabled=false` no `S3Client` bean is created and no S3 connection is opened — an S3-less
deployment pays no S3 cost. When enabled, every attachment is written to **both** local disk (live)
and S3 (after-use offload).

## Components

- PostgreSQL for message and attachment **metadata** + pointers
- Kafka for ingestion (`messages.ingested`, `holds.events`) and publishing (`messages.archived`,
  `audit.events`)
- Local disk for attachment bytes (primary), optional S3/MinIO for the offload copy
- Flyway for database migrations
- Retention, disposition, and legal-hold handling

## Main Packages

- `api` — REST controllers (message list/fetch, attachment download, manual delete + hold guard,
  stats, disposition history)
- `config` — application configuration (archive, retention, REST client, Kafka consumer error
  handler)
- `domain` — database entities and mapping
- `ingest` — Kafka listeners + archive processing
- `messaging` — Kafka publishing and event types
- `repository` — PostgreSQL repositories
- `retention` — retention and disposition logic
- `storage` — local-disk + S3 attachment byte storage (`BlobStorage`, `LocalBlobStorage`,
  `S3BlobStorage`, `AttachmentStore`, `StorageProperties`, `StorageConfig`)
- `contracts` (inlined) — the frozen wire types this module shares with the rest of the platform
  (`Message`, `Attachment`, `Topics`, `Ids`, `MessageType`, `AuditEvent`)

## Database Migrations

- `V1__baseline.sql`
- `V2__messages.sql`
- `V3__disposition.sql`
- `V4__attachment_storage.sql` — moves attachment bytes out to local disk + optional S3

## Build

The module is **self-contained**: its `pom.xml` inherits from `spring-boot-starter-parent`
(Spring Boot 4.1.1, Java 21) and inlines the small `com.discoveryhub.contracts` types it needs, so
it builds and pushes on its own with no external parent pom or contracts module:

```
cd services/archive
mvn clean package
```

## Tests

- `MessageMapperTest` — wire ↔ entity mapping, sha256 anchoring
- `ArchiveServiceTest` — idempotent store / dedupe (FR-1.6)
- `MessageControllerTest` — hold-guarded delete: 409 on held, fail-closed when P4 unreachable,
  204 + blob/row removal when not held (checkpoint 9)
- `DispositionServiceTest` — fail-closed disposition around the hold check (FR-4.2, FR-5.2)

## Storage Service — Compulsory Checkpoints

Where each checkpoint is satisfied in `services/archive`:

1. **Receive messages asynchronously (Ingestion ≠ Storage)** — `MessageIngestedListener` is a
   `@KafkaListener(topics = MESSAGES_INGESTED)`. Storage never accepts writes directly; the
   Ingestion service is a separate process that publishes to Kafka, Storage consumes. Flow:
   Ingestion → Kafka → Storage → DB. (`ingest/MessageIngestedListener.java`)
2. **Every message stored durably** — message metadata in PostgreSQL (`messages` table,
   `V2__messages.sql`); `ArchiveService.ingest(...)` is `@Transactional`. (`ingest/ArchiveService.java`)
3. **Attachments stored durably** — attachment bytes on local disk (primary) + optional S3 offload;
   metadata + `sha256` in PostgreSQL. (`storage/AttachmentStore.java`, `storage/LocalBlobStorage.java`,
   `storage/S3BlobStorage.java`)
4. **Message ID unique and immutable** — `messageId` is the `@Id` PK, derived deterministically from
   `externalId` (`Ids.messageId(...)`); there is no update path — an archived message is write-once.
   (`domain/MessageEntity.java`, `domain/MessageMapper.java`, `contracts/Ids.java`)
5. **Idempotency / no duplicates** — `UNIQUE(external_id)` constraint is the guarantee; the
   `existsByExternalId` fast path + a `DataIntegrityViolationException` caught as a dedupe cover
   the race. Re-submitting the same message is a no-op. (`V2__messages.sql`,
   `ArchiveService.ingest(...)`, `MessageIngestedListener`)
6. **Works when downstream services are temporarily unavailable** — per-record ack + an unbounded
   backoff error handler: a Storage/DB outage parks the consumer on the failing record without
   committing, so Kafka redelivers pending records once Storage recovers. No message lost. (see
   **Resiliency** above; `config/KafkaConsumerConfig.java`)
7. **Owns its own database, no shared schema** — Storage has its own PostgreSQL database
   (`archive`), its own Flyway migrations, its own tables. Other services reach it through Kafka
   (`messages.archived`) and its REST API, never its tables.
8. **Legal-hold support, including overlapping holds** — `on_hold` boolean + `hold_count` integer;
   placing a hold increments, releasing decrements, `onHold` is true while the count > 0, so
   overlapping holds (FR-4.5) are correct. Mirrored from P4 by `HoldsEventListener`. (FR-4.5)
   (`domain/MessageEntity.java`, `ingest/HoldsEventListener.java`)
9. **Reject modification/deletion of held messages** — `DELETE /messages/{id}` returns `409
   CONFLICT` for a held message and fails closed if P4 is unreachable; the disposition sweep skips
   held rows. There is no update endpoint by design (write-once). Proven by `MessageControllerTest`.
   (`api/MessageController.java`, `retention/DispositionService.java`)
10. **Make messages available to Search** — after a successful store, `messages.archived` is
    published to Kafka (after the transaction commits), which the Search service consumes to index
    — the Ingestion → Kafka → Storage → (publish) → Search indexing pipeline. (`messaging/ArchiveKafkaPublisher.java`)
11. **Handle the required corpus size** — 10k+ messages, 20+ custodians, 5%+ attachments:
    indexed `custodian_id` / `sent_at` / `thread_id`; a partial index on held rows for cheap
    disposition skips; batch inserts (`jdbc.batch_size: 50`, `order_inserts: true`); and — most
    importantly — attachment bytes live on disk / S3, not in the database, so the corpus does not
    bloat Postgres. (`V2__messages.sql`, `application.yml`)
