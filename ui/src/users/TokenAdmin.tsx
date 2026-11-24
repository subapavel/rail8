import { ReloadOutlined } from '@ant-design/icons';
import { ProCard, ProTable } from '@ant-design/pro-components';
import { Alert, App, Button, Descriptions, Flex, Form, InputNumber, Typography } from 'antd';
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { MountedModal } from '../MountedModal';
import { Page } from '../Page';
import { ReadSpinner } from '../ReadSpinner';
import { Allowed } from '../SessionGate';
import type { Ceiling, Token, Tokens, TokensApi } from './api';
import { tokenColumns, TOKENS_PAGE_SIZE } from './tokenColumns';
import { reasonOf } from './words';

/** The tokens the screen lists: those that expire within this many days. */
export const EXPIRING_DAYS = 14;

/** The highest ceiling an installation may set. */
const CEILING_MAX = 365;

type Shown = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; listed: Tokens };

/**
 * The administrator's Tokens screen: the installation's ceiling of a token's life and the default life of a new token,
 * which Change… sets, and every user's live tokens that expire within 14 days, soonest first, each with Revoke. What the
 * caller may not do is disabled, saying why; a refusal by the node shows in its own words.
 */
export function TokenAdmin({ api }: { api: TokensApi }) {
  const [shown, setShown] = useState<Shown>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [changing, setChanging] = useState<Ceiling | null>(null);
  const refresh = useCallback(() => setAsked((n) => n + 1), []);
  const { notification } = App.useApp();

  useEffect(() => {
    let current = true;
    api.list(EXPIRING_DAYS).then(
      (listed) => current && setShown({ kind: 'read', listed }),
      (error: unknown) => current && setShown({ kind: 'failed', message: reasonOf(error) }),
    );
    return () => {
      current = false;
    };
  }, [api, asked]);

  const revoke = async (token: Token) => {
    setRefusal(null);
    try {
      await api.revoke(token.id);
      notification.success({ title: `The token ${token.name} of ${token.user} is revoked`, role: 'status' });
    } catch (error) {
      setRefusal(reasonOf(error));
    }
    refresh();
  };

  let body: ReactNode;
  if (shown.kind === 'reading') {
    body = <ReadSpinner label="Reading the tokens" />;
  } else if (shown.kind === 'failed') {
    body = <Alert type="error" showIcon title="The tokens could not be read" description={shown.message} action={<Button onClick={refresh}>Try again</Button>} />;
  } else {
    const { listed } = shown;
    body = (
      <>
        <ProCard
          title={<h2>Ceiling</h2>}
          headerBordered
          extra={
            <Allowed action="token.ceiling">
              <Button onClick={() => setChanging(listed)}>Change…</Button>
            </Allowed>
          }
        >
          <Descriptions
            size="small"
            column={2}
            items={[
              { key: 'max', label: 'Longest life of a token', children: `${listed.maxDays} days` },
              { key: 'default', label: 'Life of a new token by default', children: `${listed.defaultDays} days` },
            ]}
          />
        </ProCard>
        <ProTable<Token>
          aria-label="Tokens that expire soon"
          rowKey="id"
          size="small"
          dataSource={listed.tokens}
          search={false}
          options={false}
          pagination={{ pageSize: TOKENS_PAGE_SIZE, hideOnSinglePage: true, showSizeChanger: false }}
          locale={{ emptyText: `No token expires within ${EXPIRING_DAYS} days.` }}
          headerTitle={<h2>Expire within {EXPIRING_DAYS} days</h2>}
          toolBarRender={() => [
            <Button key="refresh" icon={<ReloadOutlined aria-hidden />} onClick={refresh}>
              Refresh
            </Button>,
          ]}
          columns={tokenColumns<Token>({ user: (t) => t.user, revoke: { action: 'token.revoke', run: revoke } })}
        />
      </>
    );
  }

  return (
    <Page title="Tokens" content={`The installation's ceiling of a token's life, and every user's tokens that expire within ${EXPIRING_DAYS} days.`}>
      <Flex vertical gap="large">
        {refusal !== null && <Alert type="error" showIcon closable={{ 'aria-label': 'Dismiss' }} onClose={() => setRefusal(null)} title="The token was not revoked" description={refusal} />}
        {body}
      </Flex>
      {changing !== null && (
        <CeilingDialog
          api={api}
          ceiling={changing}
          onClose={(changed) => {
            setChanging(null);
            if (changed !== null) notification.success({ title: `A token lives at most ${changed.maxDays} days`, role: 'status' });
            refresh();
          }}
        />
      )}
    </Page>
  );
}

/** The ceiling, 1 to 365 days, from `ceiling` as it is now; Save sends it, and a refusal shows in the node's words. */
function CeilingDialog({ api, ceiling, onClose }: { api: TokensApi; ceiling: Ceiling; onClose: (changed: Ceiling | null) => void }) {
  const [maxDays, setMaxDays] = useState<number | null>(ceiling.maxDays);
  const [sending, setSending] = useState(false);
  const [refused, setRefused] = useState<string | null>(null);
  const valid = maxDays !== null && maxDays >= 1 && maxDays <= CEILING_MAX && maxDays !== ceiling.maxDays;

  const save = async () => {
    if (maxDays === null) return;
    setSending(true);
    setRefused(null);
    try {
      onClose(await api.setCeiling(maxDays));
    } catch (error) {
      setRefused(reasonOf(error));
      setSending(false);
    }
  };

  return (
    <MountedModal
      title="Ceiling of a token's life"
      onCancel={sending ? undefined : () => onClose(null)}
      footer={[
        <Button key="cancel" disabled={sending} onClick={() => onClose(null)}>
          Cancel
        </Button>,
        <Button key="save" type="primary" loading={sending} disabled={!valid} onClick={() => void save()}>
          Save
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        {refused !== null && <Alert type="error" showIcon title="The ceiling was not changed" description={refused} />}
        <Typography.Text type="secondary">
          It holds for the whole installation. A lower ceiling holds for live tokens too: a token older than it is refused from
          the next request on. A new token lives 90 days by default, or the ceiling when that is lower.
        </Typography.Text>
        <Form layout="vertical">
          <Form.Item label="Longest life of a token" htmlFor="rail8-token-ceiling" help={`1 to ${CEILING_MAX} days.`} style={{ marginBottom: 0 }}>
            <InputNumber<number>
              id="rail8-token-ceiling"
              min={1}
              max={CEILING_MAX}
              precision={0}
              suffix="days"
              value={maxDays}
              onChange={(value) => setMaxDays(typeof value === 'number' ? value : null)}
            />
          </Form.Item>
        </Form>
      </Flex>
    </MountedModal>
  );
}
