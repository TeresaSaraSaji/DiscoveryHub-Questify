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

Three independent guards stand between a candidate and deletion, and they are redundant on purpose:

| Guard | Where | Catches |
|---|---|---|
| `on_hold` flag | P2's `messages` row, mirrored from `holds.events` | Almost everything, for free |
| `GET /holds/check` | Synchronous call to P4 | A hold P2's consumer has not applied yet |
| `AND on_hold = false` | Inside the DELETE statement | A hold placed *after* the check, milliseconds before the write |

Only the third is evaluated atomically with the write, which is why it exists even though the
first two already ran. A held message that reaches it is refused there, recorded as
`SKIPPED_HOLD`, and audited as `disposition.refused` with outcome `REFUSED`. That is the
demonstrable proof FR-4.6 asks for.

**Fail closed.** If P4 cannot be reached the verdict is `UNKNOWN`, and while
`hold-check.required` is true an unknown is treated as held. Cannot verify, will not delete. A P4
that is down for an entire run produces a completed run that deleted nothing — the correct
outcome, not a failure.

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
mvn -q package -pl services/disposition-service -am    # 25 tests
```

The tests that matter are the hold guard ones in `DispositionServiceTest` and the guarded DELETE in
`JdbcMessageDeleterTest`, which runs against H2 with the same DDL as P2's `V2__messages.sql` —
`AND on_hold = false` is enforced by the database, and no amount of mocking would show that it
works.
