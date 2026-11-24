import { LockOutlined, LogoutOutlined, UserOutlined } from '@ant-design/icons';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import { Alert, Button, Flex, Modal, Spin, Tooltip, Typography } from 'antd';
import {
  cloneElement,
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactElement,
  type ReactNode,
} from 'react';
import { pageSession, PasswordRefused, reasonOf, type Me, type Refused, type SessionClient, type SessionState } from './session';
import { Rail8Theme } from './theme';

/** The session of the page, which `SessionGate` gives; null without a gate, where everything is allowed. */
const SessionContext = createContext<SessionClient | null>(null);

const NEVER = () => () => undefined;
const NONE = () => null;

/** The session's state, or null without a gate. */
function useSessionState(): SessionState | null {
  const client = useContext(SessionContext);
  return useSyncExternalStore(client?.subscribe ?? NEVER, client?.state ?? NONE);
}

/**
 * What the caller may do: a function that gives true for an action the caller may do, or for none, and else the words
 * for why not. Without a gate everything is allowed.
 */
export function useAccess(): (action?: string) => true | string {
  const client = useContext(SessionContext);
  // re-render on every change of the session
  useSessionState();
  return (action) => {
    if (client === null || action === undefined) return true;
    const may = client.may(action);
    return may === true ? true : reasonOf(may);
  };
}

/** True when the caller may do `action`, else the words for why not. */
export function useMay(action: string): true | string {
  return useAccess()(action);
}

/** `children`, a control, as it is when the caller may do `action`; else disabled, with why not as its tooltip. */
export function Allowed({ action, children }: { action: string; children: ReactElement<{ disabled?: boolean }> }) {
  const may = useMay(action);
  if (may === true) return children;
  return <Tooltip title={may}>{cloneElement(children, { disabled: true })}</Tooltip>;
}

const WAIT = (retryAfter?: string) => (retryAfter === undefined ? '' : ` Try again in ${retryAfter} s.`);

/** The words for a refused login, or a session that ended. */
function refusedWords({ code, retryAfter }: Refused): string {
  switch (code) {
    case 'login-refused':
      return 'The name or the password is wrong, or the account is blocked.';
    case 'too-many-attempts':
      return `Too many attempts.${WAIT(retryAfter)}`;
    case 'busy':
      return `The node is busy.${WAIT(retryAfter)}`;
    case 'session-ended':
    case 'unknown-credential':
      return 'The session ended. Log in again.';
    case 'blocked':
      return 'The account is blocked.';
    default:
      return `The login failed (${code}).${WAIT(retryAfter)}`;
  }
}

/** The name and the Log out of the header, in session mode only. */
export function SessionActions() {
  const client = useContext(SessionContext);
  const state = useSessionState();
  if (client === null || state?.kind !== 'in' || state.me.session === null) return null;
  return (
    <Flex align="center" gap="small">
      <Typography.Text>
        <UserOutlined aria-hidden /> {state.me.name}
      </Typography.Text>
      <Button size="small" type="text" icon={<LogoutOutlined aria-hidden />} onClick={() => void client.logout()}>
        Log out
      </Button>
    </Flex>
  );
}

/** The login, or the first password's change, over the open tabs once the session ended; it cannot be dismissed. */
export function SessionPrompt() {
  const client = useContext(SessionContext);
  const state = useSessionState();
  if (client === null || state?.kind !== 'asking') return null;
  return (
    <Modal open title={state.me.firstPassword ? 'Change the first password' : 'Log in again'} footer={null} closable={false} destroyOnHidden>
      {state.me.firstPassword ? (
        <PasswordChange client={client} />
      ) : (
        <>
          <Typography.Paragraph type="secondary">
            The session of {state.me.name} ended. The open tabs and their unsaved changes wait for the login.
          </Typography.Paragraph>
          <Login client={client} name={state.me.name} refusal={state.refusal} />
        </>
      )}
    </Modal>
  );
}

function Login({ client, name, refusal }: { client: SessionClient; name?: string; refusal?: Refused }) {
  return (
    <LoginForm<{ name: string; password: string }>
      title={false}
      subTitle={false}
      containerStyle={{ padding: 0 }}
      contentStyle={{ minWidth: 0, width: '100%' }}
      initialValues={{ name }}
      message={refusal && <Alert type="error" showIcon title={refusedWords(refusal)} />}
      submitter={{ searchConfig: { submitText: 'Log in' } }}
      onFinish={async ({ name: given, password }) => {
        await client.login(given, password);
        return true;
      }}
    >
      <ProFormText
        name="name"
        label="Name"
        fieldProps={{ prefix: <UserOutlined aria-hidden />, autoComplete: 'username' }}
        rules={[{ required: true, message: 'Enter your name' }]}
      />
      <ProFormText.Password
        name="password"
        label="Password"
        fieldProps={{ prefix: <LockOutlined aria-hidden />, autoComplete: 'current-password' }}
        rules={[{ required: true, message: 'Enter your password' }]}
      />
    </LoginForm>
  );
}

function PasswordChange({ client }: { client: SessionClient }) {
  const [refused, setRefused] = useState<string | null>(null);
  return (
    <LoginForm<{ current: string; next: string; repeat: string }>
      title={false}
      subTitle={false}
      containerStyle={{ padding: 0 }}
      contentStyle={{ minWidth: 0, width: '100%' }}
      message={refused !== null && <Alert type="error" showIcon title={refused} />}
      submitter={{ searchConfig: { submitText: 'Change the password' } }}
      onFinish={async ({ current, next }) => {
        setRefused(null);
        try {
          await client.changePassword(current, next);
        } catch (error) {
          if (!(error instanceof PasswordRefused)) throw error;
          setRefused(error.code === 'wrong-password' ? 'The current password is wrong.' : `${error.message}.${WAIT(error.retryAfter)}`);
        }
        return true;
      }}
    >
      <Typography.Paragraph type="secondary">
        The account still has the password an administrator gave it. Choose your own: 12 to 128 characters, without your
        name.
      </Typography.Paragraph>
      <ProFormText.Password
        name="current"
        label="Current password"
        fieldProps={{ autoComplete: 'current-password' }}
        rules={[{ required: true, message: 'Enter the current password' }]}
      />
      <ProFormText.Password
        name="next"
        label="New password"
        fieldProps={{ autoComplete: 'new-password' }}
        rules={[{ required: true, message: 'Enter the new password' }, { min: 12, max: 128, message: '12 to 128 characters' }]}
      />
      <ProFormText.Password
        name="repeat"
        label="Repeat the new password"
        fieldProps={{ autoComplete: 'new-password' }}
        dependencies={['next']}
        rules={[
          { required: true, message: 'Repeat the new password' },
          ({ getFieldValue }) => ({
            validator: (_, value) =>
              value === getFieldValue('next') ? Promise.resolve() : Promise.reject(new Error('The two new passwords differ')),
          }),
        ]}
      />
    </LoginForm>
  );
}

/** A page of the gate before the frame: the mark and the name above `children`, in the middle of the window. */
function GatePage({ children }: { children: ReactNode }) {
  return (
    <Rail8Theme>
      {(dark) => (
        <Flex className="rail8-gate" vertical align="center" justify="center" gap="large">
          <Flex align="center" gap="small" className="rail8-brand">
            <img src={`${import.meta.env.BASE_URL}${dark ? 'rail8-mark-dark.svg' : 'rail8.svg'}`} alt="" />
            <Typography.Title level={1}>Rail8</Typography.Title>
          </Flex>
          <div className="rail8-gate-body">{children}</div>
        </Flex>
      )}
    </Rail8Theme>
  );
}

/**
 * Shows `children`, the frame, once the session is known, and the session's pages before it: a spinner while
 * `GET /session` is read; its failure with Try again; the login; and the change of a first password, with Log out.
 * After the frame shows, a session that ends asks for the login again over it (`SessionPrompt`), and the frame's
 * header names the user with Log out (`SessionActions`), in session mode only.
 */
export function SessionGate({ client = pageSession, children }: { client?: SessionClient; children: ReactNode }) {
  const state = useSyncExternalStore(client.subscribe, client.state);
  const started = useRef(false);
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    void client.start();
  }, [client]);

  return <SessionContext.Provider value={client}>{gated(client, state, children)}</SessionContext.Provider>;
}

function gated(client: SessionClient, state: SessionState, children: ReactNode): ReactNode {
  switch (state.kind) {
    case 'starting':
      return (
        <GatePage>
          <Spin aria-label="Reading the session" delay={200} />
        </GatePage>
      );
    case 'failed':
      return (
        <GatePage>
          <Alert
            type="error"
            showIcon
            title="The session could not be read"
            description={`${state.error}.${WAIT(state.retryAfter)}`}
            action={
              <Button size="small" onClick={() => void client.start()}>
                Try again
              </Button>
            }
          />
        </GatePage>
      );
    case 'out':
      return (
        <GatePage>
          <Flex vertical gap="middle">
            <Typography.Title level={2}>Log in</Typography.Title>
            <Login client={client} refusal={state.refusal} />
          </Flex>
        </GatePage>
      );
    case 'in':
      return state.me.firstPassword ? <FirstPassword client={client} me={state.me} /> : children;
    case 'asking':
      return children;
  }
}

function FirstPassword({ client, me }: { client: SessionClient; me: Me }) {
  return (
    <GatePage>
      <Flex vertical gap="middle">
        <Typography.Title level={2}>Change the first password</Typography.Title>
        <PasswordChange client={client} />
        <Button type="link" icon={<LogoutOutlined aria-hidden />} onClick={() => void client.logout()}>
          Log out {me.name}
        </Button>
      </Flex>
    </GatePage>
  );
}
