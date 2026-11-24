import type { User } from '../../src/users/api';
import { fakeUsersApi } from '../../src/users/fake';

const person = (name: string, rest: Partial<User>): User => ({
  name,
  kind: 'person',
  roles: [],
  blockedAt: null,
  firstPassword: false,
  createdAt: '2026-11-20T09:00:00Z',
  createdBy: 'alice',
  sessions: [],
  tokens: [],
  ...rest,
});

/** The users of the pictures, acted on by alice at `now`: two administrators, one of whom gave themselves the message bodies role, two people, a blocked one and a technical account. */
export function pictureUsers(now: () => Date) {
  const users: User[] = [
    person('alice', {
      roles: [
        { role: 'administrator', grantedAt: '2026-11-20T08:00:00Z', grantedBy: 'emergency-key', grantedVia: 'emergency-key' },
      ],
      createdAt: '2026-11-20T08:00:00Z',
      createdBy: 'emergency-key',
      sessions: [{ id: '0123456789abcdef', createdAt: '2026-11-23T08:10:00Z', lastSeenAt: '2026-11-23T09:58:00Z', expiresAt: '2026-11-23T20:10:00Z' }],
    }),
    person('anna', {
      roles: [{ role: 'operator', grantedAt: '2026-11-20T09:05:00Z', grantedBy: 'alice', grantedVia: 'session' }],
      sessions: [
        { id: '1a2b3c4d5e6f7081', createdAt: '2026-11-23T07:45:00Z', lastSeenAt: '2026-11-23T09:40:00Z', expiresAt: '2026-11-23T19:45:00Z' },
        { id: '9f8e7d6c5b4a3921', createdAt: '2026-11-22T13:00:00Z', lastSeenAt: '2026-11-22T16:20:00Z', expiresAt: '2026-11-23T01:00:00Z' },
      ],
    }),
    person('ben', {
      roles: [
        { role: 'developer', grantedAt: '2026-11-21T10:00:00Z', grantedBy: 'alice', grantedVia: 'session' },
        { role: 'operator', grantedAt: '2026-11-19T15:00:00Z', grantedBy: 'ops', grantedVia: 'shared-token' },
      ],
      firstPassword: true,
      createdAt: '2026-11-19T15:00:00Z',
      createdBy: 'ops',
    }),
    person('carla', { roles: [{ role: 'developer', grantedAt: '2026-11-20T09:10:00Z', grantedBy: 'alice', grantedVia: 'session' }], blockedAt: '2026-11-22T17:00:00Z' }),
    person('dirk', {
      roles: [
        { role: 'administrator', grantedAt: '2026-11-20T09:15:00Z', grantedBy: 'alice', grantedVia: 'session' },
        { role: 'message-bodies', grantedAt: '2026-11-21T08:30:00Z', grantedBy: 'dirk', grantedVia: 'session' },
      ],
    }),
    person('ci-deploy', {
      kind: 'technical',
      roles: [{ role: 'developer', grantedAt: '2026-11-20T09:20:00Z', grantedBy: 'alice', grantedVia: 'session' }],
      tokens: [{ id: 'fedcba9876543210', name: 'ci', createdAt: '2026-11-20T09:21:00Z', createdBy: 'alice', expiresAt: '2027-02-18T09:21:00Z', lastUsedAt: '2026-11-23T06:00:00Z' }],
    }),
  ];
  return fakeUsersApi({ users, firstPasswords: [] }, 'alice', now);
}
