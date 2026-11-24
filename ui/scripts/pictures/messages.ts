import type { AdminApi, Listed, StepRecord, Timeline } from '../../src/messages/admin';

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

const listed = (id: string, status: Listed['status'], startedAt: string, order: string): Listed => ({
  id,
  flow: 'demo/orders',
  status,
  startedAt,
  durationNanos: 16_000_000,
  keys: { order },
});

/** The messages of the pictures: a few recent orders, the first of which failed at its last step. */
export function pictureMessages(): AdminApi {
  return {
    list: async () => ({
      items: [
        listed(orders.id, 'failed', '2026-11-23T09:50:00Z', 'A-7'),
        listed('m-7f39', 'completed', '2026-11-23T09:42:10Z', 'A-6'),
        listed('m-7f38', 'completed', '2026-11-23T09:31:55Z', 'A-5'),
      ],
      next: null,
    }),
    counts: async () => ({ completed: 2, failed: 1, running: 0, cancelled: 0 }),
    flows: async () => ['demo/orders'],
    timeline: async (id) => (id === orders.id ? orders : null),
    payload: async () => ({ contentType: 'application/json', text: '{"order":"A-7"}', complete: true }),
    restart: async (id) => ({ id, attempt: 2 }),
  };
}
