/*
 * Where the services live, read before the app boots.
 *
 * This file is served as a static asset, not bundled, so a built `dist/` can be pointed at a
 * different host by editing it in place. Anything omitted falls back to the localhost default in
 * src/app/core/api-config.ts.
 *
 * The host is taken from whatever address served this page rather than hardcoded, because the
 * services run alongside the UI on one machine. Open the UI on localhost and the calls go to
 * localhost; open it from a teammate's laptop at http://192.168.1.50:4200 and they go to
 * 192.168.1.50, with no edit here and no rebuild. Hardcoding `localhost` sends every one of a
 * visitor's requests to their own machine, where nothing is listening, and the UI reports all
 * seven services down while the host sees a perfectly healthy stack.
 *
 * Serving the UI over HTTPS or from a different host than the services is not covered here: set
 * the URLs explicitly below if you do that.
 */
(function () {
  var host = window.location.hostname || 'localhost';
  var on = function (port) {
    return 'http://' + host + ':' + port;
  };

  window.discoveryhubApi = {
    p1: on(8081), // Ingestion
    p2: on(8082), // Archive — system of record
    p3: on(8083), // Search — Elasticsearch
    p4case: on(8084), // Case management
    p4hold: on(8086), // Legal hold
    p5: on(8085), // Export & audit
    p22: on(8087), // Disposition — retention, sweeps, ledger
  };
})();
