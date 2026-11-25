import type { AdminApi, Audited, Listed, StepRecord, Timeline } from '../../src/messages/admin';

const step = (n: number, name: string, startedAt: string, outcome: StepRecord['outcome'] = 'OK', error: string | null = null): StepRecord => ({
  step: n,
  attempt: 1,
  name,
  outcome,
  error,
  startedAt,
  wallNanos: 1_800_000 + n * 400_000,
  cpuNanos: n === 0 ? null : 900_000 + n * 100_000,
  body: { hash: `sha256:${'4f1c9a'.repeat(2)}${n}`, size: 412 + n * 37, kept: true },
});

const orders: Timeline = {
  id: 'm-7f3a',
  flow: 'demo/orders',
  steps: [
    step(0, 'ingress', '2026-11-23T09:50:00Z'),
    step(1, 'orders/validate', '2026-11-23T09:50:00.004Z'),
    step(2, 'orders/map', '2026-11-23T09:50:00.009Z'),
    step(3, 'orders/send', '2026-11-23T09:50:00.015Z', 'FAILED', 'the partner answered 503'),
  ],
};

/** An order that went through every step, which no one has acted on since. */
const delivered: Timeline = {
  id: 'm-7f39',
  flow: 'demo/orders',
  steps: [
    step(0, 'ingress', '2026-11-23T09:42:10Z'),
    step(1, 'orders/validate', '2026-11-23T09:42:10.004Z'),
    step(2, 'orders/map', '2026-11-23T09:42:10.009Z'),
    step(3, 'orders/send', '2026-11-23T09:42:10.015Z'),
  ],
};

const listed = (id: string, status: Listed['status'], startedAt: string, order: string): Listed => ({
  id,
  flow: 'demo/orders',
  status,
  startedAt,
  durationNanos: 16_000_000,
  keys: { order },
});

/** Who acted on the failed order: a holder of the message bodies role viewed a payload, and a technical account retried it once before. */
const audited: Audited[] = [
  { id: 41, at: '2026-11-23T09:58:12Z', action: 'message.body.view', actor: 'dirk', via: 'session', credential: '3f9c0a7d21b84e65', detail: { step: 2 } },
  { id: 37, at: '2026-11-23T09:52:40Z', action: 'message.retry', actor: 'ci-ops', via: 'token', credential: 'a1d27c9e40f3b851', detail: {} },
];

/** The messages of the list, newest first: one in each status. */
const LISTED: Listed[] = [
  listed('m-7f3b', 'running', '2026-11-23T09:51:30Z', 'A-8'),
  listed(orders.id, 'failed', '2026-11-23T09:50:00Z', 'A-7'),
  listed('m-7f39', 'completed', '2026-11-23T09:42:10Z', 'A-6'),
  listed('m-7f38', 'completed', '2026-11-23T09:31:55Z', 'A-5'),
  listed('m-7f37', 'cancelled', '2026-11-23T09:20:04Z', 'A-4'),
];

/** The messages of the pictures: a few recent orders, one in each status; the failed one failed at its last step and has an audit, the others have none. */
export function pictureMessages(): AdminApi {
  return {
    list: async (query) => ({ items: LISTED.filter((row) => query.status === undefined || row.status === query.status), next: null }),
    counts: async () => ({ completed: 2, failed: 1, running: 1, cancelled: 1 }),
    flows: async () => ['demo/orders'],
    timeline: async (id) => [orders, delivered].find((t) => t.id === id) ?? null,
    payload: async () => ({ contentType: 'application/json', text: '{"order":"A-7"}', complete: true }),
    restart: async (id) => ({ id, attempt: 2 }),
    audit: async (id) => ({ items: id === orders.id ? audited : [], next: null }),
  };
}
