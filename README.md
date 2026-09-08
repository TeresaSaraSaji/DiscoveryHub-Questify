# DiscoveryHub-Questify

A collaborative discovery hub application built by team Questify.

An eDiscovery system: ingest and archive communications, search them, place legal holds, export
defensible evidence packages, and keep an append-only chain of custody.

## Getting started

```bash
git clone git@github.com:TeresaSaraSaji/DiscoveryHub-Questify.git
cd DiscoveryHub-Questify
docker compose up -d
./infra/smoke-test.sh
```

`smoke-test.sh` checks every datastore is reachable **from the host**, which is where your service
runs when you start it from your IDE. A container reporting healthy only proves it can talk to
itself. Run it before blaming your own code.

## Ports

| Port | What | Owner |
|---|---|---|
| 9092 | Kafka | all |
| 8090 | Kafka UI — browse topics and messages | all |
| 6379 | Redis — dedupe keys | P1 |
| 5433 | PostgreSQL `archive` / `archive` / `archive` | P2 |
| 5434 | PostgreSQL `cases` / `cases` / `cases` | P4 |
| 5435 | PostgreSQL `audit` / `audit` / `audit` | P5 |
| 27017 | MongoDB | unclaimed |
| 9200 | Elasticsearch | P3 |
| 9000 | MinIO API (`minioadmin` / `minioadmin`) | P2, P5 |
| 9001 | MinIO console | — |

Application ports: **8081** P1, **8082** P2, **8083** P3, **8084** P4, **8085** P5, **4200** frontend.

## Conventions

**One datastore, one owner (NFR-1).** The three PostgreSQL instances are separate containers, not
three schemas in one database. There is no connection string that lets one service read another's
tables, so the isolation is enforced by infrastructure rather than by everyone remembering. If you
need data another service owns, call its API or consume its events.

**Topic auto-create is off.** A typo in a topic name fails loudly instead of quietly creating a
one-partition topic nothing produces to. New topics go in `infra/kafka/create-topics.sh` so
everyone gets them.

**Schema comes from migrations, never from Hibernate.** Keep `ddl-auto` at `validate`.

## Things that will bite you

**Homebrew Kafka on port 9092.** If you have one, `brew services stop kafka`. Two brokers means
your producer succeeds, your consumer receives nothing, and both configs look correct — because
they are, just to different brokers.

**Kafka advertises two listeners.** `kafka:19092` for containers, `localhost:9092` for services
you run from your IDE. Use `localhost:9092` in application config. A single listener advertising
`localhost` works from the host and silently breaks every containerised client, including Kafka UI.

**Elasticsearch is pinned to 9.4.x** to match the client Spring Boot 4.1.1 ships (9.4.5). The
Elasticsearch Java client enforces a server version check, so an 8.x server rejects a 9.x client.
Don't downgrade the server without downgrading Boot.

**Redis is required for ingestion to actually dedupe.** P1's dedupe store fails *open*: without
Redis it still starts, health still reports UP, and every duplicate passes through undetected.
Silent, not loud. Don't run P1 without it.

**`down -v` deletes your data.** Plain `down` keeps the volumes. Reload the corpus after a `-v`.

## Resetting

```bash
docker compose down            # stop, keep data
docker compose down -v         # stop, wipe everything
docker compose restart kafka   # bounce one service
docker compose logs -f kafka   # follow logs
```
