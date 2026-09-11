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

Datastores in Docker, services from jars. **Skip `mongodb`** — no service claims it, and a stale
container of that name tends to block `docker compose up`:

```bash
docker compose up -d kafka kafka-init redis postgres-archive postgres-cases \
  postgres-holds postgres-audit postgres-disposition elasticsearch minio minio-init

for s in ingestion storage search case hold export disposition; do
  java -jar services/$s-service/target/$s-service-0.1.0-SNAPSHOT.jar &
done
```

Ports: 8081 P1, 8082 P2, 8083 P3, 8084 P4 case, 8085 P5, 8086 P4 hold, **8087 P2.2**, 4200 UI.

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
docker compose up -d kafka kafka-init redis postgres-archive postgres-cases \
  postgres-holds postgres-audit postgres-disposition elasticsearch minio minio-init
./infra/serve-lan.sh
```

Two things make that work, and both are configuration rather than code:

- `frontend/public/api-config.js` derives the service host from `window.location.hostname`, so a
  visitor's browser calls the host's services instead of its own empty machine.
- `DISCOVERYHUB_WEB_CORS_ALLOWED_ORIGINS` adds the host's LAN address to the allowed origins.
  `@Value` and the actuator placeholder both resolve it from the environment, so the one variable
  covers the panels and the status strip. No `CorsConfig` needs editing.

The UI is served by `ng serve --host 0.0.0.0`. There is no authentication anywhere in the stack, so
whoever can reach the port can delete a case — keep this on a network you trust.

## Traps that have cost real time

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

**`GET /holds/active` and `POST /holds/evidence-check` do not exist** on hold-service, though
`DISPOSITION.md` specifies them and P2.2 calls both. They answer 404 and 405, so P2.2 fails closed
and a sweep skips every candidate with `holdScopeAvailable: false`. A sweep that deleted nothing is
therefore not evidence that holds work.
