# Working in this repository

## Commits

**No tool attribution in commit messages.** No `Generated with …` line, no
`Co-Authored-By:` trailer. The commit author is the person who asked for the work, and that is the
whole of the attribution.

Existing commits that carry those trailers are left alone — several are already published on
`sahithi-retention` and other branches, and rewriting them would break history other people have
pulled.

Write the *why*, not the *what*: the diff already says what changed.

## Build and verify

```bash
mvn -q package -DskipTests      # build all ten modules
mvn package                     # build and run every test
cd frontend && npm run check    # ng build && ng test --no-watch
./infra/smoke-test.sh           # datastores reachable from the host
```

Both suites must be green before pushing. Java tests log `ERROR` lines on purpose — several assert
failure paths ("archive unreachable", "P2 unreachable"), so grep for `BUILD SUCCESS` rather than
for the absence of errors.

## Running the stack locally

Datastores in Docker, services from jars. **`mongodb` is required** — P2 keeps messages there and
Postgres holds only its metadata, so storage-service will not start without it:

```bash
docker compose up -d kafka kafka-init redis postgres-ingestion postgres-archive-meta \
  postgres-cases postgres-holds postgres-audit postgres-disposition mongodb \
  elasticsearch minio minio-init

for s in ingestion storage search case hold export disposition; do
  java -jar services/$s-service/target/$s-service-0.1.0-SNAPSHOT.jar &
done
```

The archive Postgres container is `postgres-archive-meta`, not `postgres-archive` — the name
changed with the MongoDB split, and `docker compose up postgres-archive` simply errors out on an
undefined service.

Ports: 8081 P1, 8082 P2, 8083 P3, 8084 P4 case, 8085 P5, 8086 P4 hold, **8087 P2.2**, 4200 UI.

Datastore ports: 5433 P2 archive metadata (`postgres-archive-meta`, *not* `postgres-archive`),
5434 P4 cases, 5435 P5 audit, 5436 P4 holds, **5437 P1 ingestion**, **5438 P2.2 disposition**,
27017 Mongo. The last two are the ones that catch people out: 5437 belonged to disposition before
P1 got a database, and a branch that predates the move will quietly point P2.2 at P1's Postgres.

Load the corpus once P1 is up (12,000 messages, about 90 seconds through Kafka into P2 and P3):

```bash
curl -F "file=@tools/corpus-generator/fixtures/messages.ndjson" \
  "http://localhost:8081/messages/upload?async=true"
```

## Sharing one stack with the team

Nothing syncs between machines. `docker compose up` on a second laptop creates a second, empty set
of databases in its own volumes — Docker packages services, it does not connect hosts. A case is
shared only because everybody is pointed at the one machine that owns it.

One machine runs the stack and everyone else opens the URL it prints:

```bash
docker compose up -d kafka kafka-init redis postgres-ingestion postgres-archive-meta \
  postgres-cases postgres-holds postgres-audit postgres-disposition mongodb \
  elasticsearch minio minio-init
./infra/serve-lan.sh
```

Two things make that work, and both are configuration rather than code:

- `frontend/public/api-config.js` derives the service host from `window.location.hostname`, so a
  visitor's browser calls the host's services instead of its own empty machine.
- `DISCOVERYHUB_WEB_CORS_ALLOWED_ORIGINS` adds the host's LAN address to the allowed origins.
  `@Value` and the actuator placeholder both resolve it from the environment, so the one variable
  covers the panels and the status strip. No `CorsConfig` needs editing.

**Sharing the link shares the delete button.** The retention page runs a real sweep, and on a
shared host a visitor who clicks it destroys the host's data, not their own. This has already
happened once: a `MANUAL`, non-dry-run sweep took 486 messages out of the archive, and holds saved
only the 14 they covered. The deletion is permanent — P1 refuses the re-upload as duplicate,
because dedupe outlives the message. Restoring means clearing Redis *and* deleting the orphaned
`message_id_map` rows in `postgres-ingestion` (the ones whose `message_id` is no longer in Mongo),
and only then re-uploading.

There is no configuration that makes this safe. `discoveryhub.disposition.schedule.enabled` is
already `false`, and that only stops the *timer* — `POST /disposition/runs` still sweeps on demand,
which is exactly what the button does. `dryRun` is a query parameter on that request, not a
setting, so nothing on the host can force it. Say so before handing the URL round.

Note also that a sweep deletes from P2 without removing the document from P3, so Elasticsearch
keeps returning hits for messages the archive no longer holds until the index is rebuilt.

The UI is served by `ng serve --host 0.0.0.0`. There is no authentication anywhere in the stack, so
whoever can reach the port can delete a case — keep this on a network you trust.

## Traps that have cost real time

**The `discoveryhub-frontend` container squats on 4200.** Docker Desktop restarts it with the rest
of the project, where it serves a *prebuilt, stale* `dist/` over nginx. It binds `[::]:4200` while
`ng serve` binds `127.0.0.1:4200`, so both start happily and `http://localhost:4200` returns
whichever of `::1`/`127.0.0.1` resolves first — you edit a component and nothing changes. Run
`docker stop discoveryhub-frontend` before `ng serve`.

**A stale process on a port.** A service whose port is taken logs `APPLICATION FAILED TO START` and
exits, but something else is still answering that port, so health checks pass and you debug the
wrong process. Check `lsof -nP -iTCP:8086 -sTCP:LISTEN` and compare the PID's start time.

**A container can be healthy with its port unpublished.** If a bind loses a race — an orphan from
a renamed service still holding 5433, say — the container starts anyway, reports healthy, and
`docker port` prints nothing, while every service dies on `Connection to localhost:5433 refused`.
`docker restart` does not repair it; `docker compose up -d --force-recreate <svc>` does, and the
named volume means no data is lost. `smoke-test.sh` used to miss this entirely because its
datastore checks run through `docker exec` and never leave the container — it now checks the host
ports separately, and those are the ones the jars actually use.

**CORS is two settings, not one.** `CorsConfig` does not cover actuator — its endpoints use a
separate handler mapping — so `management.endpoints.web.cors` is set in every service's
`application.yml` as well. Miss it and the UI's status strip shows every service DOWN while every
panel loads fine. Do not pin the allowed origin to port 4200; any loopback port is allowed, because
an IDE preview pane and `ng serve --port 4201` are different origins.

**`Resource.value()` throws in the error state** and `defaultValue` does not cover it. Angular
instantiates projected content eagerly, so a panel's table is evaluated while the panel is showing
a failure, and the throw blanks the whole page. Every list in the frontend goes through
`valueOr()`; `frontend/src/app/pages/pages.spec.ts` mounts every page with every service down to
keep it that way. This has been introduced three times.

**A sweep that deleted nothing is not evidence that holds work.** Every hold path fails closed —
P2's `GET /holds/check`, and P2.2's `GET /holds/active` and `POST /holds/evidence-check` (all
three served by hold-service now) — so an unreachable or misconfigured P4 produces the exact same
result as holds doing their job: every candidate skipped, refusals throughout the audit trail, no
errors anywhere. Before believing a quiet sweep, check `holdScopeAvailable` in the candidates
preview and P2's logs for `hold check failed`.

**A stale jar looks like a broken environment.** `git pull` does not rebuild, so a service keeps
running last week's `application.yml` from inside its jar while the compose file and the source
tree have moved on. This is what "it works for everyone but me" almost always is. The two ways it
showed up: disposition-service defaulting to 8086/5436 instead of 8087/5438 — which authenticates
against postgres-holds and reports `password authentication failed for user "disposition"`, a
credentials error that is really a wrong-datastore error — and Flyway refusing to start P2 because
the jar's migrations disagree with what is already stamped in `flyway_schema_history`. Run
`mvn -q package -DskipTests` after every pull, and compare `ls -lT services/*/target/*.jar`
against `git log -1 --format=%cd <the yml you are debugging>` before believing any config.

**Flyway checksum mismatches on `archive` are not repairable.** The MongoDB split rewrote P2's
lineage (V4 is now `retention_override`, V5 is gone, V6 is `drop_disposition`), so a database
stamped by a pre-split jar has tables the current migrations never create and lacks the ones they
expect. `flyway repair` only rewrites checksums and leaves that drift for `ddl-auto: validate` to
reject. Reset the schema instead — the message content lives in MongoDB, so the Postgres side is
cheap to rebuild:

```bash
docker exec discoveryhub-postgres-archive-meta psql -U archive -d archive \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO archive;"
```
