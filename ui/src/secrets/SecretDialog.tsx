import { Alert, Button, Flex, Form, Input, Radio, theme, Typography } from 'antd';
import { useState } from 'react';
import { useText } from '../language';
import { MountedModal } from '../MountedModal';
import { refusalOf } from '../refusal';
import { KINDS, SecretsError, type SecretEntry, type SecretKind, type SecretsApi, type SecretValue } from './api';
import { kindWords } from './parts';

export interface SecretDialogProps {
  api: SecretsApi;
  /** The secret's name, fixed; without it the person types one. */
  name?: string;
  /** The kinds the secret may be, in the order offered, the first chosen at first; every kind without it. */
  kinds?: readonly SecretKind[];
  /** True to overwrite the secret `name` with a value of its kind, the one `kinds` names, rather than create it. */
  overwrite?: boolean;
  /** Called with the secret's entry once it is written, or with null when the dialog closes without writing. */
  onClose: (written: SecretEntry | null) => void;
}

/** The longest name, in characters, and its form. */
const NAME_MAX = 128;
const NAME = /^[a-z0-9][a-z0-9._-]*$/;

const CERTIFICATE = /-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/g;

/** The certificates of `text`, each PEM block apart, the leaf first; a text with no block goes as it is, for the node to refuse. */
const certificatesOf = (text: string) => {
  const blocks = text.match(CERTIFICATE) ?? [];
  return blocks.length > 0 ? blocks : text.trim() === '' ? [] : [text];
};

/** What the fields hold while the dialog is open: emptied once the form is sent, and gone with the dialog once it is closed. */
interface Typed {
  value: string;
  user: string;
  password: string;
  privateKey: string;
  certificates: string;
}

const EMPTY: Typed = { value: '', user: '', password: '', privateKey: '', certificates: '' };

/**
 * The form that creates a secret, or overwrites one, in a dialog: its name, its kind, and the fields of that kind — a plain
 * value; a user and a password; or a private key in PEM (PKCS#8 or unencrypted OpenSSH) with its certificates, the leaf
 * first. A connection's form opens it as it is, with the name and the kinds its field takes. No field ever shows a value
 * the store holds, and once the form is sent, whatever the answer, or closed, every field of the value is emptied, so the
 * page keeps none of it. A refusal shows in the UI's words by its code, and a field the node names as wrong is marked.
 */
export function SecretDialog({ api, name: given, kinds = KINDS, overwrite = false, onClose }: SecretDialogProps) {
  const texts = useText();
  const { t } = texts;
  const { token } = theme.useToken();
  const [name, setName] = useState(given ?? '');
  const [kind, setKind] = useState<SecretKind>(kinds[0] ?? 'value');
  const [typed, setTyped] = useState<Typed>(EMPTY);
  const [sending, setSending] = useState(false);
  const [refused, setRefused] = useState<{ text: string; code?: string; field?: string } | null>(null);
  const id = (field: string) => `rail8-secret-${field}`;

  const nameValid = name.length <= NAME_MAX && NAME.test(name);
  const complete =
    kind === 'value' ? typed.value !== '' : kind === 'credentials' ? typed.user !== '' && typed.password !== '' : typed.privateKey.trim() !== '';
  const set = (field: keyof Typed) => (event: { target: { value: string } }) => setTyped((now) => ({ ...now, [field]: event.target.value }));

  const send = async () => {
    const value: SecretValue =
      kind === 'value'
        ? { kind, value: typed.value }
        : kind === 'credentials'
          ? { kind, user: typed.user, password: typed.password }
          : { kind, privateKey: typed.privateKey, certificates: certificatesOf(typed.certificates) };
    setTyped(EMPTY);
    setSending(true);
    setRefused(null);
    try {
      onClose(overwrite ? await api.overwrite(name, value) : await api.create(name, value));
    } catch (error) {
      const code = error instanceof SecretsError ? error.code : undefined;
      const field = error instanceof SecretsError && code === 'invalid' ? refusalOf(error.body).values.field : undefined;
      setRefused({ text: error instanceof Error ? error.message : String(error), ...(code === undefined ? {} : { code }), ...(typeof field === 'string' ? { field } : {}) });
      setSending(false);
    }
  };

  /** The mark of the field `field` when the node named it as wrong. */
  const marked = (field: string) => (refused?.field === field ? ('error' as const) : undefined);
  const secretInput = (field: 'value' | 'password', label: string) => (
    <Form.Item label={label} htmlFor={id(field)} required validateStatus={marked(field)}>
      <Input.Password id={id(field)} value={typed[field]} onChange={set(field)} visibilityToggle={false} autoComplete="new-password" />
    </Form.Item>
  );

  return (
    <MountedModal
      title={overwrite ? t('secrets.overwrite.title', { name }) : t('secrets.new')}
      onCancel={sending ? undefined : () => onClose(null)}
      width={640}
      footer={[
        <Button key="cancel" disabled={sending} onClick={() => onClose(null)}>
          {t('frame.cancel')}
        </Button>,
        <Button key="write" type="primary" loading={sending} disabled={!nameValid || !complete} onClick={() => void send()}>
          {overwrite ? t('secrets.overwrite') : t('secrets.create')}
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        {refused !== null && (
          <Alert
            type="error"
            showIcon
            title={overwrite ? t('secrets.overwrite.failed', { name }) : t('secrets.create.failed')}
            description={
              <Flex vertical>
                <span>{refused.text}</span>
                {/* a write that committed is in force: it must not be sent again */}
                {refused.code !== 'written-unread' && <Typography.Text type="secondary">{t('secrets.enterAgain')}</Typography.Text>}
              </Flex>
            }
          />
        )}
        <Typography.Text type="secondary">{t(overwrite ? 'secrets.overwrite.hint' : 'secrets.new.hint')}</Typography.Text>
        <Form layout="vertical" requiredMark autoComplete="off">
          {given === undefined ? (
            <Form.Item
              label={t('secrets.name')}
              htmlFor={id('name')}
              required
              validateStatus={marked('name') ?? (name === '' || nameValid ? undefined : 'error')}
              help={t('secrets.name.help', { max: NAME_MAX })}
            >
              <Input id={id('name')} value={name} autoComplete="off" spellCheck={false} onChange={(event) => setName(event.target.value)} />
            </Form.Item>
          ) : (
            <Form.Item label={t('secrets.name')}>
              <Typography.Text code>{given}</Typography.Text>
            </Form.Item>
          )}
          <Form.Item label={t('secrets.kind')}>
            {kinds.length > 1 ? (
              <Radio.Group
                aria-label={t('secrets.kind')}
                optionType="button"
                value={kind}
                onChange={(event) => {
                  setKind(event.target.value as SecretKind);
                  setTyped(EMPTY);
                }}
                options={kinds.map((k) => ({ value: k, label: kindWords(texts, k) }))}
              />
            ) : (
              <Typography.Text>{kindWords(texts, kind)}</Typography.Text>
            )}
          </Form.Item>
          {kind === 'value' && secretInput('value', t('secrets.value'))}
          {kind === 'credentials' && (
            <>
              <Form.Item label={t('secrets.user')} htmlFor={id('user')} required validateStatus={marked('user')}>
                <Input id={id('user')} value={typed.user} onChange={set('user')} autoComplete="off" spellCheck={false} />
              </Form.Item>
              {secretInput('password', t('secrets.password'))}
            </>
          )}
          {kind === 'key-pair' && (
            <>
              <Form.Item label={t('secrets.privateKey')} htmlFor={id('privateKey')} required validateStatus={marked('privateKey')} help={t('secrets.privateKey.help')}>
                <Input.TextArea
                  id={id('privateKey')}
                  value={typed.privateKey}
                  onChange={set('privateKey')}
                  autoComplete="off"
                  spellCheck={false}
                  autoSize={{ minRows: 4, maxRows: 8 }}
                  style={{ fontFamily: token.fontFamilyCode }}
                />
              </Form.Item>
              <Form.Item label={t('secrets.certificates')} htmlFor={id('certificates')} validateStatus={marked('certificates')} help={t('secrets.certificates.help')}>
                <Input.TextArea
                  id={id('certificates')}
                  value={typed.certificates}
                  onChange={set('certificates')}
                  autoComplete="off"
                  spellCheck={false}
                  autoSize={{ minRows: 4, maxRows: 8 }}
                  style={{ fontFamily: token.fontFamilyCode }}
                />
              </Form.Item>
            </>
          )}
        </Form>
      </Flex>
    </MountedModal>
  );
}
