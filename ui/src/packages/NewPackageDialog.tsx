import { Alert, Button, Flex, Form, Input, Typography } from 'antd';
import { useId, useState } from 'react';
import { MountedModal } from '../MountedModal';
import { PackagesError, type PackagesApi } from './api';
import { newPackageBodyOf } from './rules';

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
        title={`Made ${shown.repository}`}
        onCancel={onClose}
        footer={[
          <Button key="close" type="primary" onClick={onClose}>
            Close
          </Button>,
        ]}
      >
        <Alert type="warning" showIcon title={`The folder ${shown.repository} holds the new package, but its state could not be read`} description={shown.problem} />
      </MountedModal>
    );
  }

  return (
    <MountedModal
      title="New package"
      onCancel={making ? undefined : onClose}
      closable={!making}
      width={640}
      footer={[
        <Button key="cancel" disabled={making} onClick={onClose}>
          Cancel
        </Button>,
        <Button key="create" type="primary" loading={making} disabled={!ready && !making} onClick={() => void create()}>
          Create
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        <Typography.Text>
          Makes a new folder of the working folder, named as the repository: a git repository of its own on <code>main</code>, whose first commit holds{' '}
          <code>package.yaml</code> at version 0.1.0, <code>package.lock</code> and <code>.gitattributes</code>, with your git identity.
        </Typography.Text>
        <Alert
          type="info"
          showIcon
          title="It adds no remote"
          description="The node of the local run reads the working folder, so it can deploy the package at once. A deployed environment needs the repository on the git host first: make it there, add it as the remote in a terminal, and push."
        />
        {shown.kind === 'refused' && (
          <Alert
            type="error"
            showIcon
            title="The package was not made"
            description={
              <Flex vertical gap="small">
                <span>{shown.error}</span>
                {shown.output !== undefined && shown.output !== '' && (
                  <Typography.Paragraph style={{ marginBottom: 0 }}>
                    <pre aria-label="What git said" style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
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
            label="Repository"
            htmlFor={`${ids}-repository`}
            required
            validateStatus={repositoryError !== null && 'error' in repositoryError ? 'error' : undefined}
            help={repositoryError !== null && 'error' in repositoryError ? repositoryError.error : `The repository's name on the git host, and the folder's name: what a deploy and a pin name`}
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
            label="Package name"
            htmlFor={`${ids}-name`}
            required
            style={{ marginBottom: 0 }}
            validateStatus={nameError !== null && 'error' in nameError ? 'error' : undefined}
            help={nameError !== null && 'error' in nameError ? nameError.error : 'The name in package.yaml, which a flow’s qualified id starts with'}
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
