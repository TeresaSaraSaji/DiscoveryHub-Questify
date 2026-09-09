/*
 * Where the services live, read before the app boots.
 *
 * This file is served as a static asset, not bundled, so a built `dist/` can be pointed at a
 * different host by editing it in place. Anything omitted falls back to the localhost default in
 * src/app/core/api-config.ts.
 */
window.discoveryhubApi = {
  p1: 'http://localhost:8081', // Ingestion
  p2: 'http://localhost:8082', // Archive — system of record
  p3: 'http://localhost:8083', // Search — Elasticsearch
  p4case: 'http://localhost:8084', // Case management
  p4hold: 'http://localhost:8086', // Legal hold
  p5: 'http://localhost:8085', // Export & audit
  p22: 'http://localhost:8087', // Disposition — retention, sweeps, ledger
};
