import { Alert, Button, Flex, Form, Input, Typography } from 'antd';
import { useId, useState } from 'react';
import { useText } from '../language';
import { MountedModal } from '../MountedModal';
import { SecretsError, type SecretEntry, type SecretsApi, type SecretUser } from './api';
import { UsedBy } from './parts';

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/** What a 409 `in-use` answer names: the users, their count, and the packages whose uses are not known, with theirs. */
interface InUse {
  usedBy: SecretUser[];
  users: number;
  mayUse: string[];
  packages: number;
}

/** The users and possible users of an `in-use` refusal's body; undefined for any other answer. */
function inUseOf(error: unknown): InUse | undefined {
  if (!(error instanceof SecretsError) || error.code !== 'in-use' || !isRecord(error.body) || !isRecord(error.body.values)) return undefined;
  const { usedBy, users, mayUse, packages } = error.body.values;
  const list = <T,>(value: unknown) => (Array.isArray(value) ? (value as T[]) : []);
  const names = list<SecretUser>(usedBy);
  const unknown = list<unknown>(mayUse).filter((p): p is string => typeof p === 'string');
  return { usedBy: names, users: typeof users === 'number' ? users : names.length, mayUse: unknown, packages: typeof packages === 'number' ? packages : unknown.length };
}

/**
 * The confirmation of a secret's delete: it names the secret's users, and when the secret has a use or a possible use (a
 * package whose uses are not known), its name must be typed out, which the node takes as `?confirm=<name>`. A secret that
 * the list showed unused is deleted without; when the node then answers that it is in use after all, the dialog names
 * the users and possible users it gave and asks for the name.
 */
export function DeleteSecretDialog({ api, entry, onClose }: { api: SecretsApi; entry: SecretEntry; onClose: (deleted: boolean) => void }) {
  const { t } = useText();
  const [inUse, setInUse] = useState<InUse | undefined>(undefined);
  const [typed, setTyped] = useState('');
  const [sending, setSending] = useState(false);
  const [refused, setRefused] = useState<string | null>(null);
  const typedId = useId();
  const { name } = entry;
  const confirming = entry.users > 0 || inUse !== undefined;
  const confirmed = !confirming || typed === name;
  const usedBy = inUse?.usedBy ?? entry.usedBy;
  const users = inUse?.users ?? entry.users;

  const remove = async () => {
    setSending(true);
    setRefused(null);
    try {
      await api.remove(name, confirming ? typed : undefined);
      onClose(true);
    } catch (error) {
      const found = inUseOf(error);
      if (found !== undefined && !confirming) setInUse(found);
      setRefused(error instanceof Error ? error.message : String(error));
      setSending(false);
    }
  };

  return (
    <MountedModal
      title={t('secrets.delete.title', { name })}
      onCancel={sending ? undefined : () => onClose(false)}
      footer={[
        <Button key="cancel" disabled={sending} onClick={() => onClose(false)}>
          {t('frame.cancel')}
        </Button>,
        <Button key="delete" danger type="primary" loading={sending} disabled={!confirmed} onClick={() => void remove()}>
          {t('secrets.delete')}
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        {refused !== null && <Alert type="error" showIcon title={t('secrets.delete.failed', { name })} description={refused} />}
        {users > 0 ? (
          <Flex vertical gap="small">
            <Typography.Text>{t('secrets.delete.users', { name, count: users })}</Typography.Text>
            <UsedBy usedBy={usedBy} users={users} />
          </Flex>
        ) : (
          inUse === undefined && <Typography.Text>{t('secrets.delete.unused', { name })}</Typography.Text>
        )}
        {inUse !== undefined && inUse.packages > 0 && (
          <Typography.Text>{t('secrets.delete.mayUse', { count: inUse.packages, packages: inUse.mayUse.join(', ') })}</Typography.Text>
        )}
        <Typography.Text type="secondary">{t('secrets.delete.gone')}</Typography.Text>
        {confirming && (
          <Form layout="vertical">
            <Form.Item label={t('secrets.delete.type', { name })} htmlFor={typedId} style={{ marginBottom: 0 }}>
              <Input id={typedId} value={typed} autoComplete="off" spellCheck={false} onChange={(event) => setTyped(event.target.value)} />
            </Form.Item>
          </Form>
        )}
      </Flex>
    </MountedModal>
  );
}
