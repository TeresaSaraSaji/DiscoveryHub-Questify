# Corpus Generator

Deterministic synthetic message corpus for DiscoveryHub (FR-1.2, FR-1.3).

```bash
mvn -q package                                    # from the repo root
java -jar tools/corpus-generator/target/corpus-generator.jar --help
```

## Regenerating the fixture

```bash
java -jar tools/corpus-generator/target/corpus-generator.jar --out fixtures
```

Output is a pure function of the options. Regenerating with the defaults must reproduce
`fixtures/messages.ndjson` byte for byte — `manifest.json` carries the SHA-256 so you can check:

```bash
shasum -a 256 fixtures/messages.ndjson
```

If that hash moves, the fixture changed and every test written against it needs a second look.
Commit the regenerated fixture in the same change as the generator edit that caused it.

## Loading a running stack

```bash
java -jar tools/corpus-generator/target/corpus-generator.jar \
  --post http://localhost:8081/messages --batch-size 250
```

The corpus deliberately ends with 25 messages whose `externalId` already appeared earlier in the
file. A healthy run therefore stores 12,000 messages having sent 12,025 — that gap is FR-1.6
working, not a fault. Posting the whole file twice must also leave exactly 12,000 stored.

## What's in the corpus

| | |
|---|---|
| Unique messages | 12,000 (+25 deliberate re-sends) |
| Custodians | 24, every one with 330+ messages |
| Types | 60% email / 40% chat, held to within a thread |
| Attachments | ~11% of messages, real bytes with SHA-256 |
| Date range | Jan 2017 – Sep 2026 |
| Past the 7-year retention default | ~2,100 (FR-5 has something to dispose of) |

The email/chat split is steered, not sampled: chat threads run longer than email threads, so a
fixed per-thread probability lands nowhere near a target expressed in messages. Each new thread
takes whichever channel is currently behind. Changing the ratio means editing `CHAT_TARGET` in
`CorpusBuilder`, and `holdsTheSixtyFortyEmailChatSplit` will fail until the test is updated with
it — deliberately, so the split cannot drift unnoticed.

Beyond volume, the corpus plants specific cases the services have to handle:

- **The same conversation captured from two mailboxes** — different `externalId`, different
  `custodianId`, identical body. Not a duplicate, and must not be deduped away.
- **A privileged thread** labelled `PRIVILEGED`, so the search filter has something to exclude.
- **Aged messages under custodians who later go on hold**, so the disposition guard can be shown
  refusing to delete.

## Project Halyard

The demo narrative, planted across 7 threads in 2024. Meridian Dynamics bids for the Northgate
transit contract, obtains the incumbent's indicative figures through a recent hire, prices just
underneath, wins, and is then asked to "do some housekeeping" on the evidence. Legal is looped in
late and the exchange is privileged.

Principals: Dana Whitfield (`cust-001`), Marcus Ellery (`cust-002`), Priya Raghunathan
(`cust-003`), Owen Castellanos (`cust-004`), Renee Toussaint (`cust-005`).

Searching `Halyard` surfaces the spine of it. The centrepiece for export and checksum verification
is `bridgeline-indicative-figures.csv`. The smoking gun (`Housekeeping`, 11 May 2024, 21:37 UTC)
was sent at the weekend and exists in two mailboxes.

See <a href="../../docs/message-schema.md">docs/message-schema.md</a> for the wire format.
