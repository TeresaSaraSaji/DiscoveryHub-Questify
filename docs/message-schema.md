# Message Schema — frozen

This is the contract for `POST /messages` on P1, for the `messages.ingested` and
`messages.archived` topics, and for what P2 stores. It is defined in code at
`contracts/src/main/java/com/discoveryhub/contracts/Message.java`; this document explains the
parts that are easy to get wrong.

**Changing this is a breaking change.** Additive optional fields are fine. Renaming, retyping, or
removing a field needs agreement from all five owners, because P1, P2, P3, and P5 all read it.

## Message

| Field | Type | Required | Notes |
|---|---|---|---|
| `messageId` | string (UUID) | yes | DiscoveryHub's id. Derived from `externalId`, never generated randomly. |
| `externalId` | string | yes | Source system's id. **The idempotency key.** |
| `source` | string | yes | `EXCHANGE`, `TEAMS`. Where the message was captured from. |
| `type` | enum | yes | `EMAIL` or `CHAT`. |
| `custodianId` | string | yes | Owner of the mailbox this copy came from — *not* the sender. |
| `from` | string | yes | Sender email address. |
| `to` | string[] | yes | May be empty; may contain external addresses. |
| `cc` | string[] | yes | May be empty. |
| `subject` | string | no | Absent on `CHAT`. |
| `body` | string | yes | Plain text. Replies may contain quoted history prefixed with `> `. |
| `sentAt` | string (ISO-8601, UTC) | yes | e.g. `2024-05-11T21:37:00Z`. |
| `threadId` | string (UUID) | yes | Conversation grouping. |
| `inReplyTo` | string (UUID) | no | `messageId` of the parent. Absent on the first message of a thread. |
| `attachments` | Attachment[] | yes | May be empty. |
| `labels` | string[] | yes | May be empty. Currently `PRIVILEGED`, `SENSITIVE`. |

Absent and empty are distinguished on the wire: optional fields are omitted entirely rather than
sent as `null`. Array fields are always present, possibly as `[]`.

## Attachment

| Field | Type | Notes |
|---|---|---|
| `attachmentId` | string (UUID) | Derived from `externalId` + index. |
| `filename` | string | Not unique, not sanitised. Treat as untrusted when building export paths. |
| `contentType` | string | |
| `sizeBytes` | number | Bytes of the decoded content. |
| `sha256` | string (hex) | Chain of custody anchor. |
| `contentBase64` | string | **Ingestion path only.** |

`contentBase64` is dropped once P2 has written the bytes to object storage. It must not appear in
search results, in P2's read API, or in audit events — from that point the bytes live in the blob
store and `sha256` is what everything downstream reasons about. P5 re-computes it when building an
export manifest and the verifier CLI re-computes it again from the delivered package (FR-6.5).

## The two identifiers

`externalId` is the source system's key and is what dedupe operates on. `messageId` is ours and is
what every other service references — cases, holds, search hits, export manifests, audit records.

`messageId` is **derived deterministically** from `externalId` via `Ids.messageId()`
(`UUID.nameUUIDFromBytes("message:" + externalId)`). Nobody generates one with
`UUID.randomUUID()`. If two environments load the same corpus, they must end up with the same
`messageId`s, otherwise fixtures and demo scripts only work on the machine that loaded them first.

## Dedupe: what is and is not a duplicate

A duplicate is **the same `externalId` arriving more than once**. It is dropped, and dropping it is
an audit event, not an error. The API returns 2xx for it — a re-sending source system is a normal
condition, not a client fault.

The same conversation captured from two mailboxes is **not** a duplicate. Two `externalId`s, two
`messageId`s, two `custodianId`s, identical `body`. Both must be stored: a hold on one custodian
must preserve their copy independently of the other's. The corpus contains this case on purpose,
including on the narrative's smoking-gun message, so a dedupe implementation that keys on body
hash or on `from`+`subject`+`sentAt` will fail visibly rather than quietly losing evidence.

Enforcement is in two places (see decision 2 in `architecture.md`): a Redis check in P1 for speed,
a unique constraint on `externalId` in P2 for correctness. The constraint is the guarantee.

## Batching

`POST /messages` accepts a JSON array. A batch is not atomic: each message is accepted or deduped
on its own, and the response reports per-message outcomes. A duplicate in the middle of a batch
must not reject the other 199.
