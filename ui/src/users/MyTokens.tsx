import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { ProTable } from '@ant-design/pro-components';
import { Alert, App, Button, Flex } from 'antd';
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { Page } from '../Page';
import { ReadSpinner } from '../ReadSpinner';
import { Allowed, useMe } from '../SessionGate';
import type { Token, Tokens, TokensApi } from './api';
import { IssueTokenDialog } from './IssueTokenDialog';
import { tokenColumns, TOKENS_PAGE_SIZE } from './tokenColumns';
import { reasonOf } from './words';

type Shown = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; listed: Tokens };

/**
 * The caller's own tokens, for the command line: each live one with its name, id, issue, expiry and last use, and
 * Revoke. New token issues one, asking the password again, and shows it once. Issuing needs a session, revoking does
 * not; what the caller may not do is disabled, saying why. A refusal by the node shows in its own words.
 */
export function MyTokens({ api }: { api: TokensApi }) {
  const [shown, setShown] = useState<Shown>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [issuing, setIssuing] = useState(false);
  const refresh = useCallback(() => setAsked((n) => n + 1), []);
  const me = useMe();
  const { notification } = App.useApp();

  useEffect(() => {
    let current = true;
    api.mine().then(
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
      await api.revokeMine(token.id);
      notification.success({ title: `The token ${token.name} is revoked`, role: 'status' });
    } catch (error) {
      setRefusal(reasonOf(error));
    }
    refresh();
  };

  let body: ReactNode;
  if (shown.kind === 'reading') {
    body = <ReadSpinner label="Reading your tokens" />;
  } else if (shown.kind === 'failed') {
    body = <Alert type="error" showIcon title="Your tokens could not be read" description={shown.message} action={<Button onClick={refresh}>Try again</Button>} />;
  } else {
    body = (
      <ProTable<Token>
        aria-label="Your tokens"
        rowKey="id"
        size="small"
        dataSource={shown.listed.tokens}
        search={false}
        options={false}
        pagination={{ pageSize: TOKENS_PAGE_SIZE, hideOnSinglePage: true, showSizeChanger: false }}
        locale={{ emptyText: 'No live token. New token issues one for the command line.' }}
        headerTitle={<h2>Your tokens</h2>}
        toolBarRender={() => [
          <Button key="refresh" icon={<ReloadOutlined aria-hidden />} onClick={refresh}>
            Refresh
          </Button>,
          <Allowed key="new" action="self.token-issue">
            <Button type="primary" icon={<PlusOutlined aria-hidden />} onClick={() => setIssuing(true)}>
              New token
            </Button>
          </Allowed>,
        ]}
        columns={tokenColumns<Token>({ revoke: { action: 'self.tokens', run: revoke } })}
      />
    );
  }

  const ceiling = shown.kind === 'read' ? ` A token lives at most ${shown.listed.maxDays} days.` : '';
  return (
    <Page
      title="My tokens"
      content={`The tokens of ${me?.name ?? 'your account'} for the command line. A token acts with your roles as they are at each request, and never changes an account.${ceiling}`}
    >
      <Flex vertical gap="large">
        {refusal !== null && <Alert type="error" showIcon closable={{ 'aria-label': 'Dismiss' }} onClose={() => setRefusal(null)} title="The token was not revoked" description={refusal} />}
        {body}
      </Flex>
      {issuing && (
        <IssueTokenDialog
          title="New token"
          password
          read={() => api.mine()}
          issue={(name, days, password) => api.issueMine(name, days, password)}
          onClose={() => {
            setIssuing(false);
            refresh();
          }}
        />
      )}
    </Page>
  );
}
