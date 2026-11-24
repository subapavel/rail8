import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { ProTable } from '@ant-design/pro-components';
import { Alert, App, Badge, Button, Checkbox, Descriptions, Flex, Form, Input, Popconfirm, Radio, Space, Table, Tag, Tooltip, Typography } from 'antd';
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { MountedModal } from '../MountedModal';
import { Page } from '../Page';
import { ReadSpinner } from '../ReadSpinner';
import { Allowed, useAccess, useMe } from '../SessionGate';
import { ROLES, type Grant, type Kind, type LiveSession, type LiveToken, type Role, type User, type UsersApi } from './api';

export const ROLE_NAMES: Record<Role, string> = {
  administrator: 'Administrator',
  developer: 'Developer',
  operator: 'Operator',
  'message-bodies': 'Message bodies',
  'secret-administrator': 'Secret administrator',
};

/** The users on one page of the list. */
const PAGE_SIZE = 25;

const VIA: Record<string, string> = {
  session: 'in a session',
  'emergency-key': 'with the emergency key',
  'shared-token': 'with the shared token',
};

/** An instant as minutes in UTC, such as `2026-11-20 09:00 UTC`. */
const when = (iso: string) => `${iso.slice(0, 16).replace('T', ' ')} UTC`;

/** The node's words as a sentence: its first letter capital, and a full stop. */
const sentence = (text: string) => `${text.charAt(0).toUpperCase()}${text.slice(1)}${/[.!?]$/.test(text) ? '' : '.'}`;

const reasonOf = (error: unknown) => (error instanceof Error ? sentence(error.message) : sentence(String(error)));

/** Whether the grant is the user's own: a role the user gave themselves. */
const own = (user: User, grant: Grant) => grant.grantedBy === user.name;

/** The action that gives or takes `role`. */
const actionOf = (role: Role) => (role === 'administrator' ? 'administrators.change' : 'roles.change');

/** A first password, shown once, with what to do with it. */
function FirstPassword({ name, password }: { name: string; password: string }) {
  return (
    <Flex vertical gap="small">
      <Typography.Text>The first password of {name}:</Typography.Text>
      <Typography.Text code copyable aria-label="First password">
        {password}
      </Typography.Text>
      <Typography.Text type="secondary">
        It is shown only now. Give it to {name}, who chooses their own password at the first login. It holds for 7 days.
      </Typography.Text>
    </Flex>
  );
}

type Shown = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; users: User[] };

/** The words of a refused act on one user, shown above the list until the next act. */
interface Refusal {
  title: string;
  reason: string;
}

type Dialog = { kind: 'create' } | { kind: 'roles'; user: User } | { kind: 'password'; name: string; password: string };

/**
 * The Users screen: every user of the installation with their kind, their roles, whether they are blocked or still on a
 * first password, and their live sessions and tokens. A row opens to show who granted each role and how, the sessions
 * and the tokens, never a secret. An administrator creates a person or a technical account, changes a user's roles,
 * blocks and unblocks them, resets a person's password and ends a user's sessions; a first password shows once. What
 * the caller may not do is disabled, saying why. A refusal by the node, such as the last administrator, shows in its
 * own words. While the node holds an emergency key, a notice says to unset it.
 */
export function UserList({ api }: { api: UsersApi }) {
  const [shown, setShown] = useState<Shown>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const [refusal, setRefusal] = useState<Refusal | null>(null);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const refresh = useCallback(() => setAsked((n) => n + 1), []);
  const me = useMe();
  const access = useAccess();
  const { notification } = App.useApp();

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
  const act = async (run: () => Promise<void>, done: string, failed: string) => {
    setRefusal(null);
    try {
      await run();
      notification.success({ title: done, role: 'status' });
    } catch (error) {
      setRefusal({ title: failed, reason: reasonOf(error) });
    }
    refresh();
  };

  const rolesMay = (['roles.change', 'administrators.change'] as const).map((action) => access(action));
  const rolesAllowed = rolesMay.includes(true) ? true : rolesMay[0];

  const actions = (user: User): ReactNode => (
    <Space size={0} wrap>
      <Tooltip title={rolesAllowed === true ? undefined : rolesAllowed}>
        <Button type="link" size="small" disabled={rolesAllowed !== true} onClick={() => setDialog({ kind: 'roles', user })}>
          Roles…
        </Button>
      </Tooltip>
      {/* each its own key, so the popover of one is never reused by the other */}
      {user.blockedAt === null ? (
        <Allowed key="block" action="user.block">
          <ConfirmButton
            label="Block"
            title={`Block ${user.name}?`}
            description="Their sessions end now, and their tokens are refused while the block lasts."
            onConfirm={() => act(() => api.block(user.name), `${user.name} is blocked`, `${user.name} was not blocked`)}
          />
        </Allowed>
      ) : (
        <Allowed key="unblock" action="user.block">
          <ConfirmButton
            label="Unblock"
            title={`Unblock ${user.name}?`}
            onConfirm={() => act(() => api.unblock(user.name), `${user.name} is unblocked`, `${user.name} was not unblocked`)}
          />
        </Allowed>
      )}
      {user.kind === 'person' && (
        <Allowed action="user.password-reset">
          <ConfirmButton
            label="Reset password"
            title={`Reset the password of ${user.name}?`}
            description="Their sessions end, and a new first password shows once."
            onConfirm={async () => {
              setRefusal(null);
              try {
                const password = await api.resetPassword(user.name);
                setDialog({ kind: 'password', name: user.name, password });
              } catch (error) {
                setRefusal({ title: `The password of ${user.name} was not reset`, reason: reasonOf(error) });
              }
              refresh();
            }}
          />
        </Allowed>
      )}
      <Allowed action="session.end">
        <ConfirmButton
          label="End sessions"
          title={`End every session of ${user.name}?`}
          description="They log in again in each browser."
          onConfirm={() => act(() => api.endSessions(user.name), `The sessions of ${user.name} ended`, `The sessions of ${user.name} did not end`)}
        />
      </Allowed>
    </Space>
  );

  let body: ReactNode;
  if (shown.kind === 'reading') {
    body = <ReadSpinner label="Reading the users" />;
  } else if (shown.kind === 'failed') {
    body = <Alert type="error" showIcon title="The users could not be read" description={shown.message} action={<Button onClick={refresh}>Try again</Button>} />;
  } else {
    body = (
      <ProTable<User>
        aria-label="Users"
        rowKey="name"
        size="small"
        dataSource={shown.users}
        search={false}
        options={false}
        pagination={{ pageSize: PAGE_SIZE, hideOnSinglePage: true, showSizeChanger: false }}
        headerTitle={<h2>Users</h2>}
        toolBarRender={() => [
          <Button key="refresh" icon={<ReloadOutlined aria-hidden />} onClick={refresh}>
            Refresh
          </Button>,
          <Allowed key="new" action="user.create">
            <Button type="primary" icon={<PlusOutlined aria-hidden />} onClick={() => setDialog({ kind: 'create' })}>
              New user
            </Button>
          </Allowed>,
        ]}
        expandable={{ expandedRowRender: (user) => <UserDetails user={user} /> }}
        columns={[
          {
            title: 'Name',
            dataIndex: 'name',
            width: 160,
            render: (_, user) => (
              <Space size="small">
                <Typography.Text>{user.name}</Typography.Text>
                {user.name === me?.name && <Tag>You</Tag>}
              </Space>
            ),
          },
          { title: 'Kind', dataIndex: 'kind', width: 150, render: (_, user) => (user.kind === 'person' ? 'Person' : 'Technical account') },
          { title: 'Roles', key: 'roles', render: (_, user) => <Roles user={user} /> },
          { title: 'Status', key: 'status', width: 150, render: (_, user) => <Status user={user} /> },
          { title: 'Sessions', key: 'sessions', align: 'right', width: 90, render: (_, user) => user.sessions.length },
          { title: 'Tokens', key: 'tokens', align: 'right', width: 80, render: (_, user) => user.tokens.length },
          { title: 'Actions', key: 'actions', width: 360, render: (_, user) => actions(user) },
        ]}
      />
    );
  }

  return (
    <Page title="Users" content="The people and technical accounts that use this installation, their roles, sessions and tokens.">
      <Flex vertical gap="large">
        {me?.emergencyKey === true && (
          <Alert
            type="warning"
            showIcon
            title="The node holds an emergency key"
            description="Whoever holds RAIL8_ADMIN_TOKEN can make administrators and reset passwords. Once the administrators can log in, unset it on the node and restart the node."
          />
        )}
        {refusal !== null && <Alert type="error" showIcon closable={{ 'aria-label': 'Dismiss' }} onClose={() => setRefusal(null)} title={refusal.title} description={refusal.reason} />}
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
            refresh();
          }}
        />
      )}
      {dialog?.kind === 'password' && (
        <MountedModal
          title={`New first password of ${dialog.name}`}
          onCancel={() => setDialog(null)}
          footer={
            <Button type="primary" onClick={() => setDialog(null)}>
              Done
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
  if (user.roles.length === 0) return '-';
  return (
    <Flex wrap gap="small">
      {[...user.roles]
        .sort((a, b) => ROLES.indexOf(a.role) - ROLES.indexOf(b.role))
        .map((grant) => (
          <Tooltip key={grant.role} title={granted(user, grant)}>
            <Tag>
              {ROLE_NAMES[grant.role]}
              {own(user, grant) && ' (own)'}
            </Tag>
          </Tooltip>
        ))}
    </Flex>
  );
}

const granted = (user: User, grant: Grant) =>
  own(user, grant)
    ? `Given by ${user.name} to themselves ${VIA[grant.grantedVia] ?? grant.grantedVia}, ${when(grant.grantedAt)}`
    : `Given by ${grant.grantedBy} ${VIA[grant.grantedVia] ?? grant.grantedVia}, ${when(grant.grantedAt)}`;

function Status({ user }: { user: User }) {
  if (user.blockedAt !== null) return <Badge status="error" text="Blocked" />;
  if (user.firstPassword) return <Badge status="warning" text="First password" />;
  return <Badge status="success" text="Active" />;
}

/** The facts of one user: when they were made and by whom, who granted each role and how, their live sessions and tokens. */
function UserDetails({ user }: { user: User }) {
  return (
    <Flex vertical gap="middle">
      <Descriptions
        size="small"
        column={2}
        items={[
          { key: 'created', label: 'Created', children: `${when(user.createdAt)} by ${user.createdBy}` },
          { key: 'blocked', label: 'Blocked', children: user.blockedAt === null ? '-' : `since ${when(user.blockedAt)}` },
        ]}
      />
      <Table<Grant>
        aria-label={`Roles of ${user.name}`}
        size="small"
        rowKey="role"
        pagination={false}
        dataSource={user.roles}
        locale={{ emptyText: 'No role' }}
        columns={[
          { title: 'Role', dataIndex: 'role', render: (_, grant) => ROLE_NAMES[grant.role] },
          {
            title: 'Granted by',
            dataIndex: 'grantedBy',
            render: (_, grant) => (
              <Space size="small">
                {grant.grantedBy}
                {own(user, grant) && <Tag>Own</Tag>}
              </Space>
            ),
          },
          { title: 'How', dataIndex: 'grantedVia', render: (_, grant) => VIA[grant.grantedVia] ?? grant.grantedVia },
          { title: 'When', dataIndex: 'grantedAt', width: 180, render: (_, grant) => when(grant.grantedAt) },
        ]}
      />
      <Table<LiveSession>
        aria-label={`Sessions of ${user.name}`}
        size="small"
        rowKey="id"
        pagination={false}
        dataSource={user.sessions}
        locale={{ emptyText: 'No live session' }}
        columns={[
          { title: 'Session', dataIndex: 'id', render: (_, s) => <Typography.Text code>{s.id}</Typography.Text> },
          { title: 'Started', dataIndex: 'createdAt', width: 180, render: (_, s) => when(s.createdAt) },
          { title: 'Last seen', dataIndex: 'lastSeenAt', width: 180, render: (_, s) => when(s.lastSeenAt) },
          { title: 'Ends at the latest', dataIndex: 'expiresAt', width: 180, render: (_, s) => when(s.expiresAt) },
        ]}
      />
      <Table<LiveToken>
        aria-label={`Tokens of ${user.name}`}
        size="small"
        rowKey="id"
        pagination={false}
        dataSource={user.tokens}
        locale={{ emptyText: 'No live token' }}
        columns={[
          { title: 'Token', dataIndex: 'name' },
          { title: 'Id', dataIndex: 'id', render: (_, t) => <Typography.Text code>{t.id}</Typography.Text> },
          { title: 'Created', dataIndex: 'createdAt', width: 180, render: (_, t) => `${when(t.createdAt)} by ${t.createdBy}` },
          { title: 'Expires', dataIndex: 'expiresAt', width: 180, render: (_, t) => when(t.expiresAt) },
          { title: 'Last used', dataIndex: 'lastUsedAt', width: 180, render: (_, t) => (t.lastUsedAt === null ? '-' : when(t.lastUsedAt)) },
        ]}
      />
    </Flex>
  );
}

/** The roles a user may be given, as checkboxes: each disabled when the caller may not change it, saying why. */
function RoleChoices({ value, onChange, kind }: { value: Role[]; onChange: (roles: Role[]) => void; kind: Kind }) {
  const access = useAccess();
  return (
    <Checkbox.Group<Role> value={value} onChange={onChange}>
      <Flex vertical gap="small">
        {ROLES.map((role) => {
          const may = access(actionOf(role));
          const why = kind === 'technical' && role === 'administrator' ? 'Only a person may hold the administrator role' : may === true ? undefined : may;
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
        title={`${created.name} is created`}
        onCancel={onClose}
        footer={
          <Button type="primary" onClick={onClose}>
            Done
          </Button>
        }
      >
        {created.password === null ? (
          <Typography.Text>{created.name} is a technical account: it has no password, and acts with tokens only.</Typography.Text>
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
      title="New user"
      onCancel={sending ? undefined : onClose}
      footer={[
        <Button key="cancel" disabled={sending} onClick={onClose}>
          Cancel
        </Button>,
        <Button key="create" type="primary" loading={sending} disabled={!valid} onClick={() => void create()}>
          Create
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        {refused !== null && <Alert type="error" showIcon title="The user was not created" description={refused} />}
        <Form layout="vertical" requiredMark>
          <Form.Item
            label="Name"
            htmlFor="rail8-new-user-name"
            required
            validateStatus={name === '' || valid ? undefined : 'error'}
            help="2 to 64 characters: lowercase letters, digits, dots, dashes and underscores, starting with a letter. It never changes."
          >
            <Input id="rail8-new-user-name" value={name} autoComplete="off" onChange={(event) => setName(event.target.value)} />
          </Form.Item>
          <Form.Item label="Kind">
            <Radio.Group
              aria-label="Kind"
              value={kind}
              onChange={(event) => setKind(event.target.value as Kind)}
              options={[
                { value: 'person', label: 'Person, who logs in with a password' },
                { value: 'technical', label: 'Technical account, which has tokens only' },
              ]}
            />
          </Form.Item>
          <Form.Item label="Roles" style={{ marginBottom: 0 }}>
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
        ...changed.added.map((role) => `gets ${ROLE_NAMES[role]}`),
        ...changed.removed.map((role) => `loses ${ROLE_NAMES[role]}`),
      ];
      const recorded = self && changed.added.includes('message-bodies') ? ', recorded in the audit as your own' : '';
      onClose(words.length === 0 ? `The roles of ${user.name} did not change` : `${user.name} ${words.join(', ')}${recorded}`);
    } catch (error) {
      setRefused(reasonOf(error));
      setSending(false);
    }
  };

  return (
    <MountedModal
      title={`Roles of ${user.name}`}
      onCancel={sending ? undefined : () => onClose(null)}
      footer={[
        <Button key="cancel" disabled={sending} onClick={() => onClose(null)}>
          Cancel
        </Button>,
        <Button key="save" type="primary" loading={sending} disabled={add.length === 0 && remove.length === 0} onClick={() => void save()}>
          Save
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        {refused !== null && <Alert type="error" showIcon title="The roles were not changed" description={refused} />}
        <Typography.Text type="secondary">A role holds for the whole installation, from the user's next request on.</Typography.Text>
        <RoleChoices value={roles} onChange={setRoles} kind={user.kind} />
        {ownBodies && (
          <Alert
            type="info"
            showIcon
            title="You give yourself the message bodies role"
            description="It lets you see every message's body. The audit records it as your own assignment."
          />
        )}
      </Flex>
    </MountedModal>
  );
}
