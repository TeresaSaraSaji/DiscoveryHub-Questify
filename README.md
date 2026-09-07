# DiscoveryHub-Questify

A collaborative discovery hub application built by team Questify.

An eDiscovery system: ingest and archive communications, search them, place legal holds, export
defensible evidence packages, and keep an append-only chain of custody.

Five independently deployable Spring Boot services, an Angular frontend, Kafka between them, and
a private datastore per service.

## Quickstart

```bash
docker compose -f infra/docker-compose.yml up -d   # Kafka, Redis, ES, MinIO, 3x Postgres
./infra/smoke-test.sh                              # everything reachable from the host?
mvn -q package                                     # build all modules
java -jar services/ingestion/target/ingestion-0.1.0-SNAPSHOT.jar
```

Load the corpus once P1 accepts messages:

```bash
java -jar tools/corpus-generator/target/corpus-generator.jar --post http://localhost:8081/messages
```

## Layout

| Path | What | Owner |
|---|---|---|
| `contracts/` | Wire types every service compiles against. Changes here are breaking changes. | all |
| `services/ingestion` | **P1** accept, dedupe, publish — port 8081 | A |
| `services/archive` | **P2** system of record, retention and disposition — 8082 | A |
| `services/search` | **P3** full-text query and filters — 8083 | B |
| `services/cases` | **P4** cases, custodians, holds — 8084 | C |
| `services/evidence` | **P5** exports, manifests, append-only audit — 8085 | D |
| `frontend/` | Angular app — 4200 | E |
| `infra/` | docker-compose stack, topic and bucket creation, smoke test | E |
| `tools/corpus-generator` | Deterministic 12k message fixture | A |
| `docs/` | Architecture, frozen message schema | all |

Start with <a href="docs/architecture.md">docs/architecture.md</a>, then
<a href="docs/message-schema.md">docs/message-schema.md</a>.

## Conventions

**Contracts are shared, data is not.** Every service compiles against `contracts/`, and no service
reads another's database. Cross-service reads go through a REST call or an event, never a JDBC URL.

**Topic names come from `Topics`.** Broker-side auto-create is off, so a typo fails loudly — but
only if you use the constants. Adding a topic means editing `Topics.java` *and*
`infra/kafka/create-topics.sh`.

**Schema comes from Flyway, never from Hibernate.** `ddl-auto` is `validate` everywhere. Add a
migration; do not let Hibernate alter a table.

**`messageId` is derived, never random.** Use `Ids.messageId(externalId)`. Two environments that
load the same corpus must end up with the same identifiers or no fixture is portable.

## Things that have already bitten us

**Homebrew Kafka on port 9092.** If you have one, `brew services stop kafka`. Two brokers means
your producer succeeds, your consumer receives nothing, and both configs look correct — because
they are, just to different brokers.

**Spring Boot 4 splits auto-configuration into per-technology modules.** Putting `flyway-core` on
the classpath is not enough; you also need `org.springframework.boot:spring-boot-flyway` or
migrations are silently never applied and the app starts perfectly. Expect the same pattern for
other integrations, and check that a library is actually *doing* something rather than merely
present.

**We do not inherit from `spring-boot-starter-parent`,** so the `repackage` goal is bound
explicitly in the root POM. Without it jars build fine and fail at run time with "no main manifest
attribute".

## Verifying

```bash
mvn -q package          # builds and runs all tests
./infra/smoke-test.sh   # infrastructure reachable from the host
```
