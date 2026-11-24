// Test-only loopback server for the native Kotlin integration test.
//
// It runs the unchanged `buildApp(db)` Hono app against a disposable in-memory
// SQLite database seeded with explicit fixture rows, and prints its base URL as
// a single JSON line on stdout once listening. Nothing is imported by the API
// app, web app or production code: the file exists only so the JUnit test in
// apps/light-phone can exercise the real tRPC wire format over real HTTP
// without touching a deployed MealForge instance. No credentials, no external
// hosts, no root dependencies.
//
// Start it from apps/api so `--import tsx` resolves there:
//   cd apps/api && node --import tsx ../light-phone/contract/fixtureServer.ts
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import { fileURLToPath } from 'node:url';

import { buildApp } from '../../api/src/app';
import { createDb, migrateDb } from '../../api/src/db/client';
import { groceryItems, mealPlans } from '../../api/src/db/schema';

// Mirrors apps/light-phone/contract/protocol.test.ts: plan 1 is the active plan
// the native client reads; plan 2 belongs to another plan and must never leak
// into plan 1's list. Item 1 is a generated (server-aggregated) row, so
// removing it must surface the server's "Only manually added items..." refusal.
const FIXTURE_PLAN_ID = 1;
const FIXTURE_OTHER_PLAN_ID = 2;
const FIXTURE_GENERATED_ITEM_ID = 1;

// buildApp serves the built web app when WEB_DIST exists. Point it at a path
// inside this contract folder that never exists so the fixture stays tRPC-only.
process.env.WEB_DIST = fileURLToPath(new URL('./__no_web_dist__', import.meta.url));

const db = createDb(':memory:');
migrateDb(db, fileURLToPath(new URL('../../api/src/db/migrations', import.meta.url)));

db.insert(mealPlans).values({ id: FIXTURE_PLAN_ID, name: 'Fixture', status: 'active' }).run();
db.insert(mealPlans).values({ id: FIXTURE_OTHER_PLAN_ID, name: 'Other', status: 'upcoming' }).run();
db.insert(groceryItems)
  .values([
    {
      id: FIXTURE_GENERATED_ITEM_ID,
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
    const request = new Request(requestUrl, {
      method: incoming.method ?? 'GET',
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
    outgoing.writeHead(500, { 'content-type': 'application/json' });
    outgoing.end(
      JSON.stringify({
        error: { message: error instanceof Error ? error.message : 'Internal Server Error' },
      }),
    );
  }
});

server.on('error', (error) => {
  process.stderr.write(`${error instanceof Error ? error.stack : String(error)}\n`);
  process.exit(1);
});

server.listen(0, '127.0.0.1', () => {
  const { port } = server.address() as AddressInfo;
  // Single machine-readable line, emitted before the test issues any request.
  process.stdout.write(`${JSON.stringify({ url: `http://127.0.0.1:${port}` })}\n`);
});

let closing = false;
let finished = false;

function finish(): void {
  if (finished) return;
  finished = true;
  db.$client.close();
  process.exit(0);
}

function shutdown(): void {
  if (closing) return;
  closing = true;
  server.close(finish);
  // Idle keep-alive sockets would otherwise hold close() open past the
  // Kotlin test's bounded shutdown wait.
  server.closeAllConnections();
  setTimeout(finish, 2_000).unref();
}

// The Kotlin test sends SIGTERM; SIGINT keeps Ctrl-C from leaving the loopback
// listener and SQLite handle behind.
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
