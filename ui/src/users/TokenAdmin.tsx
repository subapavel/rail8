import { ReloadOutlined } from '@ant-design/icons';
import { ProCard, ProTable } from '@ant-design/pro-components';
import { Alert, App, Button, Descriptions, Flex, Form, InputNumber, Typography } from 'antd';
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { useText } from '../language';
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
  const texts = useText();
  const { t } = texts;

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
      notification.success({ title: t('users.token.revoked', { token: token.name, name: token.user }), role: 'status' });
    } catch (error) {
      setRefusal(reasonOf(error));
    }
    refresh();
  };

  let body: ReactNode;
  if (shown.kind === 'reading') {
    body = <ReadSpinner label={t('users.tokens.reading')} />;
  } else if (shown.kind === 'failed') {
    body = <Alert type="error" showIcon title={t('users.tokens.unread')} description={shown.message} action={<Button onClick={refresh}>{t('frame.tryAgain')}</Button>} />;
  } else {
    const { listed } = shown;
    body = (
      <>
        <ProCard
          title={<h2>{t('users.ceiling')}</h2>}
          headerBordered
          extra={
            <Allowed action="token.ceiling">
              <Button onClick={() => setChanging(listed)}>{t('users.ceiling.change')}</Button>
            </Allowed>
          }
        >
          <Descriptions
            size="small"
            column={2}
            items={[
              { key: 'max', label: t('users.ceiling.max'), children: t('users.days', { count: listed.maxDays }) },
              { key: 'default', label: t('users.ceiling.default'), children: t('users.days', { count: listed.defaultDays }) },
            ]}
          />
        </ProCard>
        <ProTable<Token>
          aria-label={t('users.tokens.expiring')}
          rowKey="id"
          size="small"
          dataSource={listed.tokens}
          search={false}
          options={false}
          pagination={{ pageSize: TOKENS_PAGE_SIZE, hideOnSinglePage: true, showSizeChanger: false }}
          locale={{ emptyText: t('users.tokens.expiring.none', { days: EXPIRING_DAYS }) }}
          headerTitle={<h2>{t('users.tokens.expiring.within', { days: EXPIRING_DAYS })}</h2>}
          toolBarRender={() => [
            <Button key="refresh" icon={<ReloadOutlined aria-hidden />} onClick={refresh}>
              {t('users.refresh')}
            </Button>,
          ]}
          columns={tokenColumns<Token>(texts, { user: (t) => t.user, revoke: { action: 'token.revoke', run: revoke } })}
        />
      </>
    );
  }

  return (
    <Page title={t('users.screen.tokens')} content={t('users.tokens.content', { days: EXPIRING_DAYS })}>
      <Flex vertical gap="large">
        {refusal !== null && (
          <Alert type="error" showIcon closable={{ 'aria-label': t('users.dismiss') }} onClose={() => setRefusal(null)} title={t('users.token.notRevoked.one')} description={refusal} />
        )}
        {body}
      </Flex>
      {changing !== null && (
        <CeilingDialog
          api={api}
          ceiling={changing}
          onClose={(changed) => {
            setChanging(null);
            if (changed !== null) notification.success({ title: t('users.ceiling.atMost', { days: changed.maxDays }), role: 'status' });
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
  const { t } = useText();
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
      title={t('users.ceiling.title')}
      onCancel={sending ? undefined : () => onClose(null)}
      footer={[
        <Button key="cancel" disabled={sending} onClick={() => onClose(null)}>
          {t('frame.cancel')}
        </Button>,
        <Button key="save" type="primary" loading={sending} disabled={!valid} onClick={() => void save()}>
          {t('users.save')}
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        {refused !== null && <Alert type="error" showIcon title={t('users.ceiling.failed')} description={refused} />}
        <Typography.Text type="secondary">{t('users.ceiling.hint', { days: 90 })}</Typography.Text>
        <Form layout="vertical">
          <Form.Item label={t('users.ceiling.max')} htmlFor="rail8-token-ceiling" help={t('users.days.range', { max: CEILING_MAX })} style={{ marginBottom: 0 }}>
            <InputNumber<number>
              id="rail8-token-ceiling"
              min={1}
              max={CEILING_MAX}
              precision={0}
              suffix={t('users.days.unit')}
              value={maxDays}
              onChange={(value) => setMaxDays(typeof value === 'number' ? value : null)}
            />
          </Form.Item>
        </Form>
      </Flex>
    </MountedModal>
  );
}
