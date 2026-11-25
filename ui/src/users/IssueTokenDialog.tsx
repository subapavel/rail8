import { Alert, Button, Flex, Form, Input, InputNumber, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { instant, useText } from '../language';
import { MountedModal } from '../MountedModal';
import { ReadSpinner } from '../ReadSpinner';
import type { Ceiling, Issued } from './api';
import { reasonOf } from './words';

const NAME = /^[a-z0-9][a-z0-9-]{0,63}$/;

type Read = { kind: 'reading' } | { kind: 'failed'; reason: string } | { kind: 'read'; ceiling: Ceiling };

/**
 * A new token, titled `title`: its name, its life in days within the ceiling that `read` gives, the default life
 * filled in, and, when `password` is true, the caller's password asked again. Issue sends them to `issue`; a refusal
 * shows in the node's words and the dialog stays open. The issued token then shows once, to copy, until Done.
 */
export function IssueTokenDialog({
  title,
  password,
  read,
  issue,
  onClose,
}: {
  title: string;
  password: boolean;
  read: () => Promise<Ceiling>;
  issue: (name: string, days: number, password: string) => Promise<Issued>;
  onClose: () => void;
}) {
  // read once, as the dialog opens
  const [reading] = useState(read);
  const [limits, setLimits] = useState<Read>({ kind: 'reading' });
  const [name, setName] = useState('');
  const [days, setDays] = useState<number | null>(null);
  const [given, setGiven] = useState('');
  const [sending, setSending] = useState(false);
  const [refused, setRefused] = useState<string | null>(null);
  const [issued, setIssued] = useState<Issued | null>(null);
  const texts = useText();
  const { t } = texts;

  useEffect(() => {
    let current = true;
    reading.then(
      (ceiling) => {
        if (!current) return;
        setLimits({ kind: 'read', ceiling });
        setDays(ceiling.defaultDays);
      },
      (error: unknown) => current && setLimits({ kind: 'failed', reason: reasonOf(error) }),
    );
    return () => {
      current = false;
    };
  }, [reading]);

  if (issued !== null) {
    return (
      <MountedModal
        title={t('users.token.named', { token: issued.name })}
        onCancel={onClose}
        footer={
          <Button type="primary" onClick={onClose}>
            {t('users.done')}
          </Button>
        }
      >
        <Flex vertical gap="small">
          <Typography.Text>{t('users.token.until', { token: issued.name, at: instant(texts, issued.expiresAt) })}</Typography.Text>
          <Typography.Text code copyable aria-label={t('users.token')}>
            {issued.token}
          </Typography.Text>
          <Typography.Text type="secondary">{t('users.token.once', { variable: 'RAIL8_TOKEN' })}</Typography.Text>
        </Flex>
      </MountedModal>
    );
  }

  const maxDays = limits.kind === 'read' ? limits.ceiling.maxDays : 0;
  const valid = NAME.test(name) && days !== null && days >= 1 && days <= maxDays && (!password || given !== '');

  const send = async () => {
    if (days === null) return;
    setSending(true);
    setRefused(null);
    try {
      setIssued(await issue(name, days, given));
    } catch (error) {
      setRefused(reasonOf(error));
      setSending(false);
    }
  };

  let body;
  if (limits.kind === 'reading') body = <ReadSpinner label={t('users.ceiling.reading')} />;
  else if (limits.kind === 'failed') body = <Alert type="error" showIcon title={t('users.ceiling.unread')} description={limits.reason} />;
  else {
    body = (
      <Form layout="vertical" requiredMark>
        <Form.Item
          label={t('users.name')}
          htmlFor="rail8-new-token-name"
          required
          validateStatus={name === '' || NAME.test(name) ? undefined : 'error'}
          help={t('users.token.name.help', { max: 64 })}
        >
          <Input id="rail8-new-token-name" value={name} autoComplete="off" onChange={(event) => setName(event.target.value)} />
        </Form.Item>
        <Form.Item label={t('users.token.validFor')} htmlFor="rail8-new-token-days" required help={t('users.token.days.help', { max: maxDays })}>
          <InputNumber<number>
            id="rail8-new-token-days"
            min={1}
            max={maxDays}
            precision={0}
            suffix={t('users.days.unit')}
            value={days}
            onChange={(value) => setDays(typeof value === 'number' ? value : null)}
          />
        </Form.Item>
        {password && (
          <Form.Item label={t('users.token.password')} htmlFor="rail8-new-token-password" required help={t('users.token.password.help')} style={{ marginBottom: 0 }}>
            <Input.Password id="rail8-new-token-password" value={given} autoComplete="current-password" onChange={(event) => setGiven(event.target.value)} />
          </Form.Item>
        )}
      </Form>
    );
  }

  return (
    <MountedModal
      title={title}
      onCancel={sending ? undefined : onClose}
      footer={[
        <Button key="cancel" disabled={sending} onClick={onClose}>
          {t('frame.cancel')}
        </Button>,
        <Button key="issue" type="primary" loading={sending} disabled={!valid} onClick={() => void send()}>
          {t('users.token.issue')}
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        {refused !== null && <Alert type="error" showIcon title={t('users.token.notIssued')} description={refused} />}
        {body}
      </Flex>
    </MountedModal>
  );
}
