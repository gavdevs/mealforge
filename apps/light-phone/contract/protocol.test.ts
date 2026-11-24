import type { Hono } from 'hono';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { buildApp } from '../../api/src/app';
import { createDb, type Db, migrateDb } from '../../api/src/db/client';
import { groceryItems, mealPlans } from '../../api/src/db/schema';

// These requests exercise the unchanged Hono/tRPC adapter and disposable SQLite, not a mock.
describe('native client single-request protocol reference', () => {
  let db: Db;
  let app: Hono;

  beforeEach(() => {
    db = createDb(':memory:');
    migrateDb(db, new URL('../../api/src/db/migrations', import.meta.url).pathname);
    app = buildApp(db);
    db.insert(mealPlans).values({ id: 1, name: 'Fixture', status: 'active' }).run();
    db.insert(mealPlans).values({ id: 2, name: 'Other', status: 'upcoming' }).run();
    db.insert(groceryItems)
      .values([
        {
          id: 1,
          planId: 1,
          name: 'Spinach',
          normalizedKey: 'spinach',
          section: 'produce',
          quantityText: '200 g',
        },
        { id: 2, planId: 2, name: 'Other plan item', normalizedKey: 'other' },
      ])
      .run();
  });

  afterEach(() => {
    db.$client.close();
  });

  async function query(procedure: string, input?: unknown): Promise<Response> {
    const search = input === undefined ? '' : `?input=${encodeURIComponent(JSON.stringify(input))}`;
    return app.request(`/trpc/${procedure}${search}`);
  }

  async function mutate(procedure: string, input: unknown): Promise<Response> {
    return app.request(`/trpc/${procedure}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(input),
    });
  }

  it('returns grocery booleans and display quantities inside result.data without a json transformer', async () => {
    const response = await query('grocery.itemsForPlan', { planId: 1 });
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({
      result: {
        data: [
          {
            id: 1,
            planId: 1,
            name: 'Spinach',
            quantityText: '200 g',
            section: 'produce',
            checked: false,
            isManual: false,
            sortOrder: 0,
          },
        ],
      },
    });
  });

  it('returns null for an absent plan and an empty array for an empty grocery list', async () => {
    expect(await (await query('plans.byId', { planId: 99 })).json()).toEqual({
      result: { data: null },
    });
    expect(await (await query('grocery.itemsForPlan', { planId: 99 })).json()).toEqual({
      result: { data: [] },
    });
  });

  it('accepts no-input queries and serializes plan dates as ISO strings', async () => {
    const response = await query('plans.active');
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({
      result: {
        data: {
          planId: 1,
          name: 'Fixture',
          status: 'active',
          completedAt: null,
          createdAt: expect.stringMatching(/^\d{4}-\d{2}-\d{2}T/),
        },
      },
    });
  });

  it('confirms check and uncheck through returned state and a subsequent server read', async () => {
    for (const checked of [true, true, false]) {
      const response = await mutate('grocery.setChecked', { itemId: 1, checked });
      expect(response.status).toBe(200);
      expect(await response.json()).toMatchObject({
        result: { data: { id: 1, planId: 1, checked } },
      });
      expect(await (await query('grocery.itemsForPlan', { planId: 1 })).json()).toMatchObject({
        result: { data: [{ id: 1, checked }] },
      });
    }
    expect(await (await query('grocery.itemsForPlan', { planId: 2 })).json()).toMatchObject({
      result: { data: [{ id: 2, checked: false }] },
    });
  });

  it('adds a trimmed manual item with optional quantity and section defaults then removes it', async () => {
    const response = await mutate('grocery.addManualItem', { planId: 1, name: '  Oats  ' });
    expect(response.status).toBe(200);
    const envelope = (await response.json()) as { result: { data: { id: number } } };
    expect(envelope).toMatchObject({
      result: {
        data: {
          planId: 1,
          name: 'Oats',
          quantityText: '',
          section: 'other',
          checked: false,
          isManual: true,
        },
      },
    });
    const id = envelope.result.data.id;
    expect(await (await query('grocery.itemsForPlan', { planId: 1 })).json()).toMatchObject({
      result: { data: [expect.anything(), expect.objectContaining({ id, name: 'Oats' })] },
    });
    expect(await (await mutate('grocery.removeManualItem', { itemId: id })).json()).toEqual({
      result: { data: { ok: true } },
    });
    expect(await (await query('grocery.itemsForPlan', { planId: 1 })).json()).toMatchObject({
      result: { data: [{ id: 1 }] },
    });
  });

  it('preserves optional manual quantity and section inputs', async () => {
    const response = await mutate('grocery.addManualItem', {
      planId: 1,
      name: 'Milk',
      quantityText: '1 tub',
      section: 'dairy-eggs',
    });
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({
      result: {
        data: {
          name: 'Milk',
          quantityText: '1 tub',
          section: 'dairy-eggs',
          isManual: true,
        },
      },
    });
  });

  it('rejects generated-item removal without deleting the item', async () => {
    const response = await mutate('grocery.removeManualItem', { itemId: 1 });
    expect(response.status).toBe(500);
    expect(await response.json()).toMatchObject({
      error: {
        message: 'Only manually added items can be removed from the list.',
        data: { code: 'INTERNAL_SERVER_ERROR', httpStatus: 500, path: 'grocery.removeManualItem' },
      },
    });
    expect(await (await query('grocery.itemsForPlan', { planId: 1 })).json()).toMatchObject({
      result: { data: [{ id: 1 }] },
    });
  });

  it('treats removal of an absent item as a successful no-op', async () => {
    const response = await mutate('grocery.removeManualItem', { itemId: 99 });
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ result: { data: { ok: true } } });
  });

  it('returns validation errors without confirming an invalid check operation', async () => {
    const response = await mutate('grocery.setChecked', { itemId: 0, checked: true });
    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({
      error: {
        data: { code: 'BAD_REQUEST', httpStatus: 400, path: 'grocery.setChecked' },
      },
    });
    expect(await (await query('grocery.itemsForPlan', { planId: 1 })).json()).toMatchObject({
      result: { data: [{ id: 1, checked: false }] },
    });
  });

  it('returns a server error rather than confirmed state for an absent check target', async () => {
    const response = await mutate('grocery.setChecked', { itemId: 99, checked: true });
    expect(response.status).toBe(500);
    expect(await response.json()).toMatchObject({
      error: {
        message: 'Grocery item 99 does not exist.',
        data: { code: 'INTERNAL_SERVER_ERROR', httpStatus: 500 },
      },
    });
  });
});
