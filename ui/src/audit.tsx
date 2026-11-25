import { Typography } from 'antd';
import { useText, type Texts } from './language';

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

/** The ways a caller proves who it is, each with the id of its words, `audit.via.<way>`. */
const VIAS: readonly Via[] = ['session', 'token', 'emergency-key', 'shared-token', 'local', 'declared'];

/**
 * The acts the audit records, by id, in the order the Audit screen offers them in its filter; the words of each are the
 * text of `audit.action.<id>`.
 */
export const AUDIT_ACTIONS: readonly string[] = [
  'login',
  'login.refused',
  'logout',
  'password.change',
  'user.create',
  'user.roles',
  'user.block',
  'user.unblock',
  'user.password-reset',
  'session.end',
  'token.issue',
  'token.revoke',
  'token.ceiling',
  'emergency-key.use',
  'message.body.view',
  'message.retry',
  'message.cancel',
  'work.release',
  'work.discard',
  'deploy',
  'undeploy',
];

/** The words of the act `id` in `texts`' language; an act the list does not know shows its id. */
export const auditActionName = ({ t }: Texts, id: string) => (AUDIT_ACTIONS.includes(id) ? t(`audit.action.${id}`) : id);

/** What a row of the audit did, for people, in `texts`' language; an act the list does not know shows its id. */
export function auditAction(texts: Texts, row: Pick<AuditRow, 'action' | 'detail'>): string {
  if (row.action === 'message.body.view' && typeof row.detail.step === 'number') return texts.t('audit.action.message.body.view.step', { step: row.detail.step });
  return auditActionName(texts, row.action);
}

/** How the row's caller proved who they are, with the id of their session or token when they had one. */
export function AuditHow({ row }: { row: Pick<AuditRow, 'via' | 'credential'> }) {
  const { t } = useText();
  return (
    <>
      {VIAS.includes(row.via) ? t(`audit.via.${row.via}`) : row.via}
      {row.credential !== null && (
        <>
          {' '}
          <Typography.Text code>{row.credential}</Typography.Text>
        </>
      )}
    </>
  );
}
