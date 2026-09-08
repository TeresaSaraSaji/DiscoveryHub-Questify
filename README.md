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
mvn -q package                # build contracts, ingestion, corpus generator
java -jar Ingestion/service/target/ingestion-0.1.0-SNAPSHOT.jar
```

`smoke-test.sh` checks every datastore is reachable **from the host**, which is where your service
runs when you start it from your IDE. A container reporting healthy only proves it can talk to
itself. Run it before blaming your own code.

Load the corpus once P1 is up:

```bash
curl -F "file=@Corpus/fixtures/messages.ndjson" \
  "http://localhost:8081/messages/upload?async=true"
```

## Layout

| Path | What |
|---|---|
| `Ingestion/contracts` | Wire types every service compiles against. Changes here are breaking changes. |
| `Ingestion/service` | **P1** accept, dedupe, publish — port 8081 |
| `Corpus/corpus-generator` | Deterministic 12,000-message fixture generator |
| `Corpus/fixtures` | The committed corpus: `messages.ndjson`, `custodians.json`, `manifest.json` |
| `infra/` | Topic and bucket creation, smoke test |
| `docker-compose.yml` | The whole local stack |
| `message-schema.md` | The frozen message contract, explained |

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

## Ports

| Port | What | Owner |
|---|---|---|
| 8081 | P1 Ingestion | A |
| 9092 | Kafka | all |
| 8090 | Kafka UI | all |
| 6379 | Redis — dedupe keys | P1 |
| 5433 | PostgreSQL `archive` / `archive` / `archive` | P2 |
| 5434 | PostgreSQL `cases` / `cases` / `cases` | P4 |
| 5435 | PostgreSQL `audit` / `audit` / `audit` | P5 |
| 27017 | MongoDB | unclaimed |
| 9200 | Elasticsearch | P3 |
| 9000 | MinIO API (`minioadmin` / `minioadmin`) | P2, P5 |
| 9001 | MinIO console | — |

Remaining application ports: **8082** P2, **8083** P3, **8084** P4, **8085** P5, **4200** frontend.

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
