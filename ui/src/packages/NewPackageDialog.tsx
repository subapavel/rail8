import { Alert, Button, Flex, Form, Input, Typography } from 'antd';
import { useId, useState } from 'react';
import { useText } from '../language';
import { MountedModal } from '../MountedModal';
import { PackagesError, type PackagesApi } from './api';
import { NEW_PACKAGE_TEXTS, refusalIn } from './refusals';
import { NEW_PACKAGE_REFUSALS, newPackageBodyOf } from './rules';

export interface NewPackageDialogProps {
  api: PackagesApi;
  /** Given the folder of the new package, which the screen then opens in its tab. */
  onPackage: (repository: string) => void;
  /** Called after every new package put in place, so the list reads the working folder again. */
  onMade: () => void;
  onClose: () => void;
}

type Shown = { kind: 'asking' } | { kind: 'making' } | { kind: 'refused'; error: string; output?: string } | { kind: 'made'; repository: string; problem: string };

/**
 * The dialog of New package: the repository's name, which names the new folder of the working folder, and the
 * package's name, each saying as the author types why it is refused. Create makes the package there, a git repository
 * of its own with its first commit; nothing closes the dialog meanwhile. A new package closes the dialog and opens its
 * tab. One whose state could not be read stays in place, and the dialog says why. A refusal says why, with what git
 * said. The dialog says that a deployed environment needs the repository on the git host first.
 */
export function NewPackageDialog({ api, onPackage, onMade, onClose }: NewPackageDialogProps) {
  const [repository, setRepository] = useState('');
  const [name, setName] = useState('');
  const [shown, setShown] = useState<Shown>({ kind: 'asking' });
  const ids = useId();
  const texts = useText();
  const { t, rich } = texts;
  const refusal = (error: string) => refusalIn(texts, NEW_PACKAGE_REFUSALS, NEW_PACKAGE_TEXTS, error);
  const making = shown.kind === 'making';
  // each field's own refusal: the other field is given a name the rule takes, so only this one's is found
  const repositoryError = repository === '' ? null : newPackageBodyOf({ repository, name: 'x' });
  const nameError = name === '' ? null : newPackageBodyOf({ repository: 'x', name });
  const ready = !making && !('error' in newPackageBodyOf({ repository, name }));

  const create = async () => {
    if (!ready) return;
    setShown({ kind: 'making' });
    try {
      const answer = await api.create(repository, name);
      onMade();
      if (answer.package !== null) {
        onClose();
        onPackage(answer.repository);
      } else {
        setShown({ kind: 'made', repository: answer.repository, problem: answer.problem });
      }
    } catch (error) {
      if (error instanceof PackagesError) setShown({ kind: 'refused', error: error.message, ...(error.output === undefined ? {} : { output: error.output }) });
      else setShown({ kind: 'refused', error: error instanceof Error ? error.message : String(error) });
    }
  };

  if (shown.kind === 'made') {
    return (
      <MountedModal
        title={t('packages.new.made', { folder: shown.repository })}
        onCancel={onClose}
        footer={[
          <Button key="close" type="primary" onClick={onClose}>
            {t('packages.close')}
          </Button>,
        ]}
      >
        <Alert type="warning" showIcon title={t('packages.new.unread', { folder: shown.repository })} description={shown.problem} />
      </MountedModal>
    );
  }

  return (
    <MountedModal
      title={t('packages.new')}
      onCancel={making ? undefined : onClose}
      closable={!making}
      width={640}
      footer={[
        <Button key="cancel" disabled={making} onClick={onClose}>
          {t('frame.cancel')}
        </Button>,
        <Button key="create" type="primary" loading={making} disabled={!ready && !making} onClick={() => void create()}>
          {t('packages.new.create')}
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        <Typography.Text>{rich('packages.new.intro', { code: (chunks) => <code>{chunks}</code> })}</Typography.Text>
        <Alert type="info" showIcon title={t('packages.new.noRemote')} description={t('packages.new.noRemote.description')} />
        {shown.kind === 'refused' && (
          <Alert
            type="error"
            showIcon
            title={t('packages.new.refused')}
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
        <Form layout="vertical" requiredMark>
          <Form.Item
            label={t('packages.repository')}
            htmlFor={`${ids}-repository`}
            required
            validateStatus={repositoryError !== null && 'error' in repositoryError ? 'error' : undefined}
            help={repositoryError !== null && 'error' in repositoryError ? refusal(repositoryError.error) : t('packages.new.repository.help')}
          >
            <Input
              id={`${ids}-repository`}
              autoFocus
              disabled={making}
              spellCheck={false}
              autoComplete="off"
              placeholder="orders"
              value={repository}
              onChange={(event) => setRepository(event.target.value)}
            />
          </Form.Item>
          <Form.Item
            label={t('packages.new.name')}
            htmlFor={`${ids}-name`}
            required
            style={{ marginBottom: 0 }}
            validateStatus={nameError !== null && 'error' in nameError ? 'error' : undefined}
            help={nameError !== null && 'error' in nameError ? refusal(nameError.error) : t('packages.new.name.help')}
          >
            <Input
              id={`${ids}-name`}
              disabled={making}
              spellCheck={false}
              autoComplete="off"
              placeholder="orders"
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </Form.Item>
        </Form>
      </Flex>
    </MountedModal>
  );
}
