/*
 * Where the five services live, read before the app boots.
 *
 * This file is served as a static asset, not bundled, so a built `dist/` can be pointed at a
 * different host by editing it in place. Anything omitted falls back to the localhost default in
 * src/app/core/api-config.ts.
 */
window.discoveryhubApi = {
  p1: 'http://localhost:8081', // Ingestion
  p2: 'http://localhost:8082', // Archive — system of record
  p22: 'http://localhost:8086', // Disposition — retention, sweeps, ledger
  p4: 'http://localhost:8084', // Case & Hold — not written yet
  p5: 'http://localhost:8085', // Export & Audit — not written yet
};
