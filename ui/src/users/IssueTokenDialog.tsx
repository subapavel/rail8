import { Alert, Button, Flex, Form, Input, InputNumber, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { MountedModal } from '../MountedModal';
import { ReadSpinner } from '../ReadSpinner';
import type { Ceiling, Issued } from './api';
import { reasonOf, when } from './words';

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
        title={`The token ${issued.name}`}
        onCancel={onClose}
        footer={
          <Button type="primary" onClick={onClose}>
            Done
          </Button>
        }
      >
        <Flex vertical gap="small">
          <Typography.Text>The token {issued.name}, valid until {when(issued.expiresAt)}:</Typography.Text>
          <Typography.Text code copyable aria-label="Token">
            {issued.token}
          </Typography.Text>
          <Typography.Text type="secondary">
            It is shown only now: copy it. The command line reads it from RAIL8_TOKEN. Revoke it once it is no longer needed.
          </Typography.Text>
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
  if (limits.kind === 'reading') body = <ReadSpinner label="Reading the ceiling" />;
  else if (limits.kind === 'failed') body = <Alert type="error" showIcon title="The ceiling of a token's life could not be read" description={limits.reason} />;
  else {
    body = (
      <Form layout="vertical" requiredMark>
        <Form.Item
          label="Name"
          htmlFor="rail8-new-token-name"
          required
          validateStatus={name === '' || NAME.test(name) ? undefined : 'error'}
          help="1 to 64 characters: lowercase letters, digits and dashes, starting with a letter or a digit. A new token that takes the name of an expired one replaces it."
        >
          <Input id="rail8-new-token-name" value={name} autoComplete="off" onChange={(event) => setName(event.target.value)} />
        </Form.Item>
        <Form.Item label="Valid for" htmlFor="rail8-new-token-days" required help={`1 to ${maxDays} days, the installation's ceiling.`}>
          <InputNumber<number>
            id="rail8-new-token-days"
            min={1}
            max={maxDays}
            precision={0}
            suffix="days"
            value={days}
            onChange={(value) => setDays(typeof value === 'number' ? value : null)}
          />
        </Form.Item>
        {password && (
          <Form.Item label="Your password" htmlFor="rail8-new-token-password" required help="Asked again: the token outlives your session." style={{ marginBottom: 0 }}>
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
          Cancel
        </Button>,
        <Button key="issue" type="primary" loading={sending} disabled={!valid} onClick={() => void send()}>
          Issue
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        {refused !== null && <Alert type="error" showIcon title="The token was not issued" description={refused} />}
        {body}
      </Flex>
    </MountedModal>
  );
}
