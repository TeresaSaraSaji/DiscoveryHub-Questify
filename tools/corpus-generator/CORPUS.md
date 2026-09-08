# Corpus — data generation and fixtures

Everything that produces DiscoveryHub's test data. There is no real communications source, so
FR-1.2 and FR-1.3 require us to build one: at least 10,000 messages across at least 20 custodians,
with at least 5% carrying attachments. This folder is that generator plus the committed output it
produces.

This file consolidates the generator author's original notes, which previously lived in a second
README inside `corpus-generator/`.

```
tools/corpus-generator/
├── corpus-generator/          Java CLI that generates the corpus
│   ├── pom.xml
│   └── src/main/java/com/discoveryhub/tools/corpus/
│       ├── CorpusGenerator.java   entry point: parse options, generate, write or POST
│       ├── Options.java           command line parsing and defaults
│       ├── CorpusBuilder.java     the actual generation logic and email/chat steering
│       ├── Corpus.java            the generated result plus its statistics
│       ├── Roster.java            the 24 fictional custodians
│       ├── Vocabulary.java        subject lines, body phrases, filenames
│       ├── Narrative.java         the planted "Project Halyard" storyline
│       ├── Attachments.java       attachment bytes and their SHA-256 hashes
│       └── Ingestor.java          batched HTTP POST into P1 Ingestion
└── fixtures/                  the committed output
    ├── messages.ndjson        12,025 messages, one JSON object per line (10.7 MB)
    ├── custodians.json        the 24-person roster
    └── manifest.json          statistics + SHA-256 of messages.ndjson
```

## Why a fixture is committed at all

The generator is deterministic: output is a pure function of `--seed`, `--count`, `--custodians`
and `--duplicates`. Regenerating with the defaults reproduces `messages.ndjson` byte for byte.

That property is the whole point. Every `messageId` is derived from `externalId` via
`Ids.messageId()`, never from `UUID.randomUUID()`, so the same corpus loaded on five laptops
produces five identical sets of ids. Without that, a demo script that references a specific
message only works on the machine that loaded the data first, and a test asserting on a known
message breaks on everyone else's checkout.

Verify determinism at any time:

```bash
shasum -a 256 fixtures/messages.ndjson
grep messagesSha256 fixtures/manifest.json
```

Both must read `8e35f5b96f550b10d6290f137dd13b59a581c3d0452e71e91d054a1d9731493a`. If that hash
moves, the corpus changed — regenerate the fixture in the same commit as the generator edit that
caused it, and re-check anything asserting on specific messages.

## Building and running

The generator is a module of the aggregator pom one level up, so build from the parent folder:

```bash
cd ..                      # ~/Desktop/DiscoveryHub
mvn -DskipTests package
```

That produces a self-contained (shaded) jar:

```bash
java -jar Corpus/corpus-generator/target/corpus-generator.jar --help
```

### Options

| Flag | Default | Meaning |
|---|---|---|
| `--out <dir>` | `fixtures` | Directory to write `messages.ndjson`, `custodians.json`, `manifest.json` |
| `--count <n>` | `12000` | Unique messages to generate |
| `--seed <n>` | `20240906` | RNG seed. Change it and you get a different (but still reproducible) corpus |
| `--custodians <n>` | `24` | Roster size, 11–26 |
| `--duplicates <n>` | `25` | Deliberate re-sends appended to the end |
| `--post <url>` | — | POST to an ingestion endpoint instead of writing files |
| `--batch-size <n>` | `200` | Messages per POST request |
| `--stats` | off | Print statistics only, write nothing |
| `--help` | | Usage |

### Common tasks

Print statistics without touching anything — the fastest way to confirm the generator works:

```bash
java -jar Corpus/corpus-generator/target/corpus-generator.jar --stats
```

Regenerate the committed fixture:

```bash
cd Corpus
java -jar corpus-generator/target/corpus-generator.jar --out fixtures
```

Load a running stack (P1 Ingestion must be up on 8081 — see `../../services/ingestion-service/NOTES.md`):

```bash
java -jar corpus-generator/target/corpus-generator.jar \
  --post http://localhost:8081/messages --batch-size 250
```

A smaller corpus for a fast loop:

```bash
java -jar corpus-generator/target/corpus-generator.jar --count 500 --stats
```

## What the corpus contains

From `fixtures/manifest.json`, verified by re-running `--stats`:

| | |
|---|---|
| Unique messages | 12,000 |
| Deliberate re-sends | 25 (12,025 lines total) |
| Custodians | 24, each with 337–879 messages |
| Emails / chats | 7,199 / 4,801 (60/40) |
| With attachments | 1,304 (10.87%, real bytes with SHA-256) |
| Attachment bytes | 1,778,727 |
| Distinct threads | 2,880 |
| Date range | 2017-01-03 → 2026-09-04 |
| Past the 7-year retention default | 2,115 |
| Privileged messages | 5 |

Comfortably clears the FR-1.2 floor (10,000 / 20) and doubles the FR-1.3 attachment floor (5%).

Type is held constant within a thread — an email thread never turns into a chat halfway through.

The 60/40 email-chat split is **steered, not sampled**: chat threads run longer than email
threads, so a fixed per-thread probability lands nowhere near a target expressed in messages. Each
new thread takes whichever channel is currently behind. The ratio lives in `CHAT_TARGET` in
`CorpusBuilder`, and the test `holdsTheSixtyFortyEmailChatSplit` fails if it drifts — deliberately.

## The traps it plants on purpose

This is the part worth understanding before you trust a green test elsewhere. The corpus is
seeded with cases that make specific bugs visible instead of silent:

1. **25 deliberate re-sends.** The file ends with 25 messages whose `externalId` already appeared.
   A healthy load therefore *sends* 12,025 and *stores* 12,000. That gap is FR-1.6 working, not a
   fault. Posting the whole file twice must still leave exactly 12,000 stored.
2. **The same conversation captured from two mailboxes.** Two `externalId`s, two `custodianId`s,
   identical body. This is **not** a duplicate and must not be deduped away — a hold on one
   custodian has to preserve their copy independently of the other's. Any dedupe keyed on body
   hash, or on `from`+`subject`+`sentAt`, fails here rather than quietly destroying evidence. It is
   planted on the narrative's smoking-gun message specifically so the demo would break loudly.
3. **A privileged thread** labelled `PRIVILEGED`, so the search filter has something real to
   exclude.
4. **2,115 messages past the 7-year retention default**, under custodians who later go on hold —
   so FR-5 disposition has something to delete and the hold guard has something to refuse.

## Project Halyard — the demo narrative

Planted across 7 threads in 2024. Meridian Dynamics bids for the Northgate transit contract,
obtains the incumbent's indicative figures through a recent hire, prices just underneath, wins, and
is then asked to "do some housekeeping" on the evidence. Legal is looped in late and that exchange
is privileged.

Principals: Dana Whitfield (`cust-001`), Marcus Ellery (`cust-002`), Priya Raghunathan
(`cust-003`), Owen Castellanos (`cust-004`), Renee Toussaint (`cust-005`).

Searching `Halyard` surfaces the spine of it (8 mentions). The centrepiece for export and checksum
verification is `bridgeline-indicative-figures.csv`. The smoking gun — subject `Housekeeping`,
11 May 2024 21:37 UTC — was sent at the weekend and exists in two mailboxes.

This matters for the demo because the required end-to-end flow is: generate → ingest → search →
create case → place hold → prove deletion is blocked → export → verify checksums → show audit
trail. The narrative gives every one of those steps something specific to point at.

## Message format

Each line of `messages.ndjson` is one `Message` object in the frozen wire format. Optional fields
(`subject` on chats, `inReplyTo` on thread openers) are **omitted entirely** rather than sent as
`null`; array fields are always present, possibly `[]`.

The full field-by-field contract, including the two-identifier rule and what counts as a
duplicate, is in `../message-schema.md`. Read that before changing anything here — the generator,
P1, P2, P3 and P5 all depend on that shape, so renaming or retyping a field is a breaking change
needing all five owners to agree.

## Tests

```bash
cd .. && mvn -pl tools/corpus-generator test
```

11 tests in `CorpusBuilderTest`, covering determinism, the volume and attachment floors, the
email/chat split, and the planted duplicate cases.
