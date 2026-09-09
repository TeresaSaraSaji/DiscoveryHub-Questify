# DiscoveryHub frontend

Angular 22 UI for the eDiscovery system. Five pages on **:4200**, talking to seven ports.

| Page | Route | Services it reads |
|---|---|---|
| Dashboard | `/home` | all of them, for counts and recent activity |
| Search | `/search` | P3 `:8083` |
| Cases & Legal Hold | `/cases` | P4 case `:8084`, P4 hold `:8086`, P2.2 `:8087` |
| Retention & Disposition | `/retention` | P2.2 `:8087`, P2 `:8082` |
| Exports & Audit | `/export-audit` | P5 `:8085`, P4 case `:8084` |

P4 is two entries on purpose. case-service and hold-service are separate deployables with separate
databases, and the UI has to be able to say which of the two is down — a case list that loads while
every hold check fails is a real state, and one "P4" badge could not express it.

## Running it

```bash
cd frontend
npm install
npm start                     # http://localhost:4200
```

The services are separate processes; start whichever you want to use. **The UI works with any
subset of them running, including none.**

```bash
# from the repository root
docker compose up -d          # datastores
mvn -q package -DskipTests
for s in ingestion storage search case hold export disposition; do :; done   # see the root README
```

## Verifying

```bash
npm run check         # ng build && ng test --no-watch
```

33 tests. The ones worth knowing about:

- `pages/pages.spec.ts` — every page mounted with **every** service down, asserting it still renders
  and names what is missing. Plus the search page's whole contract: no request on load, the button
  disabled until there is a criterion, a 3s deadline on the request, and an empty result rendered
  as an answer rather than a failure.
- `core/resilience.spec.ts` — one service failing leaves the others resolved, a failed panel
  recovers on reload, and no request is issued for an id nobody has typed yet.

## Search shows nothing until you search

The index holds the entire corpus — 12,000 messages — so a screen that opens with all of them has
answered a question nobody asked, buried the one you came to ask, and spent a page of Elasticsearch
throughput doing it. P3 agrees: it rejects a criterion-less query with a 400, so the Search button
stays disabled until at least one filter is set rather than firing a request guaranteed to fail.

Every search carries a **3 second deadline** (`SEARCH_TIMEOUT_MS` in `core/search.api.ts`). Not a
performance target — a promise: within three seconds you get results or an error you can retry.
What you never get is a spinner that resolves after you have given up and clicked again.

## How it survives a service being down

**One request per panel, and the panel owns its own failure.** Every region of every page is an
`<app-panel>` wrapping one `httpResource`, rendering its own loading, error and retry state
(`shared/panel.ts`). Six panels against three services means five working and one explaining
itself. There is no global error handler and no toast.

**No route resolvers.** A resolver fetches before activating a route, so one slow service would
hold up a page whose other panels are fine. `app.routes.ts` has none, deliberately.

**`value()` is never read directly in a template.** This is the trap, and it has bitten this
codebase twice. `Resource.value()` *throws* while the resource is in its error state, and a
`defaultValue` does not change that — it only covers idle and loading. Angular instantiates
projected content eagerly, so a panel's table is evaluated even while the panel is showing a
failure instead of it, and the throw escapes as a template error and blanks the whole page. Every
list goes through `valueOr()` in `core/resource-utils.ts`, and `pages.spec.ts` mounts every page
with every service down to keep it that way.

**Everything has a deadline.** A service that is *down* fails in milliseconds; one that is *wedged*
holds a spinner forever. `core/http-timeout.interceptor.ts` gives every request 8 seconds, search
3, and package verification 30 because it re-hashes a zip.

**Live progress degrades to polling.** The disposition sweep's progress arrives over SSE. A stream
that fails before delivering anything falls back to polling `/disposition/runs/progress`, so a
buffering proxy costs latency rather than the progress bar.

### "Empty" and "could not ask" are never the same thing

A correctness property, not a UX one. Upstream, an unreachable hold-service means **held**: both P2
and P2.2 fail closed and refuse to delete. So a hold panel that rendered a failed request as "no
holds are in force" would state the exact inverse of what the system is acting on.
`core/failure.ts` classifies a refused connection (`status: 0`) as *unreachable* and names the
service and port; an empty list resolves as an empty list.

The same reasoning puts the retention page's warning next to the Run button: a sweep that skipped
every candidate because it could not verify holds looks identical to holds working correctly.

## Things the backend does that the UI has to be honest about

- **A new hold protects nothing yet.** `POST /holds` returns 202 and a `RESOLVING` hold; a worker
  expands the scope and flips it to `ACTIVE` with a `messageCount`. The page polls it and says
  "resolving" rather than showing a reassuring count of 0.
- **A hold's custodian scope never reaches the wire.** `HoldEntity` keeps custodians in a
  comma-separated column and exposes them through `custodianList()` — a method, not a getter — so
  Jackson omits the field entirely. The page says so instead of rendering an empty list.
- **Filing search results to a case is eventually consistent.** `POST /search/add-to-case`
  publishes a Kafka event that case-service consumes; the response counts what was collected, not
  what has been written. The message says "queued".
- **A closed case is read-only.** case-service refuses evidence, custodians and transitions on it
  with a 409, so those controls are disabled rather than left to fail.
- **`GET /holds/active` and `POST /holds/evidence-check` do not exist.** P2.2 calls both (they are
  specified in `DISPOSITION.md`) and hold-service answers 404 and 405. So P2.2's hold-scope guards
  fail closed and a sweep skips every candidate with `holdScopeAvailable: false`. The retention page
  reports exactly that rather than presenting it as holds working.

## Pointing it somewhere else

`public/api-config.js` is served as a static asset, not bundled, so a built `dist/` can be
repointed by editing one file:

```js
window.discoveryhubApi = { p22: 'http://p22.internal:8087' /* … */ };
```

Anything omitted falls back to the localhost default in `core/api-config.ts`.

## CORS

Every call the browser makes is cross-origin. All six services allow **any port on the loopback
host** in their own `CorsConfig`, overridable with `discoveryhub.web.cors.allowed-origins`.

Two things here were learned the hard way, and both look like the whole UI being broken:

- **Do not pin the port.** `http://localhost:4200` alone refuses an IDE preview pane, a
  `ng serve --port 4201`, and a static server over `dist/` — each a different origin, each a 403
  the browser reports as a bare network error.
- **Actuator has its own CORS.** Its endpoints are served by a separate handler mapping that
  ignores `addCorsMappings`, so `management.endpoints.web.cors.allowed-origin-patterns` is set in
  every service's `application.yml`. Miss it and the status strip shows every service DOWN while
  every panel on the page loads fine, because the strip polls `/actuator/health`.

If the whole page says "not reachable" but `curl` works, it is one of these.
