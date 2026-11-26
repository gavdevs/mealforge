// Test-only loopback server for the native Kotlin integration tests.
//
// It runs the unchanged `buildApp(db)` Hono app against a disposable in-memory
// SQLite database seeded with explicit fixture rows, and prints its base URL as
// a single JSON line on stdout once listening. Nothing is imported by the API
// app, web app or production code: the file exists only so the JUnit tests in
// apps/light-phone can exercise the real tRPC wire format over real HTTP
// without touching a deployed MealForge instance. No credentials, no external
// hosts, no root dependencies.
//
// Next to the real app it exposes a tiny `/__fixture/*` control API on the same
// loopback listener. That is a test-only bridge, not a server feature: it can
// pause one named request (so duplicate-tap and late-response behavior is
// deterministic), release it, and report the requests received so far. The
// control paths are consumed by the bridge and never reach the Hono app.
//
// Start it from apps/api so `--import tsx` resolves there:
//   cd apps/api && node --import tsx ../light-phone/contract/fixtureServer.ts
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import { fileURLToPath } from 'node:url';

import { buildApp } from '../../api/src/app';
import { createDb, migrateDb } from '../../api/src/db/client';
import {
  groceryItems,
  mealPlans,
  meals,
  recipeIngredients,
  recipes,
} from '../../api/src/db/schema';

// Shared fixture contract (see docs/light-phone-contracts.md §8 and the plan
// checkpoint handoff):
//
// plans   1 Fixture (active), 2 Other (upcoming), 3 unnamed (upcoming),
//         4 Weekend (favorite, completed with completedAt)
// recipes 1 Lemon chicken (not favorite), 2 Bean bowls (favorite, newer)
// meals   1 plan1/recipe1 dinner, 2 plan1/recipe2 lunch, 3 plan2/recipe1 dinner,
//         4 plan3/recipe2 lunch, 5 plan4/recipe2 lunch cooked
// grocery 1 plan1 Spinach (generated), 2 plan2 Other plan item (generated)
const FIXTURE_PLAN_ID = 1;
const FIXTURE_OTHER_PLAN_ID = 2;

const at = (iso: string): Date => new Date(iso);

// buildApp serves the built web app when WEB_DIST exists. Point it at a path
// inside this contract folder that never exists so the fixture stays tRPC-only.
process.env.WEB_DIST = fileURLToPath(new URL('./__no_web_dist__', import.meta.url));

const db = createDb(':memory:');
migrateDb(db, fileURLToPath(new URL('../../api/src/db/migrations', import.meta.url)));

db.insert(recipes)
  .values([
    {
      id: 1,
      title: 'Lemon chicken',
      description: 'Bright roast',
      servings: 4,
      prepMinutes: 10,
      cookMinutes: 25,
      tags: ['bright', 'quick'],
      mealTypes: ['dinner'],
      stepsMarkdown: '1. Season chicken\nwith herbs\n\n2) Cook for six minutes.\n3. Serve.',
      isFavorite: false,
      createdAt: at('2026-01-01T09:00:00.000Z'),
      updatedAt: at('2026-01-01T09:00:00.000Z'),
    },
    {
      id: 2,
      title: 'Bean bowls',
      description: '',
      servings: 2,
      prepMinutes: null,
      cookMinutes: 15,
      tags: ['pantry'],
      mealTypes: ['lunch', 'dinner'],
      stepsMarkdown: 'Warm beans.\n\nServe in bowls.',
      isFavorite: true,
      createdAt: at('2026-01-02T09:00:00.000Z'),
      updatedAt: at('2026-01-02T09:00:00.000Z'),
    },
  ])
  .run();

db.insert(recipeIngredients)
  .values([
    {
      id: 1,
      recipeId: 1,
      name: 'Lemon',
      quantity: 2,
      unit: null,
      section: 'produce',
      sortOrder: 0,
    },
    {
      id: 2,
      recipeId: 1,
      name: 'Parsley',
      quantity: null,
      unit: null,
      section: 'produce',
      sortOrder: 1,
    },
    {
      id: 3,
      recipeId: 1,
      name: 'Chicken',
      quantity: 600,
      unit: 'g',
      section: 'meat-seafood',
      sortOrder: 2,
    },
    {
      id: 4,
      recipeId: 2,
      name: 'Beans',
      quantity: 1,
      unit: 'tin',
      section: 'pantry',
      sortOrder: 0,
    },
  ])
  .run();

db.insert(mealPlans)
  .values([
    {
      id: FIXTURE_PLAN_ID,
      name: 'Fixture',
      status: 'active',
      isFavorite: false,
      createdAt: at('2026-01-01T00:00:00.000Z'),
      updatedAt: at('2026-01-01T00:00:00.000Z'),
    },
    {
      id: FIXTURE_OTHER_PLAN_ID,
      name: 'Other',
      status: 'upcoming',
      isFavorite: false,
      createdAt: at('2026-01-02T00:00:00.000Z'),
      updatedAt: at('2026-01-02T00:00:00.000Z'),
    },
    {
      id: 3,
      name: null,
      status: 'upcoming',
      isFavorite: false,
      createdAt: at('2026-01-03T00:00:00.000Z'),
      updatedAt: at('2026-01-03T00:00:00.000Z'),
    },
    {
      id: 4,
      name: 'Weekend',
      status: 'completed',
      isFavorite: true,
      completedAt: at('2026-02-01T12:00:00.000Z'),
      createdAt: at('2026-01-04T00:00:00.000Z'),
      updatedAt: at('2026-02-01T12:00:00.000Z'),
    },
  ])
  .run();

db.insert(meals)
  .values([
    { id: 1, planId: FIXTURE_PLAN_ID, recipeId: 1, mealType: 'dinner', sortOrder: 0 },
    { id: 2, planId: FIXTURE_PLAN_ID, recipeId: 2, mealType: 'lunch', sortOrder: 1 },
    { id: 3, planId: FIXTURE_OTHER_PLAN_ID, recipeId: 1, mealType: 'dinner', sortOrder: 0 },
    { id: 4, planId: 3, recipeId: 2, mealType: 'lunch', sortOrder: 0 },
    {
      id: 5,
      planId: 4,
      recipeId: 2,
      mealType: 'lunch',
      sortOrder: 0,
      cookedAt: at('2026-02-01T18:00:00.000Z'),
    },
  ])
  .run();

db.insert(groceryItems)
  .values([
    {
      id: 1,
      planId: FIXTURE_PLAN_ID,
      name: 'Spinach',
      normalizedKey: 'spinach',
      section: 'produce',
      quantityText: '200 g',
    },
    { id: 2, planId: FIXTURE_OTHER_PLAN_ID, name: 'Other plan item', normalizedKey: 'other' },
  ])
  .run();

const app = buildApp(db);

interface RecordedRequest {
  method: string;
  path: string;
  query: string;
  body: string;
}

interface GateState {
  procedure: string;
  method: string;
  started: boolean;
  released: boolean;
  held: Promise<void>;
  open: () => void;
}

const requestLog: RecordedRequest[] = [];
let gate: GateState | null = null;

function jsonResponse(outgoing: ServerResponse, status: number, value: unknown): void {
  const payload = Buffer.from(JSON.stringify(value));
  try {
    outgoing.writeHead(status, {
      'content-type': 'application/json; charset=utf-8',
      'content-length': String(payload.byteLength),
    });
    outgoing.end(payload);
  } catch {
    // The test may have closed the connection while a gate was held.
  }
}

/** Names the one request the bridge should hold before handing it to Hono. */
function armGate(procedure: string, method: string): GateState {
  gate?.open();
  const { promise, resolve } = Promise.withResolvers<void>();
  const state: GateState = {
    procedure,
    method,
    started: false,
    released: false,
    held: promise,
    open: () => {
      state.released = true;
      resolve();
    },
  };
  gate = state;
  return state;
}

function releaseGate(): void {
  gate?.open();
  gate = null;
}

/** Bridge-safe copy: the gate's resolver must never be serialized. */
function gateStatus(): {
  procedure: string | null;
  method: string | null;
  started: boolean;
  released: boolean;
} {
  if (!gate) return { procedure: null, method: null, started: false, released: false };
  return {
    procedure: gate.procedure,
    method: gate.method,
    started: gate.started,
    released: gate.released,
  };
}

function requestHeaders(incoming: IncomingMessage): Headers {
  const headers = new Headers();
  for (const [name, value] of Object.entries(incoming.headers)) {
    if (value === undefined) continue;
    if (Array.isArray(value)) {
      for (const entry of value) headers.append(name, entry);
    } else {
      headers.set(name, value);
    }
  }
  return headers;
}

async function requestBody(incoming: IncomingMessage): Promise<Buffer | undefined> {
  if (incoming.method === 'GET' || incoming.method === 'HEAD') return undefined;
  const chunks: Buffer[] = [];
  for await (const chunk of incoming) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  return Buffer.concat(chunks);
}

const server = createServer(async (incoming: IncomingMessage, outgoing: ServerResponse) => {
  try {
    const body = await requestBody(incoming);
    const requestUrl = `http://${incoming.headers.host ?? '127.0.0.1'}${incoming.url ?? '/'}`;
    const parsed = new URL(requestUrl);
    const method = incoming.method ?? 'GET';

    // Test-only bridge endpoints; they never reach the API app.
    if (parsed.pathname === '/__fixture/gate') {
      const input = body
        ? (JSON.parse(body.toString('utf8')) as { procedure?: unknown; method?: unknown })
        : {};
      const procedure = typeof input.procedure === 'string' ? input.procedure : '';
      const gateMethod = typeof input.method === 'string' ? input.method : 'POST';
      if (!procedure) {
        jsonResponse(outgoing, 400, { error: 'procedure is required' });
        return;
      }
      armGate(procedure, gateMethod);
      jsonResponse(outgoing, 200, { ok: true, procedure, method: gateMethod });
      return;
    }
    if (parsed.pathname === '/__fixture/status') {
      jsonResponse(outgoing, 200, gateStatus());
      return;
    }
    if (parsed.pathname === '/__fixture/release') {
      releaseGate();
      jsonResponse(outgoing, 200, { ok: true });
      return;
    }
    if (parsed.pathname === '/__fixture/requests') {
      jsonResponse(outgoing, 200, { requests: requestLog });
      return;
    }
    if (parsed.pathname === '/__fixture/longContent' && method === 'POST') {
      const description =
        'Long fixture description for readable wrapping on the Light Phone. '.repeat(12);
      const steps = [
        '# Before cooking',
        'Keep **raw HTML** inert: <script>fixture only</script>.',
        '',
        '1. Gently fold the chopped herbs into the vegetables. '.repeat(8),
        '2. Serve with _fresh lemon_ and `warm beans`.',
      ].join('\n');
      db.$client
        .prepare('UPDATE recipes SET description = ?, steps_markdown = ? WHERE id = 1')
        .run(description, steps);
      db.$client
        .prepare('UPDATE recipe_ingredients SET name = ? WHERE id = 1')
        .run(
          'Fresh lemon with an intentionally long ingredient description that must wrap without hiding the quantity',
        );
      jsonResponse(outgoing, 200, { ok: true, recipeId: 1 });
      return;
    }

    const procedure = parsed.pathname.startsWith('/trpc/')
      ? parsed.pathname.slice('/trpc/'.length)
      : null;
    if (procedure !== null) {
      requestLog.push({
        method,
        path: parsed.pathname,
        query: parsed.search,
        body: body?.toString('utf8') ?? '',
      });
    }

    if (
      procedure !== null &&
      gate !== null &&
      !gate.started &&
      gate.procedure === procedure &&
      gate.method === method
    ) {
      gate.started = true;
      await gate.held;
    }

    const request = new Request(requestUrl, {
      method,
      headers: requestHeaders(incoming),
      ...(body === undefined ? {} : { body }),
    });
    const response = await app.fetch(request);
    const payload = Buffer.from(await response.arrayBuffer());
    const headers: Record<string, string | string[]> = {};
    response.headers.forEach((value, name) => {
      headers[name] = value;
    });
    headers['content-length'] = String(payload.byteLength);
    outgoing.writeHead(response.status, headers);
    outgoing.end(payload);
  } catch (error) {
    try {
      outgoing.writeHead(500, { 'content-type': 'application/json' });
      outgoing.end(
        JSON.stringify({
          error: { message: error instanceof Error ? error.message : 'Internal Server Error' },
        }),
      );
    } catch {
      // Connection already gone; nothing to report to.
    }
  }
});

server.on('error', (error) => {
  process.stderr.write(`${error instanceof Error ? error.stack : String(error)}\n`);
  process.exit(1);
});
// UIAutomator dumps can leave Android's adb-reversed connection idle between
// actions. Keep this disposable fixture alive across those slow interactions.
server.keepAliveTimeout = 120_000;

server.listen(0, '127.0.0.1', () => {
  const { port } = server.address() as AddressInfo;
  // Single machine-readable line, emitted before the tests issue any request.
  process.stdout.write(`${JSON.stringify({ url: `http://127.0.0.1:${port}` })}\n`);
});

let closing = false;
let finished = false;

function finish(): void {
  if (finished) return;
  finished = true;
  releaseGate();
  db.$client.close();
  process.exit(0);
}

function shutdown(): void {
  if (closing) return;
  closing = true;
  server.close(finish);
  // Idle keep-alive sockets would otherwise hold close() open past the
  // Kotlin tests' bounded shutdown wait.
  server.closeAllConnections();
  setTimeout(finish, 2_000).unref();
}

// The Kotlin tests send SIGTERM; SIGINT keeps Ctrl-C from leaving the loopback
// listener and SQLite handle behind.
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
