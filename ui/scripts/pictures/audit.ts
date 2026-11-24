import type { AuditEntry } from '../../src/users/api';
import { fakeAuditApi } from '../../src/users/fake';

const row = (id: number, at: string, action: string, actor: string, subject: string, rest: Partial<AuditEntry> = {}): AuditEntry => ({
  id,
  at,
  action,
  subject,
  actor,
  via: 'session',
  credential: actor === 'anna' ? '1a2b3c4d5e6f7081' : '0123456789abcdef',
  detail: {},
  ...rest,
});

/**
 * The audit of the pictures, newest first: the emergency key creating alice, alice setting up the others, their logins,
 * anna retrying a message and dirk reading its payload, a token of the technical account, and a refused login.
 */
export function pictureAudit() {
  const rows: AuditEntry[] = [
    row(24, '2026-11-23T09:58:12Z', 'token.issue', 'alice', 'user/ci-deploy', { detail: { token: 'fedcba9876543210', name: 'ci' } }),
    row(23, '2026-11-23T09:44:40Z', 'message.body.view', 'dirk', 'message/m-7f3a', { credential: '2b3c4d5e6f708192', detail: { step: 2 } }),
    row(22, '2026-11-23T09:41:05Z', 'message.retry', 'anna', 'message/m-7f3a'),
    row(21, '2026-11-23T09:12:30Z', 'deploy', 'ci-deploy', 'package/orders', { via: 'token', credential: 'fedcba9876543210', detail: { version: 'v12' } }),
    row(20, '2026-11-23T08:20:00Z', 'login', 'dirk', 'user/dirk', { credential: '2b3c4d5e6f708192', detail: { remote: '10.0.4.12' } }),
    row(19, '2026-11-23T08:10:00Z', 'login', 'alice', 'user/alice', { detail: { remote: '10.0.4.7' } }),
    row(18, '2026-11-23T08:02:51Z', 'login.refused', 'carla', 'user/carla', { via: 'local', credential: null, detail: { reason: 'blocked', remote: '10.0.4.31' } }),
    row(17, '2026-11-23T07:45:00Z', 'login', 'anna', 'user/anna', { detail: { remote: '10.0.4.9' } }),
    row(16, '2026-11-22T17:00:00Z', 'user.block', 'alice', 'user/carla'),
    row(15, '2026-11-22T16:20:11Z', 'session.end', 'anna', 'user/anna', { credential: '9f8e7d6c5b4a3921' }),
    row(14, '2026-11-22T13:00:00Z', 'login', 'anna', 'user/anna', { credential: '9f8e7d6c5b4a3921', detail: { remote: '10.0.4.9' } }),
    row(13, '2026-11-21T14:00:00Z', 'token.issue', 'alice', 'user/alice', { detail: { token: '7e8f9a0b1c2d3e4f', name: 'scripts' } }),
    row(12, '2026-11-21T10:00:00Z', 'user.roles', 'alice', 'user/ben', { detail: { added: ['developer'], removed: [] } }),
    row(11, '2026-11-21T08:30:00Z', 'user.roles', 'dirk', 'user/dirk', { credential: '2b3c4d5e6f708192', detail: { added: ['message-bodies'], removed: [] } }),
    row(10, '2026-11-20T09:21:00Z', 'token.issue', 'alice', 'user/ci-deploy', { detail: { token: '0f1e2d3c4b5a6978', name: 'nightly' } }),
    row(9, '2026-11-20T09:20:00Z', 'user.create', 'alice', 'user/ci-deploy', { detail: { kind: 'technical', roles: ['developer'] } }),
    row(8, '2026-11-20T09:15:00Z', 'user.create', 'alice', 'user/dirk', { detail: { kind: 'person', roles: ['administrator'] } }),
    row(7, '2026-11-20T09:10:00Z', 'user.create', 'alice', 'user/carla', { detail: { kind: 'person', roles: ['developer'] } }),
    row(6, '2026-11-20T09:05:00Z', 'user.create', 'alice', 'user/anna', { detail: { kind: 'person', roles: ['operator'] } }),
    row(5, '2026-11-20T08:30:00Z', 'token.issue', 'alice', 'user/alice', { detail: { token: '3c1d9e0f2a4b5c6d', name: 'laptop' } }),
    row(4, '2026-11-20T08:12:00Z', 'password.change', 'alice', 'user/alice'),
    row(3, '2026-11-20T08:10:00Z', 'login', 'alice', 'user/alice', { detail: { remote: '10.0.4.7' } }),
    row(2, '2026-11-20T08:00:00Z', 'user.create', 'emergency-key', 'user/alice', { via: 'emergency-key', credential: null, detail: { kind: 'person', roles: ['administrator'] } }),
    row(1, '2026-11-20T07:59:30Z', 'emergency-key.use', 'emergency-key', 'emergency-key', { via: 'emergency-key', credential: null }),
  ];
  return fakeAuditApi(rows, 15);
}
