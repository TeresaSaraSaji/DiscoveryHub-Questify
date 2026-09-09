# DiscoveryHub-Questify

A collaborative discovery hub application built by team Questify.

An eDiscovery system: ingest and archive communications, search them, place legal holds, export
defensible evidence packages, and keep an append-only chain of custody.

## Getting started

```bash
git clone git@github.com:TeresaSaraSaji/DiscoveryHub-Questify.git
cd DiscoveryHub-Questify
docker compose up -d          # Kafka, Redis, Elasticsearch, MinIO, MongoDB, 3x Postgres
./infra/smoke-test.sh         # everything reachable from the host?
mvn -q package                # build every module
java -jar services/ingestion-service/target/ingestion-service-0.1.0-SNAPSHOT.jar
java -jar services/storage-service/target/storage-service-0.1.0-SNAPSHOT.jar
java -jar services/search-service/target/search-service-0.1.0-SNAPSHOT.jar
```

Or run the services in Docker too — one command, nothing installed but Docker:

```bash
docker compose --profile app up -d --build
```

Day to day, prefer the first. Datastores in Docker, services in your IDE, where you can attach a
debugger and a change costs a restart instead of an image rebuild. The profile is there for demos
and for checking the thing works on a clean machine.

`smoke-test.sh` checks every datastore is reachable **from the host**, which is where your service
runs when you start it from your IDE. A container reporting healthy only proves it can talk to
itself. Run it before blaming your own code.

Load the corpus once P1 is up:

```bash
curl -F "file=@tools/corpus-generator/fixtures/messages.ndjson" \
  "http://localhost:8081/messages/upload?async=true"
```

## Layout

```
DiscoveryHub-Questify/
├── docker-compose.yml              the whole local stack, one shared file
├── pom.xml                         parent build
├── contracts/                      shared wire types — a library, not a service
├── services/
│   ├── ingestion-service/          P1  accept, dedupe, publish        :8081
│   │   ├── Dockerfile
│   │   ├── pom.xml
│   │   └── src/
│   ├── storage-service/            P2  system of record              :8082
│   │   ├── Dockerfile
│   │   ├── pom.xml
│   │   └── src/
│   ├── search-service/             P3  index, full-text search        :8083
│   │   ├── Dockerfile
│   │   ├── pom.xml
│   │   └── src/
│   ├── case-service/               P4  case lifecycle, custodians, evidence       :8084
│   │   ├── Dockerfile
│   │   ├── pom.xml
│   │   └── src/
│   ├── hold-service/               P4  legal hold, scope resolution, /holds/check :8086
│   │   ├── Dockerfile
│   │   ├── pom.xml
│   │   └── src/
│   ├── export-service/             P5  evidence export, audit trail    :8085
│   │   ├── Dockerfile
│   │   ├── pom.xml
│   │   └── src/
│   └── disposition-service/        P2.2 retention and disposition     :8087
│       ├── Dockerfile
│       ├── pom.xml
│       └── src/
├── frontend/                       Angular UI                        :4200
│   ├── mock/                       P4/P5 contract fixture, dev only
│   └── src/
├── tools/
│   └── corpus-generator/           12,000-message fixture generator
│       └── fixtures/               the committed corpus
├── infra/                          topic and bucket creation, test scripts
├── message-schema.md               the frozen message contract
└── README.md
```

`contracts/` sits outside `services/` deliberately: every service compiles against it, and nothing
deploys it. Put it under `services/` and the next person assumes it runs somewhere.

New services go in `services/<name>-service/` with their own `Dockerfile` and `pom.xml`, and get
added to `<modules>` in the root POM.

Read `message-schema.md` before writing anything that touches a message.

## P1 Ingestion API

Browsable at **http://localhost:8081/swagger-ui.html** once P1 is running.

| | |
|---|---|
| `POST /messages` | JSON array batch. Not atomic — per-item outcomes. |
| `POST /messages/upload` | multipart, JSON array or NDJSON |
| `POST /messages/upload?async=true` | 202 with a job id, for large files |
| `GET /messages/uploads/{jobId}` | poll an async upload |
| `GET /messages/{externalId}/status` | has this id been ingested? |
| `GET /messages/stats` | counts since this instance started |

A duplicate is a **2xx outcome, not an error** — a source system re-sending is normal. Only an
infrastructure failure returns a retryable status.

## P2.2 Disposition

Retention and disposition (FR-5) is its own service on **8086**, with its own database. It owns the
retention policy, the scheduled sweep, and the ledger of what each sweep deleted or skipped.
Details are in `services/disposition-service/DISPOSITION.md`.

It does not write to P2's tables. The sweep publishes a `DeleteCommand` to `disposition.commands`,
P2's `DispositionCommandListener` applies it under P2's own hold guard, and P2 answers on
`disposition.results` with a `DeleteReceipt` that settles P2.2's ledger to what actually happened.
One owner per datastore (NFR-1), and with P2 down the commands queue on the topic and apply when it
returns (NFR-2).

Two things to know before running it:

- **The corpus is mostly past retention.** With the real defaults (seven years for email, three for
  chat), ~500 fixture messages are eligible on any given day. So the scheduled sweep ships
  **disabled** — trigger one with `POST /disposition/runs`, ideally `?dryRun=true` first. Enabling
  the cron on this corpus deletes real fixture data every tick; that is correct FR-5 behaviour and
  still not what you want by accident. The hold check also fails closed, so nothing is deleted
  while P4 is down — do not "fix" that by setting `hold-check.required: false` in committed config.
- **A sweep that skipped everything is not proof that holds work.** Both services fail closed when
  P4 is unreachable, and the result looks identical to holds doing their job: every message
  skipped, refusals throughout the audit trail, no errors anywhere. If a sweep deletes nothing,
  check `CASES_BASE_URL` and P2's logs for `hold check failed` before believing it.

The sweep is bounded by `batch-size` and makes one call to P4 per candidate, so for the UI trigger
it asynchronously and watch it rather than holding the request open (NFR-3):

```bash
curl -X POST "localhost:8086/disposition/runs?async=true"   # 202 + a QUEUED run id
curl -N localhost:8086/disposition/runs/stream              # progress, then the stream closes
```

It needs three endpoints from **P4**, all specified in DISPOSITION.md:

- `GET /holds/check?messageId=` — the shape P2 already assumes, so there is one contract, not two.
- `GET /holds/active` — holds as *scope* (custodians, date range, case) rather than expanded to
  messages. This is what stops a sweep from deleting evidence during the asynchronous hold
  propagation FR-4.3 mandates, when every per-message flag still reads "not held".
- `POST /holds/evidence-check` — of these message ids, which are evidence items in a case that is
  under hold. A hold's scope and a case's contents are different sets: FR-2.4 lets an investigator
  attach any message to a case, including one the hold's own custodian and date scope never
  covered, and deleting it would destroy part of a production someone already selected.

## Frontend

Angular on **4200**. Details in `frontend/README.md`.

```bash
cd frontend && npm install && npm start
```

| Page | Reads |
|---|---|
| Dashboard | every service, for counts and recent activity |
| Search | P3 `:8083` |
| Cases & Holds | P4 case `:8084`, P4 hold `:8086` |
| Retention & Disposition | P2.2 `:8087`, P2 `:8082` |
| Exports & Audit | P5 `:8085` |

**Search shows nothing until you search.** The index holds the whole corpus, and a screen that
opens with 12,000 messages on it has answered a question nobody asked. P3 agrees: it rejects a
query with no criterion at all with a 400.

**It works with any subset of the services running.** Every region of every page issues its own
request and renders its own error and retry, so one service being down costs you that panel and
nothing else. There are no route resolvers. A failed request is never rendered as an empty result —
upstream an unreachable hold-service means *held*, so "no holds" and "could not ask" are opposite
facts and the UI keeps them apart.

Every service allows any port on the loopback host in its own `CorsConfig`, and in
`management.endpoints.web.cors` for actuator, which has its own and does not inherit that one. Both
are governed by `discoveryhub.web.cors.allowed-origins`. Without them a running service and a
correct URL still fail, as a network error with no status — so the UI reports "not reachable" for a
service that is answering curl perfectly. If every panel says that at once, it is CORS, not the
services.

## Ports

| Port | What | Owner |
|---|---|---|
| 4200 | Frontend | Sahithi |
| 8081 | P1 Ingestion | A |
| 8082 | P2 Archive | A |
| 8083 | P3 Search | — |
| 8084 | P4 Case Management | Teresa |
| 8085 | P5 Evidence Export & Audit | E |
| 8086 | P4 Legal Hold | Teresa |
| 8087 | P2.2 Disposition | Saketh |
| 9092 | Kafka | all |
| 8090 | Kafka UI | all |
| 6379 | Redis — dedupe keys | P1 |
| 5433 | PostgreSQL `archive` / `archive` / `archive` | P2 |
| 5434 | PostgreSQL `cases` / `cases` / `cases` | P4 (case) |
| 5435 | PostgreSQL `audit` / `audit` / `audit` | P5 |
| 5436 | PostgreSQL `holds` / `holds` / `holds` | P4 (hold) |
| 5437 | PostgreSQL `disposition` / `disposition` / `disposition` | P2.2 |
| 27017 | MongoDB | unclaimed |
| 9200 | Elasticsearch | P3 |
| 9000 | MinIO API (`minioadmin` / `minioadmin`) | P2, P5 |
| 9001 | MinIO console | — |

Every application port is now claimed.

P4 is two deployables: **case-service** (8084, cases DB) and **hold-service** (8086, holds DB).
The split keeps case and hold as separate bounded contexts with their own datastores (NFR-1),
coordinating via `cases.events` (close → release) and the synchronous `GET /holds/check` endpoint
that P2 calls before deleting anything.

**P2.2 moved to 8087, and its database to host port 5437.** It had 8086/5436 first, but
hold-service claimed both while disposition was still on an unmerged branch. Moving disposition was
the cheaper fix: hold-service is already on main and `storage-service`'s `HOLDS_BASE_URL` points at
8086. Only the host port of `postgres-disposition` changed — inside the compose network it is still
5432, so no service's connection string moved.

## Conventions

**One datastore, one owner (NFR-1).** The three PostgreSQL instances are separate containers, not
three schemas in one database. No connection string lets one service read another's tables. If you
need data another service owns, call its API or consume its events.

**Topic auto-create is off.** A typo fails loudly instead of quietly creating a one-partition topic
nothing produces to. New topics go in `infra/kafka/create-topics.sh` and in `Topics.java`.

**Schema comes from migrations, never from Hibernate.** Keep `ddl-auto` at `validate`.

**`messageId` is derived, never random.** Use `Ids.messageId(externalId)`. Two environments loading
the same corpus must produce the same identifiers, or no fixture is portable.

## Things that have already bitten us

**We do not inherit `spring-boot-starter-parent`,** so its defaults are missing and each one fails
late and looks like something else. Three so far, all now fixed in the root POM: the `repackage`
goal was unbound ("no main manifest attribute"), `spring-boot-flyway` was absent so migrations
silently never ran, and `-parameters` was off so every `@PathVariable` threw a 500 at request time.
Expect more of the same shape.

**Spring Boot 4 splits auto-configuration into per-technology modules.** A library on the classpath
is not enough — check it is actually *doing* something, not merely present.

**Homebrew Kafka on port 9092.** If you have one, `brew services stop kafka`. Two brokers means
your producer succeeds, your consumer receives nothing, and both configs look correct.

**Kafka advertises two listeners:** `kafka:19092` for containers, `localhost:9092` for services run
from your IDE. Use `localhost:9092` in application config.

**Elasticsearch is pinned to 9.4.x** to match the client Boot 4.1.1 ships. The client enforces a
server version check, so an 8.x server rejects it.

**Redis is required for dedupe to do anything.** P1's dedupe store fails *open*: without Redis it
still starts, health still reports UP, and every duplicate passes through. Recreating the container
wipes the keys — that has already put the corpus into Kafka twice.

**`down -v` deletes your data.** Plain `down` keeps the volumes.

## Verifying

```bash
mvn -q package          # builds and runs all tests
./infra/smoke-test.sh   # infrastructure reachable from the host
```
