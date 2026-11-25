import { PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Form, Input, Modal, Space } from 'antd';
import { useId, useState } from 'react';
import type { NewArtifactProps } from '../../artifacts';
import { useText, type Texts } from '../../language';
import { FlowsError, type FlowsApi } from './api';
import { edit } from './edit';

/** What the New flow dialog holds: the file's path within its folder, whether it was typed rather than made from the id, and the flow's first keys. */
interface Draft {
  path: string;
  pathTyped: boolean;
  id: string;
  from: string;
  to: string;
}

const ID = /^[a-z][a-z0-9-]*$/;

/** Why each value of `draft` cannot make a flow, by its name, in `texts`' language. */
function wrongOf({ t }: Texts, draft: Draft): Partial<Record<keyof Draft, string>> {
  const wrong: Partial<Record<keyof Draft, string>> = {};
  if (!/^[^/].*\.ya?ml$/.test(draft.path) || draft.path.split('/').includes('..')) wrong.path = t('flows.new.badPath');
  for (const k of ['id', 'from', 'to'] as const) if (!ID.test(draft[k])) wrong[k] = t('flows.new.badId');
  return wrong;
}

/**
 * The text of a new flow: the least the schema takes, a synchronous flow arriving at the endpoint `from` with one step
 * that sends to the endpoint `to`.
 */
export const newFlowText = (id: string, from: string, to: string) =>
  edit('', {
    op: 'add',
    path: [],
    value: {
      schemaVersion: 1,
      kind: 'flow',
      id,
      version: '0.1.0',
      from: { endpoint: from },
      delivery: { mode: 'sync' },
      limits: { maxBodyBytes: 1048576 },
      steps: [{ type: 'to', endpoint: to }],
    },
  });

/**
 * New flow, and the dialog it opens: the flow's id, its file's path within `folder`, made from the id beside the file
 * `beside`, else in `flows/`, until the path is typed, the endpoint it arrives at and the one its step sends to. Create
 * writes a flow file that does not exist yet in `folder`, then tells `onCreated` and opens it with `openFlow`.
 */
export function NewFlow({ api, folder, beside, onCreated, openFlow }: Pick<NewArtifactProps, 'folder' | 'beside' | 'onCreated'> & {
  api: FlowsApi;
  /** Opens the flow file at `path`, relative to the working folder, whose id is `id`. */
  openFlow: (path: string, id: string) => void;
}) {
  const texts = useText();
  const { t } = texts;
  const [draft, setDraft] = useState<Draft | null>(null);
  const [creating, setCreating] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const ids = useId();
  // the folder within `folder` a new flow goes in: the one of the flow beside it, else flows/
  const within = beside === undefined ? 'flows/' : beside.slice(folder.length + 1, beside.lastIndexOf('/') + 1);
  const wrong = draft === null ? {} : wrongOf(texts, draft);
  const create = async () => {
    if (draft === null || Object.keys(wrong).length > 0) return;
    const path = `${folder}/${draft.path}`;
    setCreating(true);
    setFailure(null);
    try {
      await api.save(path, newFlowText(draft.id, draft.from, draft.to), null);
      setDraft(null);
      onCreated();
      openFlow(path, draft.id);
    } catch (error) {
      setFailure(error instanceof FlowsError && error.status === 412 ? t('flows.new.exists', { path }) : error instanceof Error ? error.message : String(error));
    } finally {
      setCreating(false);
    }
  };
  const field = (name: 'path' | 'id' | 'from' | 'to', label: string, help: string) => {
    const issue = draft?.[name] ? wrong[name] : undefined;
    const input = (
      <Input
        required
        id={`${ids}-${name}`}
        aria-label={label}
        aria-describedby={`${ids}-${name}-help`}
        aria-invalid={issue !== undefined}
        value={draft?.[name] ?? ''}
        onChange={({ target: { value: text } }) =>
          setDraft((d) =>
            d === null
              ? d
              : name === 'id' && !d.pathTyped
                ? { ...d, id: text, path: `${within}${text}.yaml` }
                : { ...d, [name]: text, ...(name === 'path' ? { pathTyped: true } : {}) },
          )
        }
      />
    );
    return (
      <Form.Item label={label} required htmlFor={`${ids}-${name}`} validateStatus={issue ? 'error' : undefined} help={<span id={`${ids}-${name}-help`}>{issue ?? help}</span>}>
        {name === 'path' ? (
          <Space.Compact block>
            <Space.Addon>{`${folder}/`}</Space.Addon>
            {input}
          </Space.Compact>
        ) : (
          input
        )}
      </Form.Item>
    );
  };

  return (
    <>
      <Button
        icon={<PlusOutlined aria-hidden />}
        onClick={() => {
          setFailure(null);
          setDraft({ path: within, pathTyped: false, id: '', from: '', to: '' });
        }}
      >
        {t('flows.new')}
      </Button>
      <Modal
        open={draft !== null}
        title={t('flows.new')}
        okText={t('flows.new.create')}
        confirmLoading={creating}
        okButtonProps={{ 'aria-label': t('flows.new.create'), 'aria-busy': creating, htmlType: 'submit', form: `${ids}-form`, disabled: creating || draft === null || Object.keys(wrong).length > 0 }}
        cancelButtonProps={{ disabled: creating }}
        onCancel={() => !creating && setDraft(null)}
      >
        <p>{t('flows.new.hint')}</p>
        <Form layout="vertical" id={`${ids}-form`} onFinish={() => void create()}>
          {field('id', t('flows.new.id'), t('flows.new.id.help'))}
          {field('path', t('flows.new.file'), t('flows.new.path.help'))}
          {field('from', t('flows.new.from'), t('flows.new.from.help'))}
          {field('to', t('flows.new.to'), t('flows.new.to.help'))}
          {failure !== null && <Alert type="error" showIcon title={t('flows.new.failed')} description={failure} />}
        </Form>
      </Modal>
    </>
  );
}
