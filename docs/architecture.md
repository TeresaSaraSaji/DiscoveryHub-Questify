# DiscoveryHub — Architecture

Container-level view (C4 level 2). One persona, five independently deployable backend
services, one frontend, asynchronous messaging between services, and a private datastore
per service.

## Container Diagram

```mermaid
flowchart TB
    actor["Investigator / Compliance Officer<br/><i>single persona, no login</i>"]

    subgraph FE["Frontend"]
        ui["DiscoveryHub Web App<br/><b>Angular</b><br/>dashboard, search, case detail,<br/>holds, export jobs, audit viewer"]
    end

    subgraph SVC["Backend Services — independently deployable"]
        p1["<b>P1 Ingestion</b><br/>Spring Boot<br/>accepts messages, dedupes,<br/>publishes to broker<br/><i>stateless</i>"]
        p2["<b>P2 Archive</b><br/>Spring Boot<br/>system of record for messages<br/>+ retention &amp; disposition job"]
        p3["<b>P3 Search</b><br/>Spring Boot<br/>full-text query, filters,<br/>highlighting, pagination"]
        p4["<b>P4 Case &amp; Hold</b><br/>Spring Boot<br/>case lifecycle, custodians,<br/>evidence, hold propagation"]
        p5["<b>P5 Evidence &amp; Audit</b><br/>Spring Boot<br/>async export jobs, manifests,<br/>checksums, append-only audit"]
    end

    subgraph BUS["Asynchronous Messaging"]
        kafka{{"<b>Kafka</b><br/>messages.ingested<br/>messages.archived<br/>holds.commands · holds.events<br/>export.jobs · audit.events"}}
    end

    subgraph DATA["Data Stores — one owner each, no shared schemas (NFR-1)"]
        redis[("Redis<br/><i>P1 dedupe keys</i>")]
        pg2[("PostgreSQL<br/><i>P2 messages,<br/>retention policy</i>")]
        blob2[("MinIO / S3<br/><i>P2 attachments<br/>+ message bodies</i>")]
        es[("Elasticsearch<br/><i>P3 message index</i>")]
        pg4[("PostgreSQL<br/><i>P4 cases, custodians,<br/>evidence, holds,<br/>saved searches</i>")]
        pg5[("PostgreSQL<br/><i>P5 append-only audit,<br/>export job state</i>")]
        blob5[("MinIO / S3<br/><i>P5 export packages</i>")]
    end

    subgraph TOOLS["Tools — not runtime services"]
        gen["Corpus Generator<br/>12k messages, 24 custodians,<br/>60/40 email/chat, ≥7% attachments<br/><i>deterministic seed</i>"]
        verify["Export Verifier CLI<br/>re-computes checksums<br/>against manifest"]
    end

    actor -->|HTTPS| ui

    ui -->|REST| p3
    ui -->|REST| p4
    ui -->|REST| p5
    ui -->|"REST: message + attachment fetch"| p2

    gen -->|"POST /messages (batch)"| p1

    p1 -->|dedupe check| redis
    p1 ==>|messages.ingested| kafka
    kafka ==>|consume| p2
    p2 --> pg2
    p2 --> blob2
    p2 ==>|messages.archived| kafka
    kafka ==>|"consume: index message"| p3
    p3 --> es

    p4 --> pg4
    p4 ==>|"holds.commands (fan-out)"| kafka
    kafka ==>|"consume: propagate hold"| p4
    p4 ==>|holds.events| kafka
    kafka ==>|"consume: update on-hold flag"| p3
    kafka ==>|"consume: enforce hold on delete"| p2

    p2 -.->|"GET /holds/check — disposition guard"| p4
    p4 -.->|"POST /search/scope — resolve hold scope"| p3

    ui -->|"POST /exports"| p5
    p5 ==>|export.jobs| kafka
    kafka ==>|"consume: run export worker"| p5
    p5 -.->|"fetch messages + attachments"| p2
    p5 -.->|"fetch evidence items"| p4
    p5 --> pg5
    p5 --> blob5
    verify -.->|"download package"| blob5

    p1 ==>|audit.events| kafka
    p2 ==>|audit.events| kafka
    p3 ==>|audit.events| kafka
    p4 ==>|audit.events| kafka
    kafka ==>|"consume all: append-only write"| p5

    classDef svc fill:#dbeafe,stroke:#1e40af,color:#0b1f4b
    classDef store fill:#ede9fe,stroke:#5b21b6,color:#2b0f5b
    classDef bus fill:#fef3c7,stroke:#b45309,color:#4a2c00
    classDef front fill:#dcfce7,stroke:#15803d,color:#052e16
    classDef tool fill:#f1f5f9,stroke:#64748b,color:#0f172a
    classDef person fill:#fee2e2,stroke:#b91c1c,color:#450a0a

    class p1,p2,p3,p4,p5 svc
    class redis,pg2,blob2,es,pg4,pg5,blob5 store
    class kafka bus
    class ui front
    class gen,verify tool
    class actor person
```

Legend: solid bold arrows (`==>`) are asynchronous Kafka flows, thin solid arrows are
synchronous REST calls, dashed arrows are synchronous service-to-service REST calls.

## Service Ownership

| Service | Owner | Functional requirements | Private data |
|---|---|---|---|
| P1 Ingestion | A | FR-1.1, FR-1.4, FR-1.6 | Redis dedupe keys |
| P2 Archive | A | FR-1.5, FR-1.7, FR-5 | PostgreSQL + object store |
| P3 Search | B | FR-3 | Elasticsearch |
| P4 Case & Hold | C | FR-2, FR-4 | PostgreSQL |
| P5 Evidence & Audit | D | FR-6, FR-7 | PostgreSQL + object store |
| Frontend shell, API clients, infra, CI | E | FR-8 | — |
| Corpus generator, export verifier | A / D | FR-1.2, FR-1.3, FR-6.5 | — |

## Kafka Topics

| Topic | Producer | Consumers | Purpose |
|---|---|---|---|
| `messages.ingested` | P1 | P2 | Decouples accept from store (FR-1.4) |
| `messages.archived` | P2 | P3 | Index within 30s of ingestion (FR-1.7) |
| `holds.commands` | P4 | P4 workers | Async fan-out over large hold scopes (FR-4.3) |
| `holds.events` | P4 | P3, P2 | Propagate hold status to index and delete guard (FR-4.2) |
| `export.jobs` | P5 | P5 workers | Async export with retry safety (FR-6.2, FR-6.6) |
| `audit.events` | P1, P2, P3, P4 | P5 | Chain of custody from every service (FR-7.1) |

## Key Design Decisions

1. **Ingestion is stateless; Archive is the system of record.** P1 only validates, dedupes,
   and publishes. This satisfies FR-1.4 literally — the component accepting messages is not
   the one storing them — and means an Archive outage cannot lose messages, they queue in
   Kafka and drain on recovery (NFR-2).
2. **Idempotency is enforced twice.** A fast Redis check in P1 on `externalId`, plus a unique
   constraint on `externalId` in P2. Redis is an optimisation; the database constraint is the
   guarantee, so a Redis flush cannot create duplicates (FR-1.6).
3. **Hold status is duplicated into the search index, not joined at query time.** P3 keeps an
   `onHold` flag updated from `holds.events`. This keeps the on-hold filter (FR-3.2) inside a
   single Elasticsearch query and holds P3 to the 2-second budget (NFR-3), at the cost of brief
   eventual consistency after a hold is placed.
4. **The disposition guard is a synchronous call, not the cached flag.** P2's retention job
   calls `GET /holds/check` on P4 before deleting. Deletion of held data is the one place where
   eventual consistency is unacceptable — a stale flag would destroy evidence (FR-4.2, FR-5.2).
   If P4 is unreachable, the job fails closed and deletes nothing.
5. **Audit is write-only downstream of Kafka.** P5 exposes no update or delete path, and every
   service emits its own audit events rather than P5 inferring them. Append-only is enforced at
   the API surface and by table permissions (FR-7.3).
6. **Export packages are built in a temporary location and moved on completion.** A failed job
   never leaves a partial package in the download path, so retries cannot produce corrupt or
   duplicate output (FR-6.6).

## Resiliency Notes (NFR-2)

- **P2 down:** ingestion continues, `messages.ingested` accumulates, P2 drains on restart.
  Search and cases remain fully functional; only message-body fetch degrades.
- **P3 down:** search UI shows a degraded banner; ingestion, cases, holds, and export continue.
  The index catches up from `messages.archived` on restart.
- **P4 down:** search and browse continue read-only; hold placement is rejected, and P2's
  disposition job fails closed rather than deleting unverified data.
- **P5 down:** all workflows continue; audit events buffer in Kafka and are consumed on restart.
  No action is silently lost from the chain of custody.
