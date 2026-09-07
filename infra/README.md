# Local Infrastructure

One command, identical stack for all five of us.

```bash
docker compose -f infra/docker-compose.yml up -d
./infra/smoke-test.sh
```

`smoke-test.sh` checks reachability from the host, which is where your service runs when you
start it from your IDE. A container reporting healthy only proves it can talk to itself.

## Ports

| Port | What | Owner |
|---|---|---|
| 9092 | Kafka | all |
| 8090 | Kafka UI — browse topics and messages | all |
| 6379 | Redis — dedupe keys | P1 |
| 5433 | PostgreSQL `archive` / `archive` / `archive` | P2 |
| 5434 | PostgreSQL `cases` / `cases` / `cases` | P4 |
| 5435 | PostgreSQL `audit` / `audit` / `audit` | P5 |
| 9200 | Elasticsearch | P3 |
| 9000 | MinIO API (`minioadmin` / `minioadmin`) | P2, P5 |
| 9001 | MinIO console | — |

Application ports, once the services exist: **8081** P1, **8082** P2, **8083** P3, **8084** P4,
**8085** P5, **4200** frontend.

## Things that will bite you

**Two Kafkas.** If you installed Kafka with Homebrew it holds port 9092 and yours will win the
race. `brew services stop kafka`. Symptom: your producer succeeds, your consumer receives
nothing, and both look correctly configured, because they are — just to different brokers.

**Topic auto-create is off.** A typo in a topic name fails loudly instead of quietly creating a
topic nothing produces to. New topics go in `kafka/create-topics.sh` so everyone gets them.

**Three PostgreSQL instances, not three schemas.** P2, P4, and P5 each get their own container on
their own port with their own credentials (NFR-1). There is no connection string that lets one
service read another's tables, which is the point — the isolation is enforced by the
infrastructure rather than by everyone remembering.

**`down -v` deletes your data.** Plain `down` keeps the volumes. Reload the corpus after a `-v`.

## Resetting

```bash
docker compose -f infra/docker-compose.yml down          # stop, keep data
docker compose -f infra/docker-compose.yml down -v       # stop, wipe everything
docker compose -f infra/docker-compose.yml restart kafka # bounce one service
docker compose -f infra/docker-compose.yml logs -f p2    # follow logs
```

After a `down -v`, bring the stack back up and reload the corpus:

```bash
docker compose -f infra/docker-compose.yml up -d
java -jar tools/corpus-generator/target/corpus-generator.jar --post http://localhost:8081/messages
```
