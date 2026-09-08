# P2.2 Disposition

Retention and disposition (FR-5). Owns the retention policy, the scheduled sweep that destroys
expired messages, and the ledger that proves what it did.

Port **8086**. Own database **postgres-disposition** on host port 5436. API browsable at
http://localhost:8086/swagger-ui.html.

```bash
docker compose up -d postgres-disposition kafka
mvn -q package -pl services/disposition-service -am
java -jar services/disposition-service/target/disposition-service-0.1.0-SNAPSHOT.jar
```

## What it does

1. Reads the retention period for each communication type from its own database (FR-5.1).
2. On a cron, asks P2's archive for messages whose `sent_at` is past their type's cutoff (FR-5.2).
3. For each one, checks whether a legal hold covers it. If so, it is skipped.
4. Deletes the rest, and records every decision — deleted, skipped, failed — in a ledger that
   outlives the messages themselves (FR-5.3).

Eligibility is computed per run, never stored per message. Change a retention period and the next
sweep picks it up with no backfill.

## Holds always win

Four independent guards stand between a candidate and deletion, and they are redundant on purpose:

| # | Guard | Where | Catches |
|---|---|---|---|
| 1 | `on_hold` flag | P2's `messages` row, mirrored from `holds.events` | Almost everything, for free |
| 2 | **Active hold scope** | `GET /holds/active` on P4, once per run | A hold on a case that P4 has not yet expanded to messages |
| 3 | `GET /holds/check` | Synchronous per-message call to P4 | Anything scope logic cannot express, e.g. evidence added from outside the hold's custodian range |
| 4 | `AND on_hold = false` | Inside the DELETE statement | A hold placed *after* the check, milliseconds before the write |

Only the fourth is evaluated atomically with the write, which is why it exists even though three
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

**Fail closed.** An unreachable P4 makes both the scope and the per-message verdict unavailable,
and while `hold-check.required` is true an unavailable answer is treated as held. Cannot verify,
will not delete. `HoldScopeSnapshot` keeps "no holds exist" and "P4 could not be asked" as
distinct states in the type, because an empty list is the most dangerous possible reading of a
network error. A run records `hold_scope_available`, so a run that deleted nothing while failing
closed can be told apart from one that had nothing to do.

### Proving it, per case

```bash
curl localhost:8086/disposition/cases/case-1/protected
```

Everything the holds on one case have saved from disposition, across every run. It outlives both
the release of the hold and the closing of the case, which is what makes it usable as evidence
rather than as a status display — `blocking_hold_id` and `blocking_case_id` are copied onto the
ledger row, not joined from P4.

## The uncomfortable part: how it deletes

P2 owns `messages` and `attachments`. NFR-1 says one datastore, one owner. This service deletes
from them anyway, in `ARCHIVE_DB` mode, and that deserves an explanation rather than a shrug.

FR-5.2 requires the disposition process to *delete* expired messages. P2's HTTP API is read-only
(`MessageController` has no `DELETE`), and P2 is another person's module mid-development. A
retention service that correctly identifies expired data and then cannot dispose of it satisfies
nothing. So the coupling is accepted, and confined:

- **Two statements, one class each.** The eligibility query in `JdbcArchiveGateway`, the guarded
  delete in `JdbcMessageDeleter`. Nothing else in the service touches that database.
- **No JPA entity for P2's tables.** A mirrored `@Entity` would be a second definition of someone
  else's schema, and P2's next migration would break this service at runtime. A named projection
  of six columns is a contract that survives; a mirrored entity is not.
- **No Flyway on that datasource.** Adding a second migration history to P2's database would make
  P2 fail on startup under `ddl-auto: validate`.
- **`ON DELETE CASCADE` does the attachments**, so the delete is one statement and there is no
  window where a message is gone but its attachments are orphaned.

### The exit

`KAFKA` mode is where this goes. Set:

```yaml
discoveryhub:
  disposition:
    delete-mode: KAFKA
```

and the sweep publishes a `DeleteCommand` to `disposition.commands` instead of writing. That
restores the property NFR-1 actually cares about and makes the delete path resilient the way NFR-2
wants — with P2 down, commands queue on the topic and apply when it returns, rather than the sweep
failing. It is already implemented and tested; it needs one consumer on P2's side.

The honest cost: a published command is not a completed delete, so the ledger records
`DELETE_REQUESTED` rather than `DELETED` and stays there. Closing that loop needs a
`disposition.results` topic for P2 to report back, which is deliberately not built yet — it would
be a topic nothing produces to, exactly what this repo's disabled topic auto-create exists to
prevent.

### For whoever owns P2

The consumer needed to switch this on. Topic `disposition.commands`, payload:

```json
{
  "runId": "e39c23e3-791c-4ae9-bb77-f54b48277e30",
  "messageId": "probe-del-0001",
  "externalId": "PROBE-DELETE-ME",
  "custodianId": "probe-custodian",
  "reason": "past retention",
  "requestedAt": "2026-09-08T10:08:51.156905Z"
}
```

```java
@KafkaListener(topics = Topics.DISPOSITION_COMMANDS, groupId = "p2-archive")
@Transactional
public void onDeleteCommand(String payload) {
    DeleteCommand cmd = json.readValue(payload, DeleteCommand.class);
    messages.findById(cmd.messageId()).ifPresent(m -> {
        // A command is a request, not a warrant. Holds may have changed since the sweep decided,
        // and you own the data.
        if (m.isOnHold()) {
            publisher.publishAudit(audit.dispositionRefused(cmd.runId(), m.getMessageId(), "held"));
            return;
        }
        messages.delete(m);   // attachments cascade
    });
}
```

Two properties this relies on: deleting an already-deleted message is a no-op, so at-least-once
delivery is fine; and the listener must refuse held messages itself rather than trusting the
command.

## What P4 has to provide

Two endpoints. Neither exists yet, so both are stubbed out safely: unreachable means "held".

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
curl localhost:8086/retention/policies

# ISO-8601 durations. P2555D = seven years, PT2M = two minutes.
curl -X PUT "localhost:8086/retention/policies/EMAIL?actor=saketh" \
  -H 'Content-Type: application/json' -d '{"period":"PT2M"}'
```

Changes are audited as `retention.policy-updated`: shortening a retention period is an instruction
to destroy data on the next sweep, and the chain of custody should show who gave it.

## API

| | |
|---|---|
| `POST /disposition/runs` | Sweep now. `?dryRun=true` decides everything, deletes nothing. |
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

## Watch out

**The corpus is mostly past retention.** Its messages are dated 2017–2026, so with the real
defaults (seven years for email, three for chat) roughly 500 corpus messages are eligible on any
given day. That is correct behaviour, and it means a sweep that is allowed to delete will delete
real fixture data. Reload with the ingestion upload in the root README if you need it back.

**Never set `hold-check.required: false` in committed config.** With P4 down it turns every
unverifiable message into a deletable one, and combined with the point above that is the corpus
going away batch-size at a time on a five-minute cron. Pass it on the command line for a demo:

```bash
java -jar ... --discoveryhub.disposition.hold-check.required=false
```

and run `?dryRun=true` first to see the blast radius.

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
mvn -q package -pl services/disposition-service -am    # 34 tests
```

The tests that matter are the hold guard ones in `DispositionServiceTest` — in particular
`refusesAMessageInsideTheScopeOfAHoldOnACaseBeforeP4HasExpandedIt`, which is the propagation
window pinned down — and the guarded DELETE in `JdbcMessageDeleterTest`, which runs against H2
with the same DDL as P2's `V2__messages.sql`. `AND on_hold = false` is enforced by the database,
and no amount of mocking would show that it works.

To exercise the case-hold guard by hand, stub P4 with a server that returns a hold on
`/holds/active` and `{"held": false}` on `/holds/check` — that combination is the propagation
window, and a candidate inside the hold's scope must survive the sweep with
`blockingCaseId` set in the ledger.
