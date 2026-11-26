import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { ProTable } from '@ant-design/pro-components';
import { Alert, App, Badge, Button, Checkbox, Descriptions, Flex, Form, Input, Popconfirm, Radio, Space, Table, Tag, Tooltip, Typography } from 'antd';
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { instant, msg, say, useText, type Message, type Texts } from '../language';
import { MountedModal } from '../MountedModal';
import { Page } from '../Page';
import { ReadSpinner } from '../ReadSpinner';
import { Allowed, useAccess, useMe, useSessionRefresh } from '../SessionGate';
import { ROLES, type Grant, type Kind, type LiveSession, type LiveToken, type Role, type TokensApi, type User, type UsersApi } from './api';
import { IssueTokenDialog } from './IssueTokenDialog';
import { tokenColumns } from './tokenColumns';
import { reasonOf } from './words';

/** The names of the roles, which stay in English in every language: they are terms of the installation. */
export const ROLE_NAMES: Record<Role, string> = {
  administrator: 'Administrator', // untranslated: a role's name is a term
  developer: 'Developer', // untranslated: a role's name is a term
  operator: 'Operator', // untranslated: a role's name is a term
  'message-bodies': 'Message bodies', // untranslated: a role's name is a term
  'secret-administrator': 'Secret administrator', // untranslated: a role's name is a term
  'connection-administrator': 'Connection administrator', // untranslated: a role's name is a term
  'partner-administrator': 'Partner administrator', // untranslated: a role's name is a term
};

/** The users on one page of the list. */
const PAGE_SIZE = 25;

/** The ids of the words for how a role was granted. */
const VIA: Record<string, string> = {
  session: 'users.via.session',
  'emergency-key': 'users.via.emergencyKey',
  'shared-token': 'users.via.sharedToken',
};

/** How a role was granted, in words; a way the list does not know shows its id. */
const viaOf = ({ t }: Texts, via: string) => (Object.hasOwn(VIA, via) ? t(VIA[via]!) : via);

/** Whether the grant is the user's own: a role the user gave themselves. */
const own = (user: User, grant: Grant) => grant.grantedBy === user.name;

/** The action that gives or takes `role`. */
const actionOf = (role: Role) => (role === 'administrator' ? 'administrators.change' : 'roles.change');

/** A first password, shown once, with what to do with it. */
function FirstPassword({ name, password }: { name: string; password: string }) {
  const { t } = useText();
  return (
    <Flex vertical gap="small">
      <Typography.Text>{t('users.firstPassword.of', { name })}</Typography.Text>
      <Typography.Text code copyable aria-label={t('users.firstPassword')}>
        {password}
      </Typography.Text>
      <Typography.Text type="secondary">{t('users.firstPassword.hint', { name, days: 7 })}</Typography.Text>
    </Flex>
  );
}

type Shown = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; users: User[] };

/** The words of a refused act on one user, shown above the list until the next act. */
interface Refusal {
  title: Message;
  reason: string;
}

type Dialog = { kind: 'create' } | { kind: 'roles'; user: User } | { kind: 'password'; name: string; password: string } | { kind: 'token'; name: string };

/**
 * The Users screen: every user of the installation with their kind, their roles, whether they are blocked or still on a
 * first password, and their live sessions and tokens. A row opens to show who granted each role and how, the sessions
 * and the tokens, never a secret. An administrator creates a person or a technical account, changes a user's roles,
 * blocks and unblocks them, resets a person's password and ends a user's sessions; a first password shows once. They
 * issue a technical account's tokens, each shown once, and revoke any user's token. What
 * the caller may not do is disabled, saying why. A refusal by the node, such as the last administrator, shows in its
 * own words. While the node holds an emergency key, a notice says to unset it.
 */
export function UserList({ api, tokens }: { api: UsersApi; tokens: TokensApi }) {
  const [shown, setShown] = useState<Shown>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const [refusal, setRefusal] = useState<Refusal | null>(null);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const refresh = useCallback(() => setAsked((n) => n + 1), []);
  const me = useMe();
  const access = useAccess();
  const refreshSession = useSessionRefresh();
  const { notification } = App.useApp();
  const texts = useText();
  const { t } = texts;

  useEffect(() => {
    let current = true;
    api.list().then(
      (users) => current && setShown({ kind: 'read', users }),
      (error: unknown) => current && setShown({ kind: 'failed', message: reasonOf(error) }),
    );
    return () => {
      current = false;
    };
  }, [api, asked]);

  /** Runs one act on a user: on success says `done` and reads the list again; a refusal shows as `failed` with the node's reason. */
  const act = async (run: () => Promise<void>, done: Message, failed: Message) => {
    setRefusal(null);
    try {
      await run();
      notification.success({ title: say(texts, done), role: 'status' });
    } catch (error) {
      setRefusal({ title: failed, reason: reasonOf(error) });
    }
    refresh();
  };

  const rolesMay = (['roles.change', 'administrators.change'] as const).map((action) => access(action));
  const rolesAllowed = rolesMay.includes(true) ? true : rolesMay[0];

  const actions = (user: User): ReactNode => (
    <Space size={0}>
      <Tooltip title={rolesAllowed === true ? undefined : rolesAllowed}>
        <Button type="link" size="small" disabled={rolesAllowed !== true} onClick={() => setDialog({ kind: 'roles', user })}>
          {t('users.roles.change')}
        </Button>
      </Tooltip>
      {/* each its own key, so the popover of one is never reused by the other */}
      {user.blockedAt === null ? (
        <Allowed key="block" action="user.block">
          <ConfirmButton
            label={t('users.block')}
            title={t('users.block.question', { name: user.name })}
            description={t('users.block.description')}
            onConfirm={() => act(() => api.block(user.name), msg('users.block.done', { name: user.name }), msg('users.block.failed', { name: user.name }))}
          />
        </Allowed>
      ) : (
        <Allowed key="unblock" action="user.block">
          <ConfirmButton
            label={t('users.unblock')}
            title={t('users.unblock.question', { name: user.name })}
            onConfirm={() => act(() => api.unblock(user.name), msg('users.unblock.done', { name: user.name }), msg('users.unblock.failed', { name: user.name }))}
          />
        </Allowed>
      )}
      {user.kind === 'technical' && (
        <Allowed action="token.issue">
          <Button type="link" size="small" onClick={() => setDialog({ kind: 'token', name: user.name })}>
            {t('users.token.issueTo')}
          </Button>
        </Allowed>
      )}
      {user.kind === 'person' && (
        <Allowed action="user.password-reset">
          <ConfirmButton
            label={t('users.reset')}
            title={t('users.reset.question', { name: user.name })}
            description={t('users.reset.description')}
            onConfirm={async () => {
              setRefusal(null);
              try {
                const password = await api.resetPassword(user.name);
                setDialog({ kind: 'password', name: user.name, password });
              } catch (error) {
                setRefusal({ title: msg('users.reset.failed', { name: user.name }), reason: reasonOf(error) });
              }
              refresh();
            }}
          />
        </Allowed>
      )}
      <Allowed action="session.end">
        <ConfirmButton
          label={t('users.end')}
          title={t('users.end.question', { name: user.name })}
          description={t('users.end.description')}
          onConfirm={() => act(() => api.endSessions(user.name), msg('users.end.done', { name: user.name }), msg('users.end.failed', { name: user.name }))}
        />
      </Allowed>
    </Space>
  );

  let body: ReactNode;
  if (shown.kind === 'reading') {
    body = <ReadSpinner label={t('users.reading')} />;
  } else if (shown.kind === 'failed') {
    body = <Alert type="error" showIcon title={t('users.unread')} description={shown.message} action={<Button onClick={refresh}>{t('frame.tryAgain')}</Button>} />;
  } else {
    body = (
      <ProTable<User>
        aria-label={t('users.screen.users')}
        rowKey="name"
        size="small"
        dataSource={shown.users}
        search={false}
        options={false}
        pagination={{ pageSize: PAGE_SIZE, hideOnSinglePage: true, showSizeChanger: false }}
        headerTitle={<h2>{t('users.screen.users')}</h2>}
        toolBarRender={() => [
          <Button key="refresh" icon={<ReloadOutlined aria-hidden />} onClick={refresh}>
            {t('users.refresh')}
          </Button>,
          <Allowed key="new" action="user.create">
            <Button type="primary" icon={<PlusOutlined aria-hidden />} onClick={() => setDialog({ kind: 'create' })}>
              {t('users.new')}
            </Button>
          </Allowed>,
        ]}
        expandable={{
          expandedRowRender: (user) => (
            <UserDetails
              user={user}
              revoke={(token) =>
                act(
                  () => tokens.revoke(token.id),
                  msg('users.token.revoked', { token: token.name, name: user.name }),
                  msg('users.token.notRevoked', { token: token.name, name: user.name }),
                )
              }
            />
          ),
        }}
        columns={[
          {
            title: t('users.name'),
            dataIndex: 'name',
            width: 160,
            render: (_, user) => (
              <Space size="small">
                <Typography.Text>{user.name}</Typography.Text>
                {user.name === me?.name && <Tag>{t('users.you')}</Tag>}
              </Space>
            ),
          },
          { title: t('users.kind'), dataIndex: 'kind', width: 150, render: (_, user) => t(user.kind === 'person' ? 'users.kind.person' : 'users.kind.technical') },
          { title: t('users.roles'), key: 'roles', render: (_, user) => <Roles user={user} /> },
          { title: t('users.status'), key: 'status', width: 150, render: (_, user) => <Status user={user} /> },
          { title: t('users.sessions'), key: 'sessions', align: 'right', width: 90, render: (_, user) => user.sessions.length },
          { title: t('users.screen.tokens'), key: 'tokens', align: 'right', width: 80, render: (_, user) => user.tokens.length },
          { title: t('users.actions'), key: 'actions', width: 360, render: (_, user) => actions(user) },
        ]}
      />
    );
  }

  return (
    <Page title={t('users.screen.users')} content={t('users.content')}>
      <Flex vertical gap="large">
        {me?.emergencyKey === true && (
          <Alert
            type="warning"
            showIcon
            title={t('users.emergency')}
            description={t('users.emergency.description')}
          />
        )}
        {refusal !== null && (
          <Alert type="error" showIcon closable={{ 'aria-label': t('users.dismiss') }} onClose={() => setRefusal(null)} title={say(texts, refusal.title)} description={refusal.reason} />
        )}
        {body}
      </Flex>
      {dialog?.kind === 'create' && (
        <CreateDialog
          api={api}
          onClose={() => {
            setDialog(null);
            refresh();
          }}
        />
      )}
      {dialog?.kind === 'roles' && (
        <RolesDialog
          api={api}
          user={dialog.user}
          self={dialog.user.name === me?.name}
          onClose={(changed) => {
            setDialog(null);
            if (changed !== null) notification.success({ title: changed, role: 'status' });
            // what the page allows follows the caller's own new roles
            if (changed !== null && dialog.user.name === me?.name) void refreshSession();
            refresh();
          }}
        />
      )}
      {dialog?.kind === 'token' && (
        <IssueTokenDialog
          title={t('users.token.newOf', { name: dialog.name })}
          password={false}
          // the administrator who issues it is a person in a session, whose own list carries the ceiling
          read={() => tokens.mine()}
          issue={(name, days) => tokens.issue(dialog.name, name, days)}
          onClose={() => {
            setDialog(null);
            refresh();
          }}
        />
      )}
      {dialog?.kind === 'password' && (
        <MountedModal
          title={t('users.firstPassword.newOf', { name: dialog.name })}
          onCancel={() => setDialog(null)}
          footer={
            <Button type="primary" onClick={() => setDialog(null)}>
              {t('users.done')}
            </Button>
          }
        >
          <FirstPassword name={dialog.name} password={dialog.password} />
        </MountedModal>
      )}
    </Page>
  );
}

/** A small link button that asks `title` before it runs `onConfirm`. */
function ConfirmButton({ label, title, description, onConfirm, disabled }: { label: string; title: string; description?: string; onConfirm: () => Promise<void>; disabled?: boolean }) {
  return (
    <Popconfirm title={title} description={description} okText={label} onConfirm={onConfirm} disabled={disabled} destroyOnHidden>
      <Button type="link" size="small" disabled={disabled}>
        {label}
      </Button>
    </Popconfirm>
  );
}

/** The user's roles as tags, each saying who granted it and how; a role the user gave themselves says so. */
function Roles({ user }: { user: User }) {
  const texts = useText();
  if (user.roles.length === 0) return '-';
  return (
    <Flex wrap gap="small">
      {[...user.roles]
        .sort((a, b) => ROLES.indexOf(a.role) - ROLES.indexOf(b.role))
        .map((grant) => (
          <Tooltip key={grant.role} title={granted(texts, user, grant)}>
            <Tag>{own(user, grant) ? texts.t('users.role.own', { role: ROLE_NAMES[grant.role] }) : ROLE_NAMES[grant.role]}</Tag>
          </Tooltip>
        ))}
    </Flex>
  );
}

const granted = (texts: Texts, user: User, grant: Grant) =>
  texts.t(own(user, grant) ? 'users.role.givenOwn' : 'users.role.given', {
    name: own(user, grant) ? user.name : grant.grantedBy,
    via: viaOf(texts, grant.grantedVia),
    at: instant(texts, grant.grantedAt),
  });

function Status({ user }: { user: User }) {
  const { t } = useText();
  if (user.blockedAt !== null) return <Badge status="error" text={t('users.status.blocked')} />;
  if (user.firstPassword) return <Badge status="warning" text={t('users.firstPassword')} />;
  return <Badge status="success" text={t('users.status.active')} />;
}

/**
 * The facts of one user: when they were made and by whom, who granted each role and how, their live sessions and tokens,
 * each token with Revoke, which runs `revoke`.
 */
function UserDetails({ user, revoke }: { user: User; revoke: (token: LiveToken) => Promise<void> }) {
  const texts = useText();
  const { t } = texts;
  return (
    <Flex vertical gap="middle">
      <Descriptions
        size="small"
        column={2}
        items={[
          { key: 'created', label: t('users.created'), children: t('users.atBy', { at: instant(texts, user.createdAt), name: user.createdBy }) },
          { key: 'blocked', label: t('users.status.blocked'), children: user.blockedAt === null ? '-' : t('users.since', { at: instant(texts, user.blockedAt) }) },
        ]}
      />
      <Table<Grant>
        aria-label={t('users.rolesOf', { name: user.name })}
        size="small"
        rowKey="role"
        pagination={false}
        dataSource={user.roles}
        locale={{ emptyText: t('users.roles.none') }}
        columns={[
          { title: t('users.role'), dataIndex: 'role', render: (_, grant) => ROLE_NAMES[grant.role] },
          {
            title: t('users.grantedBy'),
            dataIndex: 'grantedBy',
            render: (_, grant) => (
              <Space size="small">
                {grant.grantedBy}
                {own(user, grant) && <Tag>{t('users.own')}</Tag>}
              </Space>
            ),
          },
          { title: t('users.how'), dataIndex: 'grantedVia', render: (_, grant) => viaOf(texts, grant.grantedVia) },
          { title: t('users.when'), dataIndex: 'grantedAt', width: 210, render: (_, grant) => instant(texts, grant.grantedAt) },
        ]}
      />
      <Table<LiveSession>
        aria-label={t('users.sessionsOf', { name: user.name })}
        size="small"
        rowKey="id"
        pagination={false}
        dataSource={user.sessions}
        locale={{ emptyText: t('users.sessions.none') }}
        columns={[
          { title: t('users.session'), dataIndex: 'id', render: (_, s) => <Typography.Text code>{s.id}</Typography.Text> },
          { title: t('users.session.started'), dataIndex: 'createdAt', width: 210, render: (_, s) => instant(texts, s.createdAt) },
          { title: t('users.session.lastSeen'), dataIndex: 'lastSeenAt', width: 210, render: (_, s) => instant(texts, s.lastSeenAt) },
          { title: t('users.session.ends'), dataIndex: 'expiresAt', width: 210, render: (_, s) => instant(texts, s.expiresAt) },
        ]}
      />
      <Table<LiveToken>
        aria-label={t('users.tokensOf', { name: user.name })}
        size="small"
        rowKey="id"
        pagination={false}
        dataSource={user.tokens}
        locale={{ emptyText: t('users.tokens.none') }}
        columns={tokenColumns<LiveToken>(texts, { revoke: { action: 'token.revoke', run: revoke } })}
      />
    </Flex>
  );
}

/** The roles a user may be given, as checkboxes: each disabled when the caller may not change it, saying why. */
function RoleChoices({ value, onChange, kind }: { value: Role[]; onChange: (roles: Role[]) => void; kind: Kind }) {
  const access = useAccess();
  const { t } = useText();
  return (
    <Checkbox.Group<Role> value={value} onChange={onChange}>
      <Flex vertical gap="small">
        {ROLES.map((role) => {
          const may = access(actionOf(role));
          const why = kind === 'technical' && role === 'administrator' ? t('users.role.personOnly') : may === true ? undefined : may;
          return (
            <Tooltip key={role} title={why} placement="right">
              <Checkbox value={role} disabled={why !== undefined}>
                {ROLE_NAMES[role]}
              </Checkbox>
            </Tooltip>
          );
        })}
      </Flex>
    </Checkbox.Group>
  );
}

const NAME = /^[a-z][a-z0-9._-]{1,63}$/;

/** New user: a name, a person or a technical account, and roles; a person's first password then shows once. */
function CreateDialog({ api, onClose }: { api: UsersApi; onClose: () => void }) {
  const { t } = useText();
  const [name, setName] = useState('');
  const [kind, setKind] = useState<Kind>('person');
  const [roles, setRoles] = useState<Role[]>([]);
  const [sending, setSending] = useState(false);
  const [refused, setRefused] = useState<string | null>(null);
  const [created, setCreated] = useState<{ name: string; password: string | null } | null>(null);
  const valid = NAME.test(name);

  if (created !== null) {
    return (
      <MountedModal
        title={t('users.created.title', { name: created.name })}
        onCancel={onClose}
        footer={
          <Button type="primary" onClick={onClose}>
            {t('users.done')}
          </Button>
        }
      >
        {created.password === null ? (
          <Typography.Text>{t('users.created.technical', { name: created.name })}</Typography.Text>
        ) : (
          <FirstPassword name={created.name} password={created.password} />
        )}
      </MountedModal>
    );
  }

  const create = async () => {
    setSending(true);
    setRefused(null);
    try {
      const password = await api.create(name, kind, kind === 'technical' ? roles.filter((r) => r !== 'administrator') : roles);
      setCreated({ name, password });
    } catch (error) {
      setRefused(reasonOf(error));
    } finally {
      setSending(false);
    }
  };

  return (
    <MountedModal
      title={t('users.new')}
      onCancel={sending ? undefined : onClose}
      footer={[
        <Button key="cancel" disabled={sending} onClick={onClose}>
          {t('frame.cancel')}
        </Button>,
        <Button key="create" type="primary" loading={sending} disabled={!valid} onClick={() => void create()}>
          {t('users.create')}
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        {refused !== null && <Alert type="error" showIcon title={t('users.create.failed')} description={refused} />}
        <Form layout="vertical" requiredMark>
          <Form.Item
            label={t('users.name')}
            htmlFor="rail8-new-user-name"
            required
            validateStatus={name === '' || valid ? undefined : 'error'}
            help={t('users.name.help', { min: 2, max: 64 })}
          >
            <Input id="rail8-new-user-name" value={name} autoComplete="off" onChange={(event) => setName(event.target.value)} />
          </Form.Item>
          <Form.Item label={t('users.kind')}>
            <Radio.Group
              aria-label={t('users.kind')}
              value={kind}
              onChange={(event) => setKind(event.target.value as Kind)}
              options={[
                { value: 'person', label: t('users.kind.person.choice') },
                { value: 'technical', label: t('users.kind.technical.choice') },
              ]}
            />
          </Form.Item>
          <Form.Item label={t('users.roles')} style={{ marginBottom: 0 }}>
            <RoleChoices value={kind === 'technical' ? roles.filter((r) => r !== 'administrator') : roles} onChange={setRoles} kind={kind} />
          </Form.Item>
        </Form>
      </Flex>
    </MountedModal>
  );
}

/**
 * The roles of one user: the roles it holds ticked; Save sends what was ticked and unticked as the difference. A role the
 * caller gives themselves is recorded as their own, which the dialog says for the message bodies role.
 */
function RolesDialog({ api, user, self, onClose }: { api: UsersApi; user: User; self: boolean; onClose: (changed: string | null) => void }) {
  const { t, intl } = useText();
  const held = user.roles.map((g) => g.role);
  const [roles, setRoles] = useState<Role[]>(held);
  const [sending, setSending] = useState(false);
  const [refused, setRefused] = useState<string | null>(null);
  const add = roles.filter((role) => !held.includes(role));
  const remove = held.filter((role) => !roles.includes(role));
  const ownBodies = self && add.includes('message-bodies');

  const save = async () => {
    setSending(true);
    setRefused(null);
    try {
      const changed = await api.changeRoles(user.name, add, remove);
      const words = [
        ...changed.added.map((role) => t('users.roles.gets', { role: ROLE_NAMES[role] })),
        ...changed.removed.map((role) => t('users.roles.loses', { role: ROLE_NAMES[role] })),
      ];
      const recorded = self && changed.added.includes('message-bodies');
      onClose(
        words.length === 0
          ? t('users.roles.unchanged', { name: user.name })
          : t(recorded ? 'users.roles.changedOwn' : 'users.roles.changed', { name: user.name, changes: intl.formatList(words, { type: 'unit' }) }),
      );
    } catch (error) {
      setRefused(reasonOf(error));
      setSending(false);
    }
  };

  return (
    <MountedModal
      title={t('users.rolesOf', { name: user.name })}
      onCancel={sending ? undefined : () => onClose(null)}
      footer={[
        <Button key="cancel" disabled={sending} onClick={() => onClose(null)}>
          {t('frame.cancel')}
        </Button>,
        <Button key="save" type="primary" loading={sending} disabled={add.length === 0 && remove.length === 0} onClick={() => void save()}>
          {t('users.save')}
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        {refused !== null && <Alert type="error" showIcon title={t('users.roles.failed')} description={refused} />}
        <Typography.Text type="secondary">{t('users.roles.hint')}</Typography.Text>
        <RoleChoices value={roles} onChange={setRoles} kind={user.kind} />
        {ownBodies && (
          <Alert
            type="info"
            showIcon
            title={t('users.roles.ownBodies')}
            description={t('users.roles.ownBodies.description')}
          />
        )}
      </Flex>
    </MountedModal>
  );
}
