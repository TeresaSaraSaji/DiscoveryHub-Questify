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
- **Disposition**: the rows go inside the sweep's transaction and the blobs are removed by
  `AttachmentStore.deleteAfterCommit(...)` once it commits (best-effort, so a stuck blob store never
  blocks deletion).
- **Manual delete** (`DELETE /messages/{id}`): the demonstrable legal-hold guard, implemented in
  `MessageDeletionService`. A held message is rejected with `409 CONFLICT`; a non-held one has its
  rows and blobs removed (same path as disposition). The hold check fails closed — if P4 is
  unreachable, the delete is refused.

### Rows first, blobs after the commit

Both delete paths remove the metadata rows inside the transaction and only destroy bytes once that
transaction has committed. This ordering is not cosmetic:

- Deleting bytes first means a rollback restores rows that point at files which no longer exist.
  The archive then claims an attachment it cannot produce, and there is no way back. Both paths
  originally did this, and it was caught end to end: the disposition sweep runs as a *single*
  transaction, so a failure on the 50th message would have stranded the 49 before it.
- Deferring to after the commit inverts the failure. A rollback leaves the bytes intact, and a blob
  deletion that fails after the commit merely orphans them. An orphan wastes disk and can be
  reconciled; a missing blob behind a live row cannot be undone.

`MessageDeletionService` exists as a separate `@Transactional` bean for the same reason: the
repository calls it makes (`deleteByMessageId`, `delete`) require a transaction, and a controller
calling them directly throws `TransactionRequiredException` *after* the blob deletion has already
happened.

## Resiliency

The Kafka consumer (`messages.ingested`) is configured so a Storage or database outage does not
lose messages:

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

Demo: stop the Storage service (or its DB); produce messages to Kafka; they are retained by the
broker. Start Storage again — the consumer seeks back to the uncommitted offsets and processes the
pending messages in order, none lost.

## Configuration

```yaml
discoveryhub:
  archive:
    storage:
      local:
        base-dir: ${ATTACHMENT_DIR:./data/attachments}   # primary on-disk store
      s3:
        enabled: ${S3_ENABLED:false}                     # flip to true to also offload to S3
        endpoint: ${S3_ENDPOINT:http://localhost:9000}   # empty = real AWS S3; set for MinIO
        region: ${S3_REGION:us-east-1}
        bucket: ${S3_BUCKET:discoveryhub-archive}
        access-key: ${S3_ACCESS_KEY:minioadmin}          # empty = default AWS provider chain
        secret-key: ${S3_SECRET_KEY:minioadmin}
        path-style-access: ${S3_PATH_STYLE:true}         # required for MinIO
        key-prefix: attachments/
```

When `s3.enabled=false` no `S3Client` bean is created and no S3 connection is opened — an S3-less
deployment pays no S3 cost. When enabled, every attachment is written to **both** local disk (live)
and S3 (after-use offload).

### Deployment requirement: the attachment directory must be a volume

The Dockerfile creates and declares `/app/data/attachments` (`ATTACHMENT_DIR`), but a `VOLUME`
declaration alone gives you an anonymous volume. Whatever runs the image **must** mount a named
volume or host path there. Without one the bytes live in the container's writable layer and are
destroyed with the container, while the metadata rows survive and still claim the attachment
exists — a durability hole that only shows up on the next `docker compose down`.

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

## Database Migrations

- `V1__baseline.sql`
- `V2__messages.sql`
- `V3__disposition.sql`
- `V4__attachment_storage.sql` — adds `storage_location` / `s3_key` / `s3_bucket`, drops `NOT NULL`
  on `content`
- `V5__backfill_attachment_bytes.java` — moves the bytes of any pre-existing attachment out of
  `content` and onto blob storage

`V4` is schema-only, and on its own it is a **breaking change for any populated archive**: it adds
`storage_location NOT NULL DEFAULT ''`, so every row that already existed points at nothing and
`GET /messages/{id}/attachments/{attId}` returns 500 while the bytes sit unreachable in `content`.
That is not hypothetical — applying V4 alone to the dev archive turned all 1,305 existing
attachments into 500s.

`V5` is what makes the upgrade safe. It is a Flyway Java migration (a Spring bean, so it can use
`AttachmentStore`) and runs before the context is up, so no request ever observes the half-migrated
state. It is:

- **Verifying** — the `content` column is only cleared for a row whose on-disk copy has been read
  back and re-hashed to the `sha256` already on the row. Until that passes, the database copy is
  the only surviving original, so discarding it on the strength of a write that returned without
  error would put the chain of custody (FR-6.5) on trust rather than evidence. A row that fails
  verification (or has no `sha256` to verify against) keeps its bytes and its empty
  `storage_location`, and the migration fails so someone looks.
- **Batched and resumable** — non-transactional, committing every 200 rows, paging on a keyset
  cursor over the primary key. The cursor matters: paging on `storage_location = ''` alone re-reads
  an unmovable row forever, so a single bad attachment would hang startup instead of failing it.
- **Idempotent** — a fresh install logs "nothing to move" and does nothing; a rerun after a partial
  run picks up where it stopped (after `flyway repair` clears the failed marker).

The `content` column itself is deliberately left in place. Dropping it would destroy the bytes in
any archive that has not been backfilled, with no way back; retiring it is a separate migration to
run once every environment is on V5.

## Build

The module inherits the repository parent POM and depends on the `contracts` module, so it builds
from the repository root:

```
mvn -pl contracts,services/storage-service -am package
```

## Tests

`mvn -pl contracts,services/storage-service -am test` — 67 tests.

- `MessageMapperTest` — wire ↔ entity mapping, sha256 anchoring
- `ArchiveServiceTest` — idempotent store / dedupe (FR-1.6)
- `MessageControllerTest` — status mapping for the delete outcomes
- `MessageDeletionServiceTest` — the hold guard: 409 on held, fail-closed when P4 is unreachable,
  and the row-before-blob ordering
- `DispositionServiceTest` — fail-closed disposition around the hold check (FR-4.2, FR-5.2)
- `LocalBlobStorageTest` — real filesystem round trips: deterministic paths, overwrite on
  redelivery, path-traversal refusal, empty directory pruning
- `AttachmentStoreTest` — local-primary/S3-best-effort semantics, read fallback, and the
  after-commit deletion behaviour including the rollback case
- `V5BackfillAttachmentBytesTest` — the V4 → V5 upgrade on a populated archive, against H2: batching,
  idempotency, sha256 verification, and termination when rows cannot be moved

### End-to-end verification

The unit tests do not cover the wiring, and two of the bugs above were only visible with a real
database (a mocked repository needs no transaction). Against `docker compose up -d`:

```
# ingest
docker exec -i discoveryhub-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:19092 --topic messages.ingested < message.json
```

What was verified this way, all passing:

| Path | Check |
| --- | --- |
| Ingest | rows in Postgres, blobs on disk, `content` NULL, sha256 derived when the source omits it |
| Read | `GET` returns the archived shape (no `contentBase64`), attachment bytes byte-identical |
| Publish | `messages.archived` produced after the transaction commits |
| Idempotency | redelivering the same record leaves 1 row, 2 attachments, no orphan blob |
| Legal hold | overlapping holds via `holds.events` (count 2 → release one → still held), 409 throughout |
| Fail closed | P4 unreachable → `DELETE` refused with 409, nothing removed |
| Delete | 204, rows gone, blobs gone, message directory pruned, subsequent `GET` 404 |
| S3 offload | objects in MinIO, `s3_key`/`s3_bucket` recorded, removed again on delete |
| S3 fallback | local files deleted out from under the rows → reads served from S3, bytes identical |
| Resiliency | DB stopped, 5 records produced, consumer parked with lag 5 and no commit; DB back → all 5 stored with blobs, lag 0 |
| Malformed input | unparseable JSON skipped, consumer keeps working, next good record lands |
| Disposition | eligible message removed (rows + disk + S3), held message untouched |
| Scale | 9,431-message sweep left 2 rows / 2 files / 2 objects — no orphans, no missing blobs |
| Migration | 1,305 pre-existing attachments relocated and sha256-verified in <1s |

## Deleting rows without going through this service orphans blobs

Now that bytes live outside the database, `DELETE FROM messages` is no longer a complete deletion.
The `ON DELETE CASCADE` on `fk_attachments_message` removes the attachment rows but knows nothing
about the files, so any path that reaches this schema directly — a psql session, or another service
holding a connection to the archive database — removes the metadata and **leaves the blobs behind
forever**.

Blob cleanup only happens through `AttachmentStore`: the disposition sweep, or
`DELETE /messages/{id}`. If a future service is given direct delete access to this schema, it needs
either its own blob cleanup or a reconciliation sweep that removes files with no matching row.

## Storage Service — Compulsory Checkpoints

Where each checkpoint is satisfied:

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
   (`messages.archived`) and its REST API, never its tables — which now also protects the blobs,
   see the section above.
8. **Legal-hold support, including overlapping holds** — `on_hold` boolean + `hold_count` integer;
   placing a hold increments, releasing decrements, `onHold` is true while the count > 0, so
   overlapping holds (FR-4.5) are correct. Mirrored from P4 by `HoldsEventListener`.
   (`domain/MessageEntity.java`, `ingest/HoldsEventListener.java`)
9. **Reject modification/deletion of held messages** — `DELETE /messages/{id}` returns `409
   CONFLICT` for a held message and fails closed if P4 is unreachable; the disposition sweep skips
   held rows. There is no update endpoint by design (write-once). Proven by `MessageControllerTest`.
   (`api/MessageController.java`, `retention/DispositionService.java`)
10. **Make messages available to Search** — after a successful store, `messages.archived` is
    published to Kafka (after the transaction commits), which the Search service consumes to index
    — the Ingestion → Kafka → Storage → (publish) → Search indexing pipeline.
    (`messaging/ArchiveKafkaPublisher.java`)
11. **Handle the required corpus size** — 10k+ messages, 20+ custodians, 5%+ attachments:
    indexed `custodian_id` / `sent_at` / `thread_id`; a partial index on held rows for cheap
    disposition skips; batch inserts (`jdbc.batch_size: 50`, `order_inserts: true`); and — most
    importantly — attachment bytes live on disk / S3, not in the database, so the corpus does not
    bloat Postgres. (`V2__messages.sql`, `application.yml`)
