# P2.2 Disposition

Retention and disposition (FR-5). Owns the retention policy, the scheduled sweep that destroys
expired messages, and the ledger that proves what it did.

Port **8087**. Own database **postgres-disposition** on host port 5437. API browsable at
http://localhost:8087/swagger-ui.html.

```bash
docker compose up -d postgres-disposition kafka
mvn -q package -pl services/disposition-service -am
java -jar services/disposition-service/target/disposition-service-0.1.0-SNAPSHOT.jar
```

**The scheduled sweep is off by default.** Trigger one with `POST /disposition/runs`, or turn the
cron on with `--discoveryhub.disposition.schedule.enabled=true` once you know what it will delete.
[Why](#the-scheduler-is-off-by-default).

## What it does

1. Reads the retention period for each communication type from its own database (FR-5.1).
2. On a cron, asks P2's archive for messages whose `sent_at` is past their type's cutoff (FR-5.2).
3. For each one, checks whether a legal hold covers it. If so, it is skipped.
4. Deletes the rest, and records every decision — deleted, skipped, failed — in a ledger that
   outlives the messages themselves (FR-5.3).

Eligibility is computed per run, never stored per message. Change a retention period and the next
sweep picks it up with no backfill.

## Holds always win

Five independent guards stand between a candidate and deletion, and they are redundant on purpose:

| # | Guard | Where | Catches |
|---|---|---|---|
| 1 | `on_hold` flag | P2's `messages` row, mirrored from `holds.events` | Almost everything, for free |
| 2 | **Active hold scope** | `GET /holds/active` on P4, once per run | A hold on a case that P4 has not yet expanded to messages |
| 3 | **Held-case evidence** | `POST /holds/evidence-check` on P4, once per run | A message attached to a held case that the hold's own scope does not cover |
| 4 | `GET /holds/check` | Synchronous per-message call to P4 | The backstop for anything neither run-level answer expressed |
| 5 | `AND on_hold = false` | Inside the DELETE statement | A hold placed *after* the check, milliseconds before the write |

Only the fifth is evaluated atomically with the write, which is why it exists even though four
checks already passed. A held message that reaches it is refused there, recorded as
`SKIPPED_HOLD`, and audited as `disposition.refused` with outcome `REFUSED`. That is the
demonstrable proof FR-4.6 asks for.

### Why guard 2 exists

Guards 1, 3 and 4 all read **per-message** state, and FR-4.3 requires hold propagation to be
**asynchronous**. So between an investigator placing a hold on a case and every message in scope
being flagged, there is a window as long as P4's fan-out over a large custodian set. For the whole
of that window every per-message signal correctly reports "not held" — nothing has marked the
messages yet — and a sweep landing in it would destroy exactly the evidence the hold was placed to
preserve.

Guard 2 evaluates the hold's **scope** instead: custodians, date range, and the case it belongs
to. The hold on the case is enough, expanded or not. It is fetched once per run, not once per
message — there are only ever a handful of active holds, and one snapshot per sweep means a run
cannot delete one message and then protect an identical one because a hold landed halfway through.

Scope matching:

- **Custodians** — exact. An *empty* custodian set means **every** custodian, not none: a hold
  placed without narrowing to specific people covers the whole corpus.
- **Date range** — exact, inclusive, either end may be unbounded.
- **Search terms** (FR-4.1) — *not* evaluated. This service has identities and timestamps, not
  bodies, so it cannot tell whether a message matches "project atlas". A term-scoped hold
  therefore protects everything within its custodian and date range, which over-protects by
  design. Under-protecting means deleting evidence and being unable to say so; over-protecting
  means a message survives one retention cycle longer than it had to. Those are not comparable
  costs.

Overlapping holds (FR-4.5) need no special handling: one covering hold is enough to refuse, the
ledger records which one, and the message stays protected until every covering hold is gone from
the snapshot. Release is P4's business — a released hold is simply absent next run.

### Why guard 3 exists

A hold's scope and a case's contents are **different sets**, and guard 2 only knows the first.

A hold is written as custodians plus a date range. But FR-2.4 lets an investigator add *any*
individual message — or a whole search result set — to a case as evidence. Nothing constrains
those to the hold's scope: a hold covering two custodians for 2019–2021 does not cover a 2024
message from a third custodian, so `ActiveHold.covers` correctly returns **false** for it. And yet
if someone pulled that message into the matter, deleting it destroys part of a production that a
human has already selected.

So the sweep asks P4 a second question — "of these candidate ids, which are evidence in a case
that is under hold?" — and refuses any that come back. Scope alone would delete out-of-scope
evidence; evidence alone would delete everything a broad custodian hold was placed to freeze
before anyone had reviewed it. Neither guard subsumes the other, and `HoldContext` carries both.

Evidence membership protects only while the **case is under hold**. A message in a case with no
hold is not returned and stays deletable — otherwise adding anything to any case would silently
switch retention off for it, and retention would decay to nothing as the system got used.

The two refusals are distinguishable in the audit trail via `detail.blockedBy`, which is
`hold-scope` or `case-evidence`. They answer different questions in review: one is a rule firing,
the other is a human's decision being overridden, and the second is the worse failure.

**Fail closed.** An unreachable P4 makes the hold context unavailable, and while
`hold-check.required` is true an unavailable answer is treated as held. Cannot verify, will not
delete. `HoldContext` keeps "nothing is under hold" and "P4 could not be asked" as distinct states
in the type rather than both being an empty collection, because an empty collection is the most
dangerous available reading of a network error. A run records `hold_scope_available`, so a run
that deleted nothing while failing closed can be told apart from one that had nothing to do.

Partial knowledge counts as no knowledge: if the scopes come back but the evidence check fails,
the whole context is unavailable. Knowing the scopes but not the evidence membership would let the
sweep delete an out-of-scope evidence item with full confidence, which is worse than knowing
nothing and refusing.

### Proving it, per case

```bash
curl localhost:8087/disposition/cases/case-1/protected
```

Everything the holds on one case have saved from disposition, across every run. It outlives both
the release of the hold and the closing of the case, which is what makes it usable as evidence
rather than as a status display — `blocking_hold_id` and `blocking_case_id` are copied onto the
ledger row, not joined from P4.

## How it deletes

P2 owns `messages` and `attachments`, and NFR-1 says one datastore, one owner. So this service does
not write to them. It publishes a `DeleteCommand` to `disposition.commands` and P2 applies it —
`delete-mode: KAFKA`, the default.

That split is what makes the two halves independently correct. This service decides *what* is past
retention and no hold covers; P2 decides whether to act, re-checking holds against the data it
owns. It also buys the NFR-2 property directly: with P2 down, commands queue on the topic and are
applied when it comes back, rather than the sweep failing or losing the deletions.

### The loop

```
P2.2  sweep ──DeleteCommand──▶ disposition.commands ──▶ P2  DispositionCommandListener
                                                            ├ on_hold flag?      refuse
                                                            ├ P4 says held, or
                                                            │ P4 unreachable?    refuse
                                                            └ otherwise          DELETE (attachments cascade)
P2.2  DeleteReceiptListener ◀── disposition.results ◀──DeleteReceipt──┘
```

The sweep records `DELETE_REQUESTED`, which is all it can honestly claim at that moment — the
message is not gone, it has been asked about. The receipt settles the row to what P2 actually did.
Both records are in `contracts`, so neither end carries its own copy of the shape.

| Receipt outcome | Ledger becomes | |
|---|---|---|
| `DELETED` | `DELETED` | Gone, confirmed. |
| `REFUSED_HOLD` | `SKIPPED_HOLD` | A hold landed between this service's check and P2's delete, and P2's own guard caught it. The rarest and most reassuring outcome in the system. |
| `NOT_FOUND` | `DELETED` | Already absent. The desired state holds; recording a failure would make the next run look like it is retrying something broken. |
| `FAILED` | `FAILED` | Still there. The next sweep finds it expired again and retries. |

Settling moves the run summary too, so a run row never disagrees with its own items. It is guarded
inside `DispositionItemEntity.settle`: only a row still awaiting a receipt can move, so a
redelivered receipt on an at-least-once topic cannot turn a recorded refusal back into a deletion.

A row still at `DELETE_REQUESTED` with a null `settled_at` hours later means P2 is not consuming.

### `ARCHIVE_DB` mode

The other mode deletes straight from P2's database, guarded by `AND on_hold = false` inside the
statement. It exists because it predates P2's consumer, and it is still useful for running this
service against an archive with no P2 alongside it — the integration tests use it for exactly that
reason, since asserting on rows is stronger than asserting on published commands.

It is not the default and should not be. It is a second writer to another service's tables, and
the guard it relies on is the mirrored `on_hold` flag rather than P4. Where it is unavoidable the
coupling is confined: two statements, one class each (`JdbcArchiveGateway`, `JdbcMessageDeleter`),
no JPA entity for P2's tables, and no Flyway on that datasource — a second migration history in
P2's database would make P2 fail on startup under `ddl-auto: validate`.

### If P2 refuses everything, check `CASES_BASE_URL`

P2's guard treats an unreachable P4 as held, correctly. The failure mode is that this looks
*exactly* like holds working: every message skipped, `disposition.refused` all over the audit
trail, nothing deleted, no errors. The first end-to-end run of this loop refused all five test
messages for that reason — `CASES_BASE_URL` was unset in P2's container, so the hold check was
resolving to `localhost:8084` inside the container and getting connection refused.

It is now set in both `services/storage-service/Dockerfile` and `docker-compose.yml`. If a sweep
skips everything with `P4 reports an active hold, or could not be reached`, check P2's logs for
`hold check failed` before believing the holds.

## What P4 has to provide

Three endpoints. None exists yet, so all are handled safely: unreachable means "held".

### `GET /holds/active` — the case-level guard

Every hold currently in force, as scope. **Not** expanded to messages, and not filtered by
whether propagation has finished — that is the whole point.

```json
[
  {
    "holdId": "hold-1",
    "caseId": "case-1",
    "caseName": "SEC Inquiry 2026",
    "custodianIds": ["cust-004", "cust-017"],
    "from": "2019-01-01T00:00:00Z",
    "to": "2021-12-31T23:59:59Z",
    "terms": ["project atlas"]
  }
]
```

Rules this service relies on:

- **Only unreleased holds appear.** Release semantics are yours; absence is how this service
  learns a hold is gone. A message covered by two holds must keep appearing until both are
  released (FR-4.5).
- **`custodianIds: []` means all custodians.** If you mean "no one", do not return the hold.
- **`from` / `to` may be null** for an unbounded range.
- **`terms` may be omitted.** If present, this service widens rather than narrows — see above.
- Unknown fields are ignored, so you can add to this shape freely.

### `POST /holds/evidence-check` — the held-case evidence guard

"Of these messages, which are evidence items in a case that is under hold?" A bulk POST rather
than a query string because a sweep carries up to `batch-size` ids, and a GET would hit a URL
length limit somewhere between here and P4 — a failure that would look like a hold check bug and
behave like data loss.

Request:

```json
{ "messageIds": ["msg-1", "msg-2", "msg-3"] }
```

Response — only the ones that *are* protected, so an empty array is the normal answer:

```json
[
  {
    "messageId": "msg-2",
    "holdId": "hold-1",
    "caseId": "case-1",
    "caseName": "SEC Inquiry 2026"
  }
]
```

On your side this is one indexed query: evidence items joined to their case's active holds,
filtered by `message_id IN (...)`. Rules this service relies on:

- **Only include a message if its case has an active hold.** A case with no hold must not appear,
  or retention stops applying to anything anyone ever filed.
- **Membership beats scope.** Include an evidence item even when the hold's own custodian and date
  scope would not cover it — that is the entire purpose of this endpoint.
- Not called at all when `/holds/active` returns an empty list, since nothing could be protected.

### `GET /holds/check?messageId=...` — the per-message guard

```json
{ "held": true }
```

Same shape P2 already assumes, so there is one contract to satisfy rather than two. Anything other
than `{"held": false}` — including a 500, a timeout, or an empty body — is treated as held.

## Retention policy (FR-5.1)

The policy lives in the `retention_policies` table, not in `application.yml`. FR-5.1 asks for it to
be configurable and settable to minutes for the demo — a restart to change a number is not
configurable in any useful sense, and a value that exists only in a container's environment cannot
be shown in the UI or audited when it changes.

Config seeds the table for any type missing a row on startup, and never overwrites one. So a demo
that drops email retention to two minutes still has it after a restart.

```bash
curl localhost:8087/retention/policies

# ISO-8601 durations. P2555D = seven years, PT2M = two minutes.
curl -X PUT "localhost:8087/retention/policies/EMAIL?actor=saketh" \
  -H 'Content-Type: application/json' -d '{"period":"PT2M"}'
```

Changes are audited as `retention.policy-updated`: shortening a retention period is an instruction
to destroy data on the next sweep, and the chain of custody should show who gave it.

## API

| | |
|---|---|
| `POST /disposition/runs` | Sweep now, synchronously, and return the counts. `?dryRun=true` decides everything, deletes nothing. |
| `POST /disposition/runs?async=true` | Accept and sweep in the background. **202** with a `QUEUED` run id; watch the stream below. |
| `GET /disposition/runs/stream` | Live progress as server-sent events. Closes when the run finishes. |
| `GET /disposition/runs/progress` | The same snapshot, polled once. |
| `GET /disposition/runs` | Run history, newest first, paginated. |
| `GET /disposition/runs/{runId}` | One run's summary. |
| `GET /disposition/runs/{runId}/items` | Per-message ledger. `?outcome=SKIPPED_HOLD` is the "what did holds save?" view. |
| `GET /disposition/messages/{messageId}` | Every decision ever recorded about one message. Survives the message. |
| `GET /disposition/cases/{caseId}/protected` | Everything this case's holds have saved from disposition. |
| `GET /retention/policies` · `PUT /retention/policies/{type}` | Retention policy (FR-5.1). |
| `GET /disposition/stats` | Dashboard counts (FR-8.2). |
| `GET /disposition/stats/candidates` | What the next sweep would touch, without running it. |

A second concurrent run is refused with **409**, not queued: two sweeps would evaluate the same
candidates and race on the same rows, and whatever a dropped tick skipped is still expired at the
next one.

### Watching a sweep

A sweep makes one HTTP call to P4 per candidate and considers up to `batch-size` of them, so the
synchronous form holds the connection for the length of the work — over the full corpus that is
the bulk operation NFR-3 says must not time out the UI. Start it asynchronously instead:

```bash
curl -X POST "localhost:8087/disposition/runs?async=true&actor=sahithi"
curl -N localhost:8087/disposition/runs/stream
```

```
event:progress
data:{"runId":"37956dfd…","status":"QUEUED","processed":0,"total":0,…,"percent":0,"terminal":false}

event:progress
data:{"runId":"37956dfd…","status":"RUNNING","processed":3,"total":5,"deleted":1,"skippedHold":2,…,"percent":60,"terminal":false}

event:progress
data:{"runId":"37956dfd…","status":"COMPLETED","processed":5,"total":5,"deleted":2,"skippedHold":3,…,"percent":100,"terminal":true}
```

The current snapshot is sent on connect, so a client attaching mid-sweep — or just after one
finished — sees state immediately rather than an empty stream. The stream closes on a terminal
status instead of being left for the browser to time out. Progress is in memory and for the
current run only; the ledger is the durable record, and a per-message counter written to the
chain-of-custody table would be write traffic for something nobody reads tomorrow.

## Watch out

### The scheduler is off by default

`discoveryhub.disposition.schedule.enabled` defaults to **false**, and the
`@ConditionalOnProperty` on `DispositionScheduler` uses `matchIfMissing = false`, so the bean does
not exist unless you ask for it.

This is not timidity. **The committed corpus is mostly past retention** — fixtures dated
2017–2026, defaults of seven years for email and three for chat, so roughly 500 messages are
eligible on any given day. A sweep enabled by default fires within seconds of startup, before
anyone has read the configuration, and in `ARCHIVE_DB` mode deletes real fixture data — then again
every tick.

That is not a hypothetical. During testing, a locally started instance with a ten-second cron
completed two sweeps of `candidates=500, deleted=499` before a retention change could be applied.
It happened to be running in `KAFKA` mode, so 499 delete commands were *published* rather than
executed, and the corpus survived — and then those commands sat on `disposition.commands` as a
loaded gun for whoever added P2's consumer with `auto-offset-reset: earliest`. The topic had to be
deleted and recreated.

**That consumer now exists**, so the near miss no longer has its reprieve. `KAFKA` mode is the
default and P2 applies what it finds on the topic; a stray command is a real deletion, subject
only to P2's hold guard. The one thing that has improved is the evidence: the receipt comes back
and the ledger records what happened, so an accidental sweep can at least be accounted for
afterwards.

Three lessons worth keeping:

- Turn the cron on deliberately, after `?dryRun=true` tells you the blast radius.
- **Check what is sitting on `disposition.commands`** before starting P2 against a broker you have
  been testing sweeps on. `docker exec discoveryhub-kafka /opt/kafka/bin/kafka-topics.sh
  --bootstrap-server localhost:19092 --delete --topic disposition.commands`, then re-run
  `infra/kafka/create-topics.sh`.
- A sweep that skipped everything is not proof that holds work. Check P2's logs for
  `hold check failed` first — see [above](#if-p2-refuses-everything-check-cases_base_url).

### Never set `hold-check.required: false` in committed config

With P4 down it turns every unverifiable message into a deletable one, and combined with the point
above that is the corpus going away batch-size at a time. Pass it on the command line for a demo:

```bash
java -jar ... --discoveryhub.disposition.hold-check.required=false
```

and run `?dryRun=true` first. Reload the corpus with the ingestion upload in the root README if
you need it back.

**`DataSourceProperties` moved in Boot 4** to `org.springframework.boot.jdbc.autoconfigure`.
Every Boot 3 example still shows `org.springframework.boot.autoconfigure.jdbc`, and the failure is
a bare "cannot find symbol" that looks like a missing dependency. Same per-technology module split
that already caught this repo with Flyway and Kafka.

**Declaring a `DataSource` bean disables Boot's datasource auto-configuration entirely,** so both
datasources have to be declared by hand in `DataSourceConfig` — including the primary one that
would otherwise be free. Omitting `@Primary` surfaces as an `EntityManagerFactory` failure that
mentions nothing about datasources.

**The sweep is not `@Transactional`.** Deliberately: a rollback would erase the ledger record of
deletions that already happened in P2's database, which no transaction of ours can undo. The
ledger has to survive the failure that makes it interesting.

## Verifying

```bash
mvn -q package -pl services/disposition-service -am    # 76 tests
```

The integration tests need Docker: they run against real PostgreSQL and a real Kafka broker via
Testcontainers, started once for the module by `support/Containers`.

| | |
|---|---|
| `DispositionIntegrationTest` | The sweep against two real Postgres instances. Booting the context is itself the assertion that Flyway's migrations and the entities agree under `ddl-auto: validate`. Covers the per-type cutoffs, every hold guard, the fail-closed path, dry run, batch bounding, and a runtime policy change taking effect on the next sweep. |
| `DeleteLoopKafkaIntegrationTest` | The Kafka delete path from this side, against a real broker. The test plays P2: consumes the command, checks its shape, answers with a receipt, and asserts the ledger settles — including that a redelivered receipt cannot rewrite it. |
| `RunProgressIntegrationTest` | `?async=true` and the SSE stream, through the real HTTP stack. |
| `DispositionCommandIntegrationTest` (in storage-service) | P2's half: a command on the topic, a row gone from a real archive, a receipt back — and a held message still there. |

`DispositionIntegrationTest` builds the archive schema from **P2's own migration file**, read out
of `services/storage-service`, rather than from a copy. `JdbcArchiveGateway` hand-writes SQL
against another service's table, so the failure it is exposed to is P2 renaming a column — which
compiles fine and breaks at run time. A copied fixture would keep passing after such a rename; the
real file means the build breaks the moment the contract does.

Of the unit tests, the ones that matter are the hold guards in `DispositionServiceTest` — in particular
`refusesAMessageInsideTheScopeOfAHoldOnACaseBeforeP4HasExpandedIt` (the propagation window) and
`refusesAMessageAttachedToAHeldCaseEvenWhenItIsOutsideThatHoldsScope` (the scope-versus-contents
gap) — plus the guarded DELETE in `JdbcMessageDeleterTest`, which runs against H2 with the same
DDL as P2's `V2__messages.sql`. `AND on_hold = false` is enforced by the database, and no amount
of mocking would show that it works.

To exercise the case guards by hand, stub P4 so that:

- `/holds/active` returns a hold on a case, scoped to a custodian your test message does **not**
  belong to;
- `/holds/check` returns `{"held": false}` — propagation has not happened;
- `/holds/evidence-check` returns your test message.

That combination defeats guards 1, 2 and 4, so the message survives only if guard 3 works. It
should end the sweep as `SKIPPED_HOLD` with `blockingCaseId` set, while a sibling message that is
not evidence is deleted. Note that Spring's `RestClient` sends the POST body **chunked**, so a
hand-rolled stub reading `Content-Length` will get an empty body — and the sweep will correctly
refuse everything rather than proceed on partial knowledge.
