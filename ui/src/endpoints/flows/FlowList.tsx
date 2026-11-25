import { PlusCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { ProTable } from '@ant-design/pro-components';
import { Alert, Button, Empty, Flex, Form, Input, Modal, Typography } from 'antd';
import { useCallback, useEffect, useId, useState } from 'react';
import { useText, type Texts } from '../../language';
import { packageColumn } from '../../packageColumn';
import { ReadSpinner } from '../../ReadSpinner';
import { FlowsError, type FlowList as Listed, type FlowsApi } from './api';
import { edit } from './edit';

export interface FlowListProps {
  api: FlowsApi;
  /** Opens the flow file at this path, whose id is `id`, in a tab of its own. */
  onOpen: (path: string, id: string) => void;
}

type Result = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; list: Listed };

/** What the New flow dialog holds: the file's path, whether it was typed rather than made from the id, and the flow's first keys. */
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
 * The flow files of the working folder: each one's id, package and path, the id opening its picture in a tab of its
 * own, with a filter on the package; a file that cannot be read shows why, and opens with its text. Without a working folder it says how to set one. New flow
 * writes a flow file that does not exist yet, and opens it.
 */
export function FlowList({ api, onOpen }: FlowListProps) {
  const texts = useText();
  const { t, rich } = texts;
  const [result, setResult] = useState<Result>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const refresh = useCallback(() => setAsked((n) => n + 1), []);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [creating, setCreating] = useState(false);
  const [createFailure, setCreateFailure] = useState<string | null>(null);
  const ids = useId();

  useEffect(() => {
    const controller = new AbortController();
    const { signal } = controller;
    api.list(signal).then(
      (list) => !signal.aborted && setResult({ kind: 'read', list }),
      (error: unknown) => !signal.aborted && setResult({ kind: 'failed', message: error instanceof Error ? error.message : String(error) }),
    );
    return () => controller.abort();
  }, [api, asked]);

  // a new flow's file goes beside the first flow listed, else at the top of the working folder
  const first = result.kind === 'read' ? result.list.items[0]?.path : undefined;
  const folder = first === undefined ? '' : first.slice(0, first.lastIndexOf('/') + 1);
  const wrong = draft === null ? {} : wrongOf(texts, draft);
  const create = async () => {
    if (draft === null || Object.keys(wrong).length > 0) return;
    setCreating(true);
    setCreateFailure(null);
    try {
      await api.save(draft.path, newFlowText(draft.id, draft.from, draft.to), null);
      setDraft(null);
      refresh();
      onOpen(draft.path, draft.id);
    } catch (error) {
      setCreateFailure(
        error instanceof FlowsError && error.status === 412 ? t('flows.new.exists', { path: draft.path }) : error instanceof Error ? error.message : String(error),
      );
    } finally {
      setCreating(false);
    }
  };
  const field = (name: 'path' | 'id' | 'from' | 'to', label: string, help: string) => {
    const issue = draft?.[name] ? wrong[name] : undefined;
    return (
      <Form.Item label={label} required htmlFor={`${ids}-${name}`} validateStatus={issue ? 'error' : undefined} help={<span id={`${ids}-${name}-help`}>{issue ?? help}</span>}>
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
                  ? { ...d, id: text, path: `${folder}${text}.yaml` }
                  : { ...d, [name]: text, ...(name === 'path' ? { pathTyped: true } : {}) },
            )
          }
        />
      </Form.Item>
    );
  };

  let body;
  if (result.kind === 'reading') {
    body = <ReadSpinner label={t('endpoints.folder.reading')} />;
  } else if (result.kind === 'failed') {
    body = <Alert showIcon type="error" title={t('endpoints.folder.unread')} description={result.message} />;
  } else if (!result.list.configured) {
    body = (
      <Empty
        description={
          <>
            <Typography.Title level={2}>{t('endpoints.folder.none')}</Typography.Title>
            {rich('endpoints.folder.none.hint', { code: (chunks) => <code>{chunks}</code> })}
          </>
        }
      />
    );
  } else if (result.list.items.length === 0) {
    body = (
      <Empty
        description={
          <>
            <Typography.Title level={2}>{t('flows.list.empty')}</Typography.Title>
            {t('flows.list.empty.hint')}
          </>
        }
      />
    );
  } else {
    body = (
      <Flex vertical gap="middle">
        {result.list.truncated && (
          <Alert
            type="warning"
            showIcon
            title={t('endpoints.folder.truncated')}
            description={t('endpoints.folder.truncated.hint')}
          />
        )}
        <ProTable<Listed['items'][number]>
          aria-label={t('flows.list.files')}
          rowKey="path"
          size="small"
          dataSource={result.list.items}
          search={false}
          options={false}
          toolBarRender={false}
          pagination={false}
          columns={[
            {
              title: t('flows.list.flow'),
              key: 'flow',
              render: (_, item) => {
                const id = 'id' in item ? item.id : (item.path.split('/').at(-1) ?? item.path);
                return (
                  <Flex vertical align="start">
                    <Button type="link" onClick={() => onOpen(item.path, id)}>
                      {id}
                    </Button>
                    {'problem' in item && <Typography.Text type="danger">{item.problem}</Typography.Text>}
                  </Flex>
                );
              },
            },
            packageColumn(result.list.items, (item) => item.path),
            { title: t('endpoints.list.file'), dataIndex: 'path' },
          ]}
        />
      </Flex>
    );
  }

  return (
    <Flex vertical gap="middle">
      <Flex align="center" gap="small" wrap>
        <Typography.Title level={1} style={{ margin: 0 }}>
          {t('flows.screen.title')}
        </Typography.Title>
        <Flex gap="small" style={{ marginInlineStart: 'auto' }}>
          <Button icon={<ReloadOutlined aria-hidden />} onClick={refresh}>
            {t('endpoints.refresh')}
          </Button>
          {result.kind === 'read' && result.list.configured && (
            <Button
              type="primary"
              icon={<PlusCircleOutlined aria-hidden />}
              onClick={() => {
                setCreateFailure(null);
                setDraft({ path: folder, pathTyped: false, id: '', from: '', to: '' });
              }}
            >
              {t('flows.new')}
            </Button>
          )}
        </Flex>
      </Flex>
      <Typography.Text type="secondary">{t('flows.list.intro')}</Typography.Text>
      {body}
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
          {field('path', t('endpoints.list.file'), t('flows.new.path.help'))}
          {field('from', t('flows.new.from'), t('flows.new.from.help'))}
          {field('to', t('flows.new.to'), t('flows.new.to.help'))}
          {createFailure !== null && <Alert type="error" showIcon title={t('flows.new.failed')} description={createFailure} />}
        </Form>
      </Modal>
    </Flex>
  );
}
