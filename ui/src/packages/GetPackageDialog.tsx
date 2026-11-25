import { Alert, Button, Flex, Form, Input, Typography } from 'antd';
import { useId, useState } from 'react';
import { useText } from '../language';
import { MountedModal } from '../MountedModal';
import { PackagesError, type CloneAnswer, type PackagesApi } from './api';
import { refusedIn } from './refusals';
import { cloneSourceOf } from './rules';

export interface GetPackageDialogProps {
  api: PackagesApi;
  /** Given the folder of a clone that is a package, which the screen then opens in its tab. */
  onPackage: (repository: string) => void;
  /** Called after every clone put in place, a package or not, so the list reads the working folder again. */
  onCloned: () => void;
  onClose: () => void;
}

type Shown = { kind: 'asking' } | { kind: 'getting' } | { kind: 'refused'; error: string; output?: string } | { kind: 'got'; answer: Exclude<CloneAnswer, { package: object }> };

/**
 * The dialog of Get a package: one field for the https or ssh address of a repository, which, as the author types,
 * says which folder of the working folder the clone goes into, or why the address is refused. Get clones it there and
 * waits for git; nothing closes the dialog meanwhile. A clone that is a package closes the dialog and opens its tab. A
 * clone that is no package, or whose state could not be read, stays in place, and the dialog says why. A refusal says
 * why, with what git said.
 */
export function GetPackageDialog({ api, onPackage, onCloned, onClose }: GetPackageDialogProps) {
  const [text, setText] = useState('');
  const [shown, setShown] = useState<Shown>({ kind: 'asking' });
  const addressId = useId();
  const texts = useText();
  const { t } = texts;
  const source = text.trim() === '' ? null : cloneSourceOf(text);
  const getting = shown.kind === 'getting';
  const ready = source !== null && 'address' in source && !getting;

  const get = async () => {
    if (!ready) return;
    setShown({ kind: 'getting' });
    try {
      const answer = await api.clone(text);
      onCloned();
      if (answer.package !== null) {
        onClose();
        onPackage(answer.repository);
      } else {
        setShown({ kind: 'got', answer });
      }
    } catch (error) {
      if (error instanceof PackagesError) setShown({ kind: 'refused', error: error.message, ...(error.output === undefined ? {} : { output: error.output }) });
      else setShown({ kind: 'refused', error: error instanceof Error ? error.message : String(error) });
    }
  };

  if (shown.kind === 'got') {
    const { answer } = shown;
    return (
      <MountedModal
        title={t('packages.get.got', { folder: answer.repository })}
        onCancel={onClose}
        footer={[
          <Button key="close" type="primary" onClick={onClose}>
            {t('packages.close')}
          </Button>,
        ]}
      >
        {'reason' in answer ? (
          <Alert type="warning" showIcon title={t('packages.get.noPackage', { folder: answer.repository })} description={answer.reason} />
        ) : (
          <Alert type="warning" showIcon title={t('packages.get.unread', { folder: answer.repository })} description={answer.problem} />
        )}
      </MountedModal>
    );
  }

  return (
    <MountedModal
      title={t('packages.get')}
      onCancel={getting ? undefined : onClose}
      closable={!getting}
      width={640}
      footer={[
        <Button key="cancel" disabled={getting} onClick={onClose}>
          {t('frame.cancel')}
        </Button>,
        <Button key="get" type="primary" loading={getting} disabled={!ready && !getting} onClick={() => void get()}>
          {t('packages.get.get')}
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        <Typography.Text>{t('packages.get.intro')}</Typography.Text>
        {shown.kind === 'refused' && (
          <Alert
            type="error"
            showIcon
            title={t('packages.get.refused')}
            description={
              <Flex vertical gap="small">
                <span>{shown.error}</span>
                {shown.output !== undefined && shown.output !== '' && (
                  <Typography.Paragraph style={{ marginBottom: 0 }}>
                    <pre aria-label={t('packages.gitSaid')} style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
                      {shown.output}
                    </pre>
                  </Typography.Paragraph>
                )}
              </Flex>
            }
          />
        )}
        <Form layout="vertical" requiredMark onFinish={() => void get()}>
          <Form.Item
            label={t('packages.get.address')}
            htmlFor={addressId}
            required
            style={{ marginBottom: 0 }}
            validateStatus={source !== null && 'error' in source ? 'error' : undefined}
            help={
              source === null
                ? t('packages.get.address.help')
                : 'error' in source
                  ? refusedIn(texts, source)
                  : t('packages.get.into', { folder: source.repository })
            }
          >
            <Input
              id={addressId}
              autoFocus
              disabled={getting}
              spellCheck={false}
              autoComplete="off"
              placeholder="https://github.com/acme/orders.git"
              value={text}
              onChange={(event) => setText(event.target.value)}
            />
          </Form.Item>
        </Form>
      </Flex>
    </MountedModal>
  );
}
