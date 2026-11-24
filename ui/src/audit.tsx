import { Typography } from 'antd';

/** How a caller proved who it is, as the audit records it; `declared` for rows written before users existed. */
export type Via = 'session' | 'token' | 'emergency-key' | 'shared-token' | 'local' | 'declared';

/** One row of the audit, as the node answers it. */
export interface AuditRow {
  id: number;
  /** When it was written, as an ISO-8601 instant. */
  at: string;
  /** What was done, such as `message.retry`, `login` or `user.roles`. */
  action: string;
  /** Who did it: a user's name, `emergency-key`, or the name a caller declared before users existed. */
  actor: string;
  via: Via;
  /** The id of the session or token, never its secret; null for the others. */
  credential: string | null;
  /** What the row records besides, such as the `step` of a body viewed. */
  detail: Record<string, unknown>;
}

export const viaLabels: Record<Via, string> = {
  session: 'Session',
  token: 'Token',
  'emergency-key': 'Emergency key',
  'shared-token': 'Shared token',
  local: 'Local, no credential',
  declared: 'Name declared, before users',
};

/** The acts the audit records, by id, in words; the order the Audit screen offers them in its filter. */
export const auditActions: Readonly<Record<string, string>> = {
  login: 'Logged in',
  'login.refused': 'Login refused',
  logout: 'Logged out',
  'password.change': 'Changed their password',
  'user.create': 'Created a user',
  'user.roles': 'Changed roles',
  'user.block': 'Blocked a user',
  'user.unblock': 'Unblocked a user',
  'user.password-reset': 'Reset a password',
  'session.end': 'Ended sessions',
  'token.issue': 'Issued a token',
  'token.revoke': 'Revoked a token',
  'token.ceiling': "Changed the ceiling of a token's life",
  'emergency-key.use': 'Used the emergency key',
  'message.body.view': 'Viewed a payload',
  'message.retry': 'Retried',
  'message.cancel': 'Cancelled',
  'work.release': 'Released held work',
  'work.discard': 'Discarded lost work',
  deploy: 'Deployed',
  undeploy: 'Undeployed',
};

/** What a row of the audit did, for people; an act this list does not know shows its id. */
export function auditAction(row: Pick<AuditRow, 'action' | 'detail'>): string {
  if (row.action === 'message.body.view' && typeof row.detail.step === 'number') return `Viewed the payload after step ${row.detail.step}`;
  return (Object.hasOwn(auditActions, row.action) ? auditActions[row.action] : undefined) ?? row.action;
}

/** How the row's caller proved who they are, with the id of their session or token when they had one. */
export function AuditHow({ row }: { row: Pick<AuditRow, 'via' | 'credential'> }) {
  return (
    <>
      {viaLabels[row.via] ?? row.via}
      {row.credential !== null && (
        <>
          {' '}
          <Typography.Text code>{row.credential}</Typography.Text>
        </>
      )}
    </>
  );
}
