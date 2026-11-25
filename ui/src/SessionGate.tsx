import { LockOutlined, LogoutOutlined, UserOutlined } from '@ant-design/icons';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import { Alert, Button, Flex, Modal, Tooltip, Typography } from 'antd';
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
import { ReadSpinner } from './ReadSpinner';
import { LanguageSwitch } from './LanguageSwitch';
import { say, useText, type Texts } from './language';
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
  const texts = useText();
  // re-render on every change of the session
  useSessionState();
  return (action) => {
    if (client === null || action === undefined) return true;
    const may = client.may(action);
    return may === true ? true : say(texts, reasonOf(may));
  };
}

/** Who uses the page, or null without a gate or before the session is known. */
export function useMe(): Me | null {
  const state = useSessionState();
  return state?.kind === 'in' || state?.kind === 'asking' ? state.me : null;
}

/** What reads the session again after a change of the caller's own roles; it does nothing without a gate. */
export function useSessionRefresh(): () => Promise<void> {
  const client = useContext(SessionContext);
  return () => client?.refresh() ?? Promise.resolve();
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

/** `words`, and when to try again when the node said so. */
const waited = ({ t }: Texts, words: string, retryAfter?: string) =>
  retryAfter === undefined ? words : `${words} ${t('frame.session.retryAfter', { seconds: retryAfter })}`;

/** The words for a refused login, or a session that ended. */
function refusedWords(texts: Texts, { code, retryAfter }: Refused): string {
  const { t } = texts;
  switch (code) {
    case 'login-refused':
      return t('frame.login.refused');
    case 'too-many-attempts':
      return waited(texts, t('frame.login.tooMany'), retryAfter);
    case 'busy':
      return waited(texts, t('frame.login.busy'), retryAfter);
    case 'session-ended':
    case 'unknown-credential':
      return t('frame.login.ended');
    case 'blocked':
      return t('frame.login.blocked');
    default:
      return waited(texts, t('frame.login.failed', { code }), retryAfter);
  }
}

/** The name and the Log out of the header, in session mode only. */
export function SessionActions() {
  const client = useContext(SessionContext);
  const state = useSessionState();
  const { t } = useText();
  if (client === null || state?.kind !== 'in' || state.me.session === null) return null;
  return (
    <Flex align="center" gap="small">
      <Typography.Text>
        <UserOutlined aria-hidden /> {state.me.name}
      </Typography.Text>
      <Button size="small" type="text" icon={<LogoutOutlined aria-hidden />} onClick={() => void client.logout()}>
        {t('frame.logout')}
      </Button>
    </Flex>
  );
}

/** The login, or the first password's change, over the open tabs once the session ended; it cannot be dismissed. */
export function SessionPrompt() {
  const client = useContext(SessionContext);
  const state = useSessionState();
  const { t } = useText();
  if (client === null || state?.kind !== 'asking') return null;
  return (
    <Modal open title={t(state.me.firstPassword ? 'frame.password.first' : 'frame.login.again')} footer={null} closable={false} destroyOnHidden>
      {state.me.firstPassword ? (
        <PasswordChange client={client} />
      ) : (
        <>
          <Typography.Paragraph type="secondary">{t('frame.login.waiting', { name: state.me.name })}</Typography.Paragraph>
          <Login client={client} name={state.me.name} refusal={state.refusal} />
        </>
      )}
    </Modal>
  );
}

function Login({ client, name, refusal }: { client: SessionClient; name?: string; refusal?: Refused }) {
  const texts = useText();
  const { t } = texts;
  return (
    <LoginForm<{ name: string; password: string }>
      title={false}
      subTitle={false}
      containerStyle={{ padding: 0 }}
      contentStyle={{ minWidth: 0, width: '100%' }}
      initialValues={{ name }}
      message={refusal && <Alert type="error" showIcon title={refusedWords(texts, refusal)} />}
      submitter={{ searchConfig: { submitText: t('frame.login') } }}
      onFinish={async ({ name: given, password }) => {
        await client.login(given, password);
        return true;
      }}
    >
      <ProFormText
        name="name"
        label={t('frame.login.name')}
        fieldProps={{ prefix: <UserOutlined aria-hidden />, autoComplete: 'username' }}
        rules={[{ required: true, message: t('frame.login.name.required') }]}
      />
      <ProFormText.Password
        name="password"
        label={t('frame.login.password')}
        fieldProps={{ prefix: <LockOutlined aria-hidden />, autoComplete: 'current-password' }}
        rules={[{ required: true, message: t('frame.login.password.required') }]}
      />
    </LoginForm>
  );
}

function PasswordChange({ client }: { client: SessionClient }) {
  const texts = useText();
  const { t } = texts;
  const [refused, setRefused] = useState<string | null>(null);
  return (
    <LoginForm<{ current: string; next: string; repeat: string }>
      title={false}
      subTitle={false}
      containerStyle={{ padding: 0 }}
      contentStyle={{ minWidth: 0, width: '100%' }}
      message={refused !== null && <Alert type="error" showIcon title={refused} />}
      submitter={{ searchConfig: { submitText: t('frame.password.change') } }}
      onFinish={async ({ current, next }) => {
        setRefused(null);
        try {
          await client.changePassword(current, next);
        } catch (error) {
          if (!(error instanceof PasswordRefused)) throw error;
          setRefused(error.code === 'wrong-password' ? t('frame.password.wrong') : waited(texts, `${say(texts, error.reason)}.`, error.retryAfter));
        }
        return true;
      }}
    >
      <Typography.Paragraph type="secondary">{t('frame.password.why', { min: 12, max: 128 })}</Typography.Paragraph>
      <ProFormText.Password
        name="current"
        label={t('frame.password.current')}
        fieldProps={{ autoComplete: 'current-password' }}
        rules={[{ required: true, message: t('frame.password.current.required') }]}
      />
      <ProFormText.Password
        name="next"
        label={t('frame.password.new')}
        fieldProps={{ autoComplete: 'new-password' }}
        rules={[{ required: true, message: t('frame.password.new.required') }, { min: 12, max: 128, message: t('frame.password.length', { min: 12, max: 128 }) }]}
      />
      <ProFormText.Password
        name="repeat"
        label={t('frame.password.repeat')}
        fieldProps={{ autoComplete: 'new-password' }}
        dependencies={['next']}
        rules={[
          { required: true, message: t('frame.password.repeat.required') },
          ({ getFieldValue }) => ({
            validator: (_, value) =>
              value === getFieldValue('next') ? Promise.resolve() : Promise.reject(new Error(t('frame.password.differ'))),
          }),
        ]}
      />
    </LoginForm>
  );
}

/**
 * A page of the gate before the frame: the mark and the name above `children`, in the middle of the window, with the
 * language switch in its corner.
 */
function GatePage({ children }: { children: ReactNode }) {
  return (
    <Rail8Theme>
      {(dark) => (
        <Flex className="rail8-gate" vertical align="center" justify="center" gap="large">
          <div className="rail8-gate-language">
            <LanguageSwitch />
          </div>
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

  const texts = useText();
  return <SessionContext.Provider value={client}>{gated(texts, client, state, children)}</SessionContext.Provider>;
}

function gated(texts: Texts, client: SessionClient, state: SessionState, children: ReactNode): ReactNode {
  const { t } = texts;
  switch (state.kind) {
    case 'starting':
      return (
        <GatePage>
          <ReadSpinner label={t('frame.session.reading')} />
        </GatePage>
      );
    case 'failed':
      return (
        <GatePage>
          <Alert
            type="error"
            showIcon
            title={t('frame.session.unread')}
            description={waited(texts, `${say(texts, state.error)}.`, state.retryAfter)}
            action={
              <Button size="small" onClick={() => void client.start()}>
                {t('frame.tryAgain')}
              </Button>
            }
          />
        </GatePage>
      );
    case 'out':
      return (
        <GatePage>
          <Flex vertical gap="middle">
            <Typography.Title level={2}>{t('frame.login')}</Typography.Title>
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
  const { t } = useText();
  return (
    <GatePage>
      <Flex vertical gap="middle">
        <Typography.Title level={2}>{t('frame.password.first')}</Typography.Title>
        <PasswordChange client={client} />
        <Button type="link" icon={<LogoutOutlined aria-hidden />} onClick={() => void client.logout()}>
          {t('frame.logout.named', { name: me.name })}
        </Button>
      </Flex>
    </GatePage>
  );
}
