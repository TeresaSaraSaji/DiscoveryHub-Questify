# Storage / Archive Service

## Overview

The Archive (storage) service is the **system of record** for message content. It consumes
ingested messages from Kafka, stores them durably, and enforces the legal-hold guard on every
deletion path.

Data is split across two stores, one concern each:

| What | Where |
| --- | --- |
| Message **content** — from/to/subject/body, labels, thread, attachments *including their bytes* | **MongoDB** (`messages` collection, one document per message) |
| Legal-hold state + retention bookkeeping (`on_hold`, `hold_count`, `retention_override_at`) | **PostgreSQL** (`message_hold_status`, one slim row per message) |

The split is deliberate: hold state changes often (every hold placed or released touches it) and
is what the disposition sweep queries; content is write-once and never updated. Keeping hold state
out of the Mongo document means a hold update never has to touch — or race with — the message
content. See `domain/ArchivedMessageDocument` and `domain/MessageHoldStatus`.

Attachment bytes travel inside the Mongo document. The chain-of-custody anchor is the per-
attachment `sha256` (derived at ingest when the source omits it); the bytes never appear in the
read API's message listing, in search results, or in audit events (message-schema.md).

> **History**: earlier revisions kept everything in PostgreSQL (bytes first as `BYTEA`, then on
> local disk with an optional S3/MinIO offload). That design is gone — if you are reading old
> branches or migrations `V2`/`V3`, that is the world they came from. MinIO in the compose stack
> now belongs to P5 (export packages), not P2.

## Flow

1. Kafka `messages.ingested` → `MessageIngestedListener` → `ArchiveService.ingest(...)`.
2. `ArchiveService` writes the **Mongo document first**, then the Postgres hold-status row. The
   order matters: `save` on a Mongo document keyed by `messageId` is an idempotent upsert (same
   id, same deterministic content), so losing a race on the Postgres `UNIQUE(external_id)`
   constraint afterwards leaves nothing incorrect behind. The other order risks a hold-status row
   that claims a message no Mongo document backs.
3. Idempotency (FR-1.6) is two-layered: P1's `message_id_map` is the first line of defence, and
   `UNIQUE(external_id)` on `message_hold_status` is P2's own backstop — `existsByExternalId` is
   the fast path, and a duplicate that races past it is caught by the constraint and recorded as a
   **dedupe outcome, not an error**.
4. On a store, `messages.archived` + an audit event are published, which is how P3 learns to
   index the message.

## Reading / deleting

- **Read** (`GET /messages`, `GET /messages/{id}`, attachment download): served from the Mongo
  document.
- **Manual delete** (`DELETE /messages/{id}`): the demonstrable legal-hold guard (FR-4.6),
  implemented in `MessageDeletionService`. A held message is rejected with `409 CONFLICT`; the
  hold check against P4 **fails closed** — if hold-service is unreachable, the delete is refused.
- **Disposition**: P2.2 owns the sweep and publishes `DeleteCommand`s on `disposition.commands`;
  P2's `DispositionCommandListener` applies each one under the same hold guard and answers on
  `disposition.results` with a `DeleteReceipt`. P2.2 never touches P2's stores directly.

### Postgres row first, Mongo document second

Both delete paths remove the `message_hold_status` row inside the transaction and delete the Mongo
document after. If the Mongo delete throws, the exception rolls the transaction back and the
Postgres row comes back — the two stores are never left disagreeing about whether the message
exists. The reverse order could leave a Postgres row pointing at content Mongo had already lost.

The row is loaded with `findByIdForUpdate`, not plain `findById`: without the lock, a concurrent
`HoldsEventListener` could place a hold on the message between the P4 check returning "not held"
and the delete that follows.

## Legal-hold mirror

`HoldsEventListener` consumes `holds.events` and mirrors hold state onto `message_hold_status` so
the disposition candidate query can skip held rows without a per-message call. The flag is an
optimisation, not the guarantee — deletion still asks P4 synchronously, because a stale flag here
would destroy evidence.

`held=false` is applied as **state, not a decrement**: P4 owns the coverage table and only sends
the release event once *no other active hold* covers the message, so the mirror sets
`on_hold = false, hold_count = 0` outright. A decrementing mirror would drift permanently on any
duplicated or dropped event — too high and a released message is never disposed of, too low and a
held one loses its flag. Applied as state, redeliveries are no-ops and the next event corrects any
drift.

## Resiliency

The Kafka consumer (`messages.ingested`) is configured so a Storage, Mongo, or Postgres outage
does not lose messages:

- `spring.kafka.listener.ack-mode: record` — the offset for a record is committed only after its
  listener returns normally.
- `KafkaConsumerConfig` replaces the default error handler with a `DefaultErrorHandler` using an
  **unbounded fixed backoff** (retry every 3s forever). A transient failure parks the consumer on
  the failing record — it does not commit the offset and does not skip ahead — so Kafka's retained
  records are processed in order once Storage is healthy again. No record is ever silently dropped.
- Expected "bad" records never reach the error handler and are skipped inside the listener:
  malformed JSON (`JacksonException`) is logged and skipped; a `DataIntegrityViolationException`
  from `UNIQUE(external_id)` is caught and recorded as a dedupe (the idempotency guarantee
  working, not a failure).

Demo: stop the Storage service (or a datastore); produce messages to Kafka; they are retained by
the broker. Start Storage again — the consumer seeks back to the uncommitted offsets and processes
the pending messages in order, none lost (NFR-2).

## Configuration

```yaml
spring:
  datasource:            # slim Postgres: hold state + retention bookkeeping only
    url: jdbc:postgresql://localhost:5433/archive
  mongodb:               # message content, attachments included
    uri: ${MONGO_URI:mongodb://localhost:27017/archive}
    database: ${MONGO_DATABASE:archive}
```

**The Mongo prefix is `spring.mongodb`, not `spring.data.mongodb`.** Boot 4.1 moved
`MongoProperties` into its own autoconfigure module and renamed the prefix in the move. The old
prefix still parses as valid YAML and shows up in `/actuator/env` — nothing fails, nothing warns,
the app starts clean and connects — and every write silently lands in the driver's fallback
database (`test`) instead of the configured one. Confirmed against a real container.

## Components

- MongoDB for message content, attachment bytes included (`messages` collection)
- PostgreSQL for hold state + retention bookkeeping (`message_hold_status`), migrated by Flyway
- Kafka for consuming (`messages.ingested`, `holds.events`, `disposition.commands`) and publishing
  (`messages.archived`, `disposition.results`, `audit.events`)

## Main Packages

- `api` — REST controllers (message list/fetch, attachment download, manual delete + hold guard,
  stats)
- `config` — application configuration (archive, retention, REST client, Kafka consumer error
  handler)
- `domain` — the Mongo document, the Postgres entity, and the mapper between wire and stored form
- `ingest` — Kafka listeners (`messages.ingested`, `holds.events`, `disposition.commands`) +
  archive processing
- `messaging` — Kafka publishing and event types
- `repository` — the Mongo repository and the Postgres repository, one each
- `retention` — the hold-check client and the guarded delete

## Database Migrations (Postgres only — Mongo has no schema)

- `V1__baseline.sql`
- `V2__messages.sql` — `message_hold_status`
- `V3__disposition.sql` — *historical*: disposition tables, created when P2 owned the sweep
- `V4__retention_override.sql` — adds `retention_override_at`
- `V6__drop_disposition.sql` — removes V3's tables; the sweep and its ledger moved to P2.2
  (`disposition-service`), which owns its own database

## Build

The module inherits the repository parent POM and depends on the `contracts` module, so it builds
from the repository root:

```
mvn -pl contracts,services/storage-service -am package
```

## Tests

`mvn -pl contracts,services/storage-service -am test`

- `MessageMapperTest` — wire ↔ document/row mapping, sha256 anchoring
- `ArchiveServiceTest` — idempotent store / dedupe (FR-1.6), Mongo-first write order
- `MessageControllerTest` — status mapping for the delete outcomes
- `MessageDeletionServiceTest` — the hold guard: 409 on held, fail-closed when P4 is unreachable,
  row-before-document ordering
- `HoldsEventListenerTest` — the mirror: state-not-decrement, overlapping holds, redelivery no-ops
- `DispositionCommandListenerTest` — P2.2's commands applied under the hold guard, receipts
  answered
- `HoldCheckClientTest` — unreachable P4 means *held*

## Deleting from one store without the other leaves the two disagreeing

`DELETE FROM message_hold_status` in a psql session removes the bookkeeping but leaves the Mongo
document — the archive still serves content the sweep no longer knows about. Deleting the Mongo
document directly does the reverse. Every deletion must go through `MessageDeletionService` or
`DispositionCommandListener`, which are the only two places that keep the stores in step (and the
only two that enforce the hold guard).

Note also that a deletion here does not remove the document from P3's index — Elasticsearch keeps
returning hits for messages the archive no longer holds until the index is rebuilt.

## Storage Service — Compulsory Checkpoints

Where each checkpoint is satisfied:

1. **Receive messages asynchronously (Ingestion ≠ Storage)** — `MessageIngestedListener` is a
   `@KafkaListener(topics = MESSAGES_INGESTED)`. Storage never accepts writes directly; the flow
   is Ingestion → Kafka → Storage → stores. (`ingest/MessageIngestedListener.java`)
2. **Every message stored durably** — the whole message is one Mongo document in the `archive`
   database; the hold-status row lands in Postgres under the same ingest.
   (`ingest/ArchiveService.java`, `domain/ArchivedMessageDocument.java`)
3. **Attachments stored durably** — attachment bytes travel inside the message document, each with
   its `sha256`. (`domain/AttachmentDocument.java`, `domain/MessageMapper.java`)
4. **Message ID unique and immutable** — `messageId` is the document `@Id` and the Postgres PK,
   derived deterministically from `externalId` (`Ids.messageId(...)`); there is no update path —
   an archived message is write-once. (`domain/`, `contracts/Ids.java`)
5. **Idempotency / no duplicates** — `UNIQUE(external_id)` on `message_hold_status` is the
   guarantee; `existsByExternalId` is the fast path; the Mongo-first write order makes the race
   harmless. Re-submitting the same message is a no-op. (`ingest/ArchiveService.java`)
6. **Works when downstream services are temporarily unavailable** — per-record ack + an unbounded
   backoff error handler: an outage parks the consumer on the failing record without committing,
   so Kafka redelivers once Storage recovers. No message lost. (see **Resiliency**;
   `config/KafkaConsumerConfig.java`)
7. **Owns its own datastores, no shared schema** — Storage has its own Mongo database and its own
   Postgres instance with its own Flyway migrations. Other services reach it through Kafka
   (`messages.archived`, `disposition.results`) and its REST API, never its stores (NFR-1).
8. **Legal-hold support, including overlapping holds** — `on_hold` + `hold_count` mirrored from
   P4 by `HoldsEventListener`; releases are applied as state, not decrements, because P4 only
   sends `held=false` once no other active hold covers the message (FR-4.5).
   (`ingest/HoldsEventListener.java`)
9. **Reject modification/deletion of held messages** — `DELETE /messages/{id}` returns `409
   CONFLICT` for a held message and fails closed if P4 is unreachable; disposition commands are
   refused the same way and the refusal goes into P2.2's ledger via the `DeleteReceipt`. There is
   no update endpoint by design (write-once). (`retention/MessageDeletionService.java`,
   `ingest/DispositionCommandListener.java`)
10. **Make messages available to Search** — after a successful store, `messages.archived` is
    published, which P3 consumes to index — target under 30 seconds from ingest (FR-1.7).
    (`messaging/ArchiveKafkaPublisher.java`)
11. **Handle the required corpus size** — 10k+ messages, 20+ custodians, 5%+ attachments: content
    scales in Mongo (indexed by `custodianId`), and the slim Postgres row keeps the disposition
    candidate query cheap regardless of message size (NFR-3).
