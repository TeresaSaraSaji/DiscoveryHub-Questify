# DiscoveryHub — Corpus & Ingestion workspace

Working copy of the two pieces owned by A: the data generator and P1 Ingestion. Built and tested
as one Maven project.

```
DiscoveryHub/
├── pom.xml              aggregator: Ingestion/contracts, Ingestion/service, Corpus/corpus-generator
├── message-schema.md    the frozen message contract — read this first
├── Corpus/              corpus generation + committed fixtures   → Corpus/README.md
└── Ingestion/           shared contracts + the P1 service        → Ingestion/README.md
```

## Build and test everything

```bash
mvn clean test        # 45 tests: 34 ingestion, 11 corpus generator
mvn -DskipTests package
```

## The 60-second demo of both halves together

```bash
# 1. start P1 (needs Kafka + Redis running)
java -jar Ingestion/service/target/ingestion-0.1.0-SNAPSHOT.jar &
curl -s localhost:8081/actuator/health

# 2. push the whole corpus through it
java -jar Corpus/corpus-generator/target/corpus-generator.jar \
  --post http://localhost:8081/messages --batch-size 250
```

12,025 sent, 12,000 accepted, 25 duplicates — the gap is idempotency (FR-1.6) working.

## Note on `contracts/`

`Ingestion/contracts/` is the shared wire contract, carried in this branch so that the corpus
generator and P1 build and test as one project. It has to stay identical to the copy the other four
services compile against, which came from `feat/contracts-corpus-infra`. Renaming, retyping or
removing a field there is a breaking change for P1 through P5 and is not P1's to make alone — see
`message-schema.md`.
