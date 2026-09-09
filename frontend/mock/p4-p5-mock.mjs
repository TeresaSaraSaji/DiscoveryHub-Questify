#!/usr/bin/env node
/*
 * A stand-in for P4 Case & Hold (:8084) and P5 Export & Audit (:8085).
 *
 * DEVELOPMENT ONLY. This is a fixture, not a service: state lives in memory and dies with the
 * process, and nothing here is a hint about how P4 or P5 should be built. It exists so the Case
 * and Export/Audit pages can be developed and demonstrated before those services are written, and
 * so the contracts in `src/app/core/cases.api.ts` and `src/app/core/audit.api.ts` are exercised by
 * something rather than only asserted.
 *
 * The two ports are separate servers on purpose: kill one and the pages must still work against
 * the other. That is the behaviour the UI is built for, and this is how it gets tested.
 *
 *   node mock/p4-p5-mock.mjs           both
 *   node mock/p4-p5-mock.mjs --only p4
 *
 * No dependencies, deliberately: `npm run mock` should not be able to fail because of an install.
 */
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';

const ORIGINS = ['http://localhost:4200', 'http://127.0.0.1:4200'];
const CASE_PORT = Number(process.env.P4_PORT ?? 8084);
const AUDIT_PORT = Number(process.env.P5_PORT ?? 8085);

// ---------------------------------------------------------------- fixture state

const HOLDS = [
  {
    holdId: 'hold-1',
    caseId: 'case-1',
    caseName: 'SEC Inquiry 2026',
    custodianIds: ['cust-004', 'cust-017'],
    from: '2019-01-01T00:00:00Z',
    to: '2021-12-31T23:59:59Z',
    terms: ['project atlas'],
  },
  {
    // An empty custodian list means every custodian, and a null range means unbounded. Both are
    // in the contract and both are easy to get wrong, so the fixture includes one.
    holdId: 'hold-2',
    caseId: 'case-2',
    caseName: 'Wrongful Termination — Okafor',
    custodianIds: [],
    from: null,
    to: null,
  },
];

/** messageId -> the hold protecting it as case evidence, regardless of the hold's own scope. */
const EVIDENCE = new Map([
  ['msg-evidence-1', { holdId: 'hold-1', caseId: 'case-1', caseName: 'SEC Inquiry 2026' }],
  [
    'msg-evidence-2',
    { holdId: 'hold-2', caseId: 'case-2', caseName: 'Wrongful Termination — Okafor' },
  ],
]);

/** Deterministic so a page reload does not change a message's hold status under you. */
const heldByScope = (messageId) => /[048]$/.test(messageId);

const exports_ = [];
const auditEvents = [];

function audit(service, action, outcome, subjectType, subjectId, detail = {}) {
  auditEvents.unshift({
    eventId: randomUUID(),
    occurredAt: new Date().toISOString(),
    service,
    action,
    outcome,
    subjectType,
    subjectId,
    actor: 'investigator',
    correlationId: randomUUID(),
    detail,
  });
}

audit('P4', 'hold.placed', 'SUCCESS', 'case', 'case-1', { holdId: 'hold-1', custodians: '2' });
audit('P4', 'hold.placed', 'SUCCESS', 'case', 'case-2', { holdId: 'hold-2', custodians: 'all' });
audit('P2.2', 'disposition.refused', 'REFUSED', 'message', 'msg-evidence-1', {
  reason: 'held-case evidence',
  blockingCaseId: 'case-1',
});
audit('P1', 'message.ingested', 'SUCCESS', 'message', 'msg-0004', { source: 'exchange' });
audit('P1', 'message.deduped', 'SUCCESS', 'message', 'msg-0004', { reason: 'external id seen' });

// ---------------------------------------------------------------- plumbing

const json = (res, status, body) => {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload),
  });
  res.end(payload);
};

const page = (items, url) => {
  const number = Number(url.searchParams.get('page') ?? 0);
  const size = Math.min(Number(url.searchParams.get('size') ?? 20), 200);
  const content = items.slice(number * size, number * size + size);
  const totalPages = Math.max(1, Math.ceil(items.length / size));
  return {
    content,
    number,
    size,
    totalElements: items.length,
    totalPages,
    first: number === 0,
    last: number >= totalPages - 1,
  };
};

const readBody = (req) =>
  new Promise((resolve) => {
    let raw = '';
    req.on('data', (chunk) => (raw += chunk));
    req.on('end', () => {
      try {
        resolve(raw ? JSON.parse(raw) : {});
      } catch {
        resolve({});
      }
    });
  });

/** Same allowance the three real services grant in their `CorsConfig`. */
function cors(req, res) {
  const origin = req.headers.origin;
  res.setHeader('access-control-allow-origin', ORIGINS.includes(origin) ? origin : ORIGINS[0]);
  res.setHeader('access-control-allow-methods', 'GET,POST,PUT,DELETE,OPTIONS');
  res.setHeader('access-control-allow-headers', '*');
  res.setHeader('vary', 'origin');
  if (req.method === 'OPTIONS') {
    res.writeHead(204).end();
    return true;
  }
  return false;
}

function serve(port, name, route) {
  createServer(async (req, res) => {
    if (cors(req, res)) {
      return;
    }
    const url = new URL(req.url, `http://localhost:${port}`);
    if (url.pathname === '/actuator/health') {
      json(res, 200, { status: 'UP', components: { mock: { status: 'UP' } } });
      return;
    }
    try {
      if (!(await route(req, res, url))) {
        json(res, 404, {
          status: 404,
          error: 'Not Found',
          message: `no handler for ${url.pathname}`,
          path: url.pathname,
        });
      }
    } catch (error) {
      json(res, 500, { status: 500, error: 'Internal Server Error', message: String(error) });
    }
  }).listen(port, () => console.log(`  ${name} mock  →  http://localhost:${port}`));
}

// ---------------------------------------------------------------- P4 Case & Hold

const caseRoutes = async (req, res, url) => {
  if (req.method === 'GET' && url.pathname === '/holds/active') {
    json(res, 200, HOLDS);
    return true;
  }

  if (req.method === 'GET' && url.pathname === '/holds/check') {
    const messageId = url.searchParams.get('messageId');
    if (!messageId) {
      json(res, 400, { status: 400, message: 'messageId is required' });
      return true;
    }
    // Note that evidence membership does *not* make this true. That gap is exactly why
    // /holds/evidence-check exists, so the fixture preserves it.
    json(res, 200, { held: heldByScope(messageId) });
    return true;
  }

  if (req.method === 'POST' && url.pathname === '/holds/evidence-check') {
    const body = await readBody(req);
    const ids = Array.isArray(body.messageIds) ? body.messageIds : [];
    json(
      res,
      200,
      ids.filter((id) => EVIDENCE.has(id)).map((id) => ({ messageId: id, ...EVIDENCE.get(id) })),
    );
    return true;
  }

  return false;
};

// ---------------------------------------------------------------- P5 Export & Audit

const auditRoutes = async (req, res, url) => {
  if (req.method === 'POST' && url.pathname === '/exports') {
    const body = await readBody(req);
    if (!body.caseId) {
      json(res, 400, { status: 400, message: 'caseId is required' });
      return true;
    }
    const job = {
      exportId: randomUUID(),
      caseId: body.caseId,
      format: body.format ?? 'PST',
      status: 'QUEUED',
      requestedAt: new Date().toISOString(),
      completedAt: null,
      messageCount: 0,
      sha256: null,
      sizeBytes: null,
      requestedBy: body.requestedBy ?? 'investigator',
      error: null,
    };
    exports_.unshift(job);
    audit('P5', 'export.requested', 'SUCCESS', 'export', job.exportId, {
      caseId: job.caseId,
      format: job.format,
    });

    // Asynchronous on purpose: the UI polls a job to completion, and a mock that answered
    // COMPLETED immediately would never exercise that path.
    setTimeout(() => {
      job.status = 'RUNNING';
      job.messageCount = 128;
    }, 1500);
    setTimeout(() => {
      job.status = 'COMPLETED';
      job.completedAt = new Date().toISOString();
      job.messageCount = 412;
      job.sizeBytes = 18_452_992;
      job.sha256 = randomUUID().replaceAll('-', '').repeat(2).slice(0, 64);
      audit('P5', 'export.completed', 'SUCCESS', 'export', job.exportId, {
        messageCount: String(job.messageCount),
        sha256: job.sha256,
      });
    }, 5000);

    json(res, 202, job);
    return true;
  }

  if (req.method === 'GET' && url.pathname === '/exports') {
    json(res, 200, page(exports_, url));
    return true;
  }

  const one = /^\/exports\/([^/]+)$/.exec(url.pathname);
  if (req.method === 'GET' && one) {
    const job = exports_.find((candidate) => candidate.exportId === one[1]);
    job ? json(res, 200, job) : json(res, 404, { status: 404, message: `no export ${one[1]}` });
    return true;
  }

  const pkg = /^\/exports\/([^/]+)\/package$/.exec(url.pathname);
  if (req.method === 'GET' && pkg) {
    const job = exports_.find((candidate) => candidate.exportId === pkg[1]);
    if (!job || job.status !== 'COMPLETED') {
      json(res, 409, { status: 409, message: 'export is not complete' });
      return true;
    }
    const body = `DiscoveryHub export ${job.exportId}\ncase: ${job.caseId}\nmanifest sha256: ${job.sha256}\n`;
    res.writeHead(200, {
      'content-type': 'application/octet-stream',
      'content-disposition': `attachment; filename="${job.caseId}-${job.format}.txt"`,
    });
    res.end(body);
    return true;
  }

  if (req.method === 'GET' && url.pathname === '/audit/events') {
    const wanted = {
      service: url.searchParams.get('service'),
      action: url.searchParams.get('action'),
      outcome: url.searchParams.get('outcome'),
      subjectId: url.searchParams.get('subjectId'),
    };
    const matches = auditEvents.filter(
      (event) =>
        (!wanted.service || event.service.toLowerCase().includes(wanted.service.toLowerCase())) &&
        (!wanted.action || event.action.includes(wanted.action)) &&
        (!wanted.outcome || event.outcome === wanted.outcome) &&
        (!wanted.subjectId || event.subjectId === wanted.subjectId),
    );
    json(res, 200, page(matches, url));
    return true;
  }

  const subject = /^\/audit\/events\/([^/]+)$/.exec(url.pathname);
  if (req.method === 'GET' && subject) {
    const id = decodeURIComponent(subject[1]);
    json(
      res,
      200,
      auditEvents
        .filter((event) => event.subjectId === id)
        .slice()
        .reverse(),
    );
    return true;
  }

  return false;
};

// ---------------------------------------------------------------- go

const only = process.argv.includes('--only')
  ? process.argv[process.argv.indexOf('--only') + 1]
  : null;

console.log('DEVELOPMENT FIXTURE — not P4 or P5, and not a design for either.');
if (only !== 'p5') {
  serve(CASE_PORT, 'P4 Case & Hold ', caseRoutes);
}
if (only !== 'p4') {
  serve(AUDIT_PORT, 'P5 Export/Audit', auditRoutes);
}
console.log('  Stop one of them to watch the pages degrade panel by panel.');
