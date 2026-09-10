# P5 Evidence Export & Audit

## Overview

P5 has two responsibilities that share one owner and one datastore (`postgres-audit`):

1. **Chain of custody (FR-7).** Every other service publishes `audit.events`; P5 is the only
   consumer and the only durable store. It is insert-only — there is no update or delete endpoint
   anywhere in this service, which is the entire enforcement mechanism for FR-7.3.
2. **Evidence export (FR-6).** Builds a checksum-verifiable package of messages and attachments,
   asynchronously, from what P2 hands back — P5 stores nothing of its own until a package exists.

## Why one service

Both halves read `audit.events`/write to the `audit` Postgres instance and both are about proving
what happened after the fact, which is a coherent single ownership boundary even though FR-6 and
FR-7 are numbered as separate requirements.

## Main packages

- `api` — REST controllers (`AuditController`, `ExportController`)
- `audit` — the `audit.events` consumer (`AuditEventListener`)
- `config` — MinIO, RestClient (to P2) and configuration properties
- `domain` — JPA entities (`AuditLogEntity`, `ExportJobEntity`)
- `messaging` — Kafka publishing (`export.jobs`, `audit.events`) and the `export.jobs` consumer
- `repository` — Postgres repositories
- `service` — `ExportService` (orchestration), `PackageBuilder`, `ObjectStorageClient`,
  `ExportVerifier`, `ArchiveClient` (reads P2)

## How export scoping works without P4

FR-6.1 describes exporting "a case's evidence items, or a hold's full scope." P4 (Case & Legal
Hold) does not exist yet, so `ExportRequest` accepts either shape directly:

```json
{ "caseId": "demo-case-1", "messageIds": ["<uuid>", "<uuid>"] }
```
```json
{ "caseId": "demo-case-1", "custodianId": "cust-001", "from": "2024-01-01T00:00:00Z", "to": "2024-12-31T23:59:59Z" }
```

Once P4 exists and can resolve a case's evidence items or a hold's scope to a concrete id list,
the natural integration point is P4 calling `POST /exports` with the resolved `messageIds` — no
change needed here.

## Async job lifecycle (FR-6.2)

`POST /exports` does the minimum possible amount of synchronous work: validate, persist a
`QUEUED` row, publish the job id to `export.jobs`, return 202 with a `Location` header. The actual
build — resolving scope, fetching every message and attachment from P2, zipping, checksumming,
uploading — happens on the `export.jobs` consumer thread (`ExportJobListener` → `ExportService.process`),
off the request that created the job. `GET /exports/{jobId}` polls status:
`QUEUED → RUNNING → COMPLETED` / `FAILED`.

`process` is idempotent against redelivery: a job already past `QUEUED` is left alone, so a
duplicate `export.jobs` record from a rebalance cannot rebuild or clobber a package that already
exists.

## Never a partial package (FR-6.6)

The packages bucket only gains an object once the whole package has been built successfully in
memory (`PackageBuilder.build`) *and* copied server-side from `export-staging` to
`export-packages` (`ObjectStorageClient.promote`). If anything fails before that copy — a
message vanished from the archive, an attachment's bytes no longer match its recorded `sha256`,
MinIO is unreachable — the job is marked `FAILED`, any staged object is discarded, and nothing
ever appears under the job's key in `export-packages`. `POST /exports/{jobId}/retry` is only
accepted for a `FAILED` job and starts the whole build over; it can never produce a duplicate or
corrupt package because there is nothing to conflict with until the retry itself succeeds.

## Checksums (FR-6.3, FR-6.5)

Every attachment's bytes are re-fetched from P2 and re-hashed — never trusted from the archive's
own metadata — and the export refuses outright if the fetched bytes don't match the `sha256` P2
recorded, because that is the chain of custody breaking. `manifest.json` inside the package lists
every message and attachment with the checksum computed from the exact bytes written into the
package. The package-level checksum (the whole zip, including the manifest) is reported by the
job status API and by `GET /exports/{jobId}/verify`, which re-downloads the package, re-derives
every checksum from its own bytes with no call back to P2, and reports any mismatch — this is what
makes tampering detectable rather than merely claimed to be detectable.

## Running it

Needs Kafka, MinIO and Postgres (`postgres-audit`, port 5435), plus a reachable P2 to export from.

```bash
mvn -pl contracts,services/export-service -am -DskipTests package
java -jar services/export-service/target/export-service-0.1.0-SNAPSHOT.jar
curl -s localhost:8085/actuator/health
```

Try it end to end once P1/P2 have some corpus loaded:

```bash
CUSTODIAN=cust-001
JOB=$(curl -s -X POST localhost:8085/exports -H 'Content-Type: application/json' \
  -d "{\"caseId\":\"demo\",\"custodianId\":\"$CUSTODIAN\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["jobId"])')
sleep 3
curl -s localhost:8085/exports/$JOB | python3 -m json.tool
curl -s localhost:8085/exports/$JOB/download | python3 -m json.tool
curl -s localhost:8085/exports/$JOB/verify | python3 -m json.tool
```

The audit trail for everything above (its own `export.requested`/`completed`/`downloaded`, plus
anything P1/P2 published earlier) is queryable at:

```bash
curl -s "localhost:8085/audit?action=export.completed" | python3 -m json.tool
```

## Tests

```bash
mvn -pl contracts,services/export-service -am test
```
