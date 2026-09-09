# DiscoveryHub frontend

Angular 22 UI for the eDiscovery system. Three pages on **:4200**, talking to five services on five
ports.

| Page | Route | Services it reads |
|---|---|---|
| Retention & Disposition | `/retention` | P2.2 `:8086`, P2 `:8082` |
| Case & Hold | `/cases` | P4 `:8084`, P2.2 `:8086`, P2 `:8082` |
| Export & Audit | `/export-audit` | P5 `:8085`, P2.2 `:8086` |

## Running it

```bash
cd frontend
npm install
npm start                     # http://localhost:4200
```

The services it talks to are separate processes; start whichever you want to use. **The UI works
with any subset of them running**, including none — see below.

```bash
# from the repository root
docker compose up -d                                                   # datastores
java -jar services/storage-service/target/storage-service-0.1.0-SNAPSHOT.jar          # P2  :8082
java -jar services/disposition-service/target/disposition-service-0.1.0-SNAPSHOT.jar  # P2.2 :8086
```

P4 and P5 do not exist yet. To see those pages populated:

```bash
npm run mock          # serves the P4 and P5 contracts on :8084 and :8085
npm run mock:p4       # or just one of them
```

`mock/p4-p5-mock.mjs` is a **development fixture, not a design**. It holds state in memory, has no
dependencies, and exists so the contracts are exercised by something rather than only asserted.
P2.2 also picks it up (`CASES_BASE_URL` defaults to `:8084`), so a sweep run with it up actually
performs its hold checks.

## Verifying

```bash
npm run check         # ng build && ng test --no-watch
```

27 tests. The ones worth knowing about:

- `core/resilience.spec.ts` — one service failing leaves the others resolved, a failed panel
  recovers on reload, and no request is issued for an id nobody has typed yet.
- `pages/pages.spec.ts` — each page mounted with **every** service down, asserting it still renders
  and names what is missing. This is the test that catches the failure mode described below.

## How it survives a service being down

The requirement is that a page works when a service it uses does not. Five decisions make that
true, and each of them is load-bearing:

**One request per panel, and the panel owns its own failure.** Every region of every page is an
`<app-panel>` wrapping one `httpResource`, and it renders its own loading, error and retry state
(`shared/panel.ts`). Six panels against three services means five working and one explaining
itself. There is no global error handler and no toast.

**No route resolvers.** A resolver fetches before activating a route, which would let P4 — a service
nobody has written — refuse to open a page whose other five panels are fine. `app.routes.ts` has
none, deliberately.

**`value()` is never read directly in a template.** This is the trap. `Resource.value()` *throws*
while the resource is in its error state, and a `defaultValue` does not cover that — only idle and
loading. Angular instantiates projected content eagerly, so a panel's table is evaluated even while
the panel is displaying a failure instead of it, and the throw escapes as a template error and
blanks the whole page. Every list therefore goes through `valueOr()` in `core/resource-utils.ts`.

**Everything has a deadline.** A service that is *down* fails in milliseconds; one that is *wedged*
holds a spinner forever. `core/http-timeout.interceptor.ts` gives every request 8 seconds.

**Live progress degrades to polling.** The sweep's progress arrives over SSE
(`GET /disposition/runs/stream`). A stream that fails before delivering anything falls back to
polling `/disposition/runs/progress`, so a proxy that buffers or a missing header on that one
endpoint costs latency rather than the progress bar (`core/disposition.api.ts`).

### "Empty" and "could not ask" are never the same thing

Worth stating separately, because it is a correctness property rather than a UX one. Upstream, an
unreachable P4 means **held**: both P2 and P2.2 fail closed and refuse to delete. So a hold panel
that rendered a failed request as "no holds are in force" would tell the exact inverse of what the
system is doing. `core/failure.ts` classifies a refused connection (`status: 0`) as *unreachable*
and says which service and which port, and an empty list resolves as an empty list.

The same reasoning puts the retention page's warning next to the Run button rather than in a log: a
sweep that skipped every candidate because P4 was unreachable looks identical to holds working
correctly.

## Pointing it somewhere else

`public/api-config.js` is served as a static asset, not bundled, so a built `dist/` can be
repointed by editing one file:

```js
window.discoveryhubApi = { p22: 'http://p22.internal:8086' /* … */ };
```

Anything omitted falls back to the localhost default in `core/api-config.ts`.

## CORS

Every call the browser makes is cross-origin. The three existing services allow **any port on the
loopback host** in their own `CorsConfig`, overridable with
`discoveryhub.web.cors.allowed-origins`. Without it a running service and a correct URL still fail
as a network error with no status — which the UI then reports, correctly and uselessly, as "not
reachable".

Two things here were learned the hard way, and both look like the whole UI being broken:

- **Do not pin the port.** `http://localhost:4200` alone refuses an IDE preview pane, a
  `ng serve --port 4201`, and a static server over `dist/` — every one a different origin, every
  one a 403 the browser reports as a bare network error. Localhost is already whoever is sitting
  at the machine; the port buys no security.
- **Actuator has its own CORS.** Its endpoints are served by a separate handler mapping that
  ignores `addCorsMappings`, so `management.endpoints.web.cors.allowed-origin-patterns` is set in
  each service's `application.yml` — pointed at the same property. Miss it and the status strip in
  the header shows every service DOWN while every panel on the page loads fine, because the strip
  polls `/actuator/health`.

If the whole page says "not reachable" but `curl` works, it is one of these. Check the browser
console for a CORS message and compare `document.location.origin` against the allowlist.

## The P4 and P5 contracts

Neither service exists. Nothing in this UI is stubbed to compensate: the requests go to the real
ports and fail honestly until someone ships them, at which point the pages work with no change.

- **P4** — specified by `services/disposition-service/DISPOSITION.md`, "What P4 has to provide".
  `GET /holds/active`, `GET /holds/check?messageId=`, `POST /holds/evidence-check`. P2 and P2.2
  already call these, so the UI holds P4 to the same contract rather than inventing a second one.
- **P5** — the event shape is fixed by `contracts/AuditEvent.java`; the HTTP surface is not
  specified anywhere. The paths this page uses are a *proposal*, documented at the top of
  `core/audit.api.ts`, kept thin on purpose. Two things there are asserted rather than assumed: an
  export job is asynchronous, and a completed one carries a `sha256` of its manifest.
