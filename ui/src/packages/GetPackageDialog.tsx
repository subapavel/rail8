import { Alert, Button, Flex, Form, Input, Typography } from 'antd';
import { useId, useState } from 'react';
import { MountedModal } from '../MountedModal';
import { PackagesError, type CloneAnswer, type PackagesApi } from './api';
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
        title={`Got ${answer.repository}`}
        onCancel={onClose}
        footer={[
          <Button key="close" type="primary" onClick={onClose}>
            Close
          </Button>,
        ]}
      >
        {'reason' in answer ? (
          <Alert type="warning" showIcon title={`The folder ${answer.repository} holds the clone, but it is no package`} description={answer.reason} />
        ) : (
          <Alert type="warning" showIcon title={`The folder ${answer.repository} holds the clone, but its state could not be read`} description={answer.problem} />
        )}
      </MountedModal>
    );
  }

  return (
    <MountedModal
      title="Get a package"
      onCancel={getting ? undefined : onClose}
      closable={!getting}
      width={640}
      footer={[
        <Button key="cancel" disabled={getting} onClick={onClose}>
          Cancel
        </Button>,
        <Button key="get" type="primary" loading={getting} disabled={!ready && !getting} onClick={() => void get()}>
          Get
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        <Typography.Text>Clones a repository into a new folder of the working folder, named as the repository, as git clone does in a terminal.</Typography.Text>
        {shown.kind === 'refused' && (
          <Alert
            type="error"
            showIcon
            title="The package was not got"
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
        <Form layout="vertical" requiredMark onFinish={() => void get()}>
          <Form.Item
            label="Address"
            htmlFor={addressId}
            required
            style={{ marginBottom: 0 }}
            validateStatus={source !== null && 'error' in source ? 'error' : undefined}
            help={
              source === null
                ? 'The https or ssh address of the repository, as its Clone button gives it'
                : 'error' in source
                  ? source.error
                  : `Goes into the folder ${source.repository}`
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
