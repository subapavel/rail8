import { CheckCircleOutlined, EditOutlined, SaveOutlined } from '@ant-design/icons';
import { Alert, App, Button, Flex, Form, Modal, Spin, Tabs, Tag, Typography } from 'antd';
import { useEffect, useId, useState, type ReactNode } from 'react';
import { DiscardDialog } from '../DiscardDialog';
import type { EndpointsApi, StoredEndpoint } from './api';
import { FieldControl } from './FieldControl';
import { inboundAddress, optionsIn, type NodeIngress } from './inbound';
import { RawOptions } from './RawOptions';
import { sameValue } from './same-value';
import { checkOptions, shownFields, type Options, type Problem, type SettingsDescription, type SettingsField } from './settings';

/** Asked before the endpoint's tab closes while it has unsaved changes: it calls `close` once the author agrees. */
export type CloseGuard = (close: () => void) => void;

export interface EndpointFormProps {
  api: EndpointsApi;
  /** The endpoint file's path in the working folder. */
  path: string;
  /** Given the question to ask before the tab closes while it has unsaved changes, and null when it has none. */
  guardClose?: (guard: CloseGuard | null) => void;
}

/**
 * The component's description, while it is read, once read with its HTTP prefix and where the node takes requests, or
 * why it could not be.
 */
type Described = { kind: 'reading' } | { kind: 'read'; settings: SettingsDescription; httpPrefix: string | null; ingress: NodeIngress } | { kind: 'missing'; why: string };

/**
 * Under the address of an in endpoint: a preview of the URL senders call, or of the path on the node's HTTP port when
 * the node names no origin, built from the options shown with the node environment's overlay laid over them.
 */
function ArrivalNote({ url, ingress, overlaid }: { url: string; ingress: NodeIngress; overlaid: boolean }) {
  const where =
    ingress.httpOrigin === null ? (
      <>
        The path <strong>{url}</strong> on the node&apos;s HTTP port; the node names no origin for it (RAIL8_HTTP_ORIGIN).
      </>
    ) : (
      <>
        Senders call <strong>{url}</strong>.
      </>
    );
  return (
    <>
      <Tag>Preview</Tag> {where} Built from these settings
      {overlaid ? `, with the address environment ${ingress.environment} sets` : ` in environment ${ingress.environment}`}, not from what is deployed.
    </>
  );
}

type Discarding = { then: 'return to view mode' } | { then: 'close its tab'; close: () => void };

/** The options as they are saved: keys whose value is undefined are left out. */
function present(options: Options): Options {
  return Object.fromEntries(Object.entries(options).filter(([, v]) => v !== undefined));
}

/** The option a problem's JSON pointer names, or null for a rule over several. */
function optionOf(path: string): string | null {
  return path === '' ? null : path.slice(1).replaceAll('~1', '/').replaceAll('~0', '~');
}

/** A key of the file that no field describes, drawn as a field of no known type. */
function undescribed(name: string): SettingsField {
  return { name, type: 'raw', help: "No such setting in the component's description.", tab: 'general', required: false, directions: ['in', 'out'] };
}

/**
 * An endpoint's settings form, drawn from its component's settings description: the endpoint's id, scheme, direction
 * and file; the environments that override options; then the description's tabs, each with the fields it holds that
 * serve the endpoint's direction and are shown, and the problems the check finds in them. It opens read-only; Edit, when
 * the file may be edited and the description could be read, then Cancel, Check and Save. Save writes only `config`,
 * sending the version it read, and a file changed on disk since opens a dialog. Cancel, or closing the tab, with unsaved
 * changes asks first, and the page asks before it unloads. Without the description the file's options are shown as
 * JSON, read-only, with the reason.
 */
export function EndpointForm({ api, path, guardClose }: EndpointFormProps) {
  const [stored, setStored] = useState<StoredEndpoint | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [described, setDescribed] = useState<Described>({ kind: 'reading' });
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<Options>({});
  // a new generation remounts the controls, so the text a raw editor held goes with the draft it belonged to
  const [generation, setGeneration] = useState(0);
  const [invalid, setInvalid] = useState<ReadonlySet<string>>(new Set());
  const [tab, setTab] = useState('general');
  const [check, setCheck] = useState<Problem[] | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveFailure, setSaveFailure] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const { notification } = App.useApp();
  const [discarding, setDiscarding] = useState<Discarding | null>(null);
  const tabsId = useId();

  const startFrom = (next: StoredEndpoint) => {
    setStored(next);
    setDraft(next.file.config);
    setInvalid(new Set());
    setGeneration((g) => g + 1);
  };

  useEffect(() => {
    let current = true;
    api.read(path).then(
      (read) => {
        if (!current) return;
        setStored(read);
        setDraft(read.file.config);
        setGeneration((g) => g + 1);
      },
      (error: unknown) => current && setFailed(error instanceof Error ? error.message : String(error)),
    );
    return () => {
      current = false;
    };
  }, [api, path]);

  const scheme = stored?.file.scheme;
  useEffect(() => {
    if (scheme === undefined) return undefined;
    let current = true;
    api.components().then(
      (components) => {
        if (!current) return;
        const found = components.items.find((c) => c.scheme === scheme);
        const ingress = { environment: components.environment, httpOrigin: components.httpOrigin };
        setDescribed(
          found
            ? { kind: 'read', settings: found.settings, httpPrefix: found.httpPrefix, ingress }
            : { kind: 'missing', why: `The node serves no component of the scheme ${scheme}.` },
        );
      },
      (error: unknown) => current && setDescribed({ kind: 'missing', why: `The node's components could not be read: ${error instanceof Error ? error.message : String(error)}` }),
    );
    return () => {
      current = false;
    };
  }, [api, scheme]);

  const unsaved = editing && stored !== null && !sameValue(present(draft), stored.file.config);

  useEffect(() => {
    if (!unsaved) return undefined;
    const ask = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', ask);
    return () => window.removeEventListener('beforeunload', ask);
  }, [unsaved]);

  useEffect(() => {
    if (!unsaved) return undefined;
    guardClose?.((close) => setDiscarding({ then: 'close its tab', close }));
    return () => guardClose?.(null);
  }, [guardClose, unsaved]);

  if (failed !== null) {
    return <Alert showIcon type="error" title={`${path} could not be read`} description={failed} />;
  }
  if (stored === null) {
    return <Spin aria-label={`Reading ${path}`} />;
  }

  const { file } = stored;
  const settings = described.kind === 'read' ? described.settings : null;
  const options = editing ? draft : file.config;
  const problems = settings === null ? [] : checkOptions(settings, present(options), file.direction);
  const problemOf = new Map<string, string>();
  for (const p of problems) {
    const option = optionOf(p.path);
    if (option !== null && !problemOf.has(option)) problemOf.set(option, p.message);
  }

  const change = (name: string, value: unknown) => {
    setCheck(null);
    setDraft((d) => ({ ...d, [name]: value }));
  };
  const markInvalid = (name: string, bad: boolean) =>
    setInvalid((set) => {
      if (set.has(name) === bad) return set;
      const next = new Set(set);
      if (bad) next.add(name);
      else next.delete(name);
      return next;
    });

  const leaveEditing = () => {
    setDiscarding(null);
    setSaveFailure(null);
    setCheck(null);
    setEditing(false);
    startFrom(stored);
  };
  const discard = () => {
    if (discarding?.then === 'close its tab') {
      setDiscarding(null);
      discarding.close();
    } else leaveEditing();
  };

  const save = async () => {
    setSaving(true);
    setSaveFailure(null);
    try {
      startFrom(await api.save(path, present(draft), stored.etag));
      notification.success({ key: path, title: `Endpoint ${file.id} saved`, duration: 8, role: 'status', closable: { 'aria-label': 'Dismiss save notification' } });
    } catch (error) {
      if (error instanceof Error && 'status' in error && error.status === 412) setConflict(true);
      else setSaveFailure(error instanceof Error ? error.message : String(error));
    } finally {
      setSaving(false);
    }
  };

  const reload = async () => {
    setConflict(false);
    setEditing(false);
    setCheck(null);
    try {
      startFrom(await api.read(path));
    } catch (error) {
      setFailed(error instanceof Error ? error.message : String(error));
    }
  };

  const shown = settings === null ? new Set<string>() : shownFields(settings, present(options));
  const fieldsOn = (tabId: string): SettingsField[] => {
    if (settings === null) return [];
    const fields = settings.fields.filter((f) => f.tab === tabId && f.directions.includes(file.direction) && shown.has(f.name));
    if (tabId !== 'general') return fields;
    // on an in endpoint the node sets flow and maxBodyBytes itself: no field describes them, and they are no stray key
    const named = new Set([...settings.fields.map((f) => f.name), ...(file.direction === 'in' ? ['flow', 'maxBodyBytes'] : [])]);
    return [
      ...fields,
      ...Object.keys(options)
        .filter((k) => !named.has(k) && options[k] !== undefined)
        .map(undescribed),
    ];
  };

  const canEdit = file.editable && settings !== null;
  let arrival: ReactNode;
  if (described.kind === 'read' && described.httpPrefix !== null && file.direction === 'in') {
    const { ingress } = described;
    const overlay = file.environments.find((env) => env.name === ingress.environment);
    const url = inboundAddress(described.httpPrefix, optionsIn(present(options), file.environments, ingress.environment).address, ingress.httpOrigin);
    if (url !== null) arrival = <ArrivalNote url={url} ingress={ingress} overlaid={overlay !== undefined && Object.hasOwn(overlay.config, 'address')} />;
  }
  const tabs = settings?.tabs ?? [];
  const activeTab = tabs.some((t) => t.id === tab) ? tab : (tabs[0]?.id ?? 'general');

  return (
    <Flex vertical gap="middle">
      <Flex align="center" wrap gap="small">
        <Typography.Title level={1} style={{ margin: 0 }}>
          {file.id}
        </Typography.Title>
        <Flex align="center" role="group" aria-label="Endpoint" gap="small">
          <Tag color="blue">{file.scheme}</Tag>
          <Tag>{file.direction}</Tag>
          {!file.editable && <Tag>Read-only</Tag>}
        </Flex>
        <Flex gap="small" wrap style={{ marginInlineStart: 'auto' }}>
          {editing ? (
            <>
              <Button type="text" onClick={() => (unsaved ? setDiscarding({ then: 'return to view mode' }) : leaveEditing())}>
                Cancel
              </Button>
              <Button icon={<CheckCircleOutlined aria-hidden />} onClick={() => setCheck(problems)}>
                Check
              </Button>
              <Button type="primary" icon={<SaveOutlined aria-hidden />} disabled={!unsaved || saving || invalid.size > 0} onClick={() => void save()}>
                Save
              </Button>
            </>
          ) : (
            canEdit && (
              <Button type="primary" icon={<EditOutlined aria-hidden />} onClick={() => setEditing(true)}>
                Edit
              </Button>
            )
          )}
        </Flex>
      </Flex>
      <Typography.Text type="secondary">{file.path}</Typography.Text>
      {file.description !== null && <Typography.Paragraph>{file.description}</Typography.Paragraph>}
      {described.kind === 'missing' && (
        <Alert
          type="warning"
          showIcon
          title="The settings form needs the component's description"
          description={
            <>
              <p>{described.why}</p>
              <p>The file's settings are shown as they are; Edit needs the description.</p>
            </>
          }
        />
      )}
      {file.environments.length > 0 && (
        <Alert
          type="info"
          showIcon
          title="Environments override some of these settings"
          description={
            <ul aria-label="Overrides">
              {file.environments.map(({ name, options: overridden }) => (
                <li key={name}>
                  <strong>{name}</strong>: {overridden.length > 0 ? overridden.join(', ') : 'nothing'}
                </li>
              ))}
            </ul>
          }
        />
      )}
      {check !== null && (
        <Alert
          showIcon
          type={check.length > 0 ? 'warning' : 'success'}
          role="status"
          title={check.length > 0 ? 'Check found issues' : 'Check passed'}
          closable={{ 'aria-label': 'Dismiss check', onClose: () => setCheck(null) }}
          description={
            <>
              {check.length > 0 && (
                <ul aria-label="Issues">
                  {check.map((p, i) => (
                    <li key={i}>{p.path === '' ? p.message : `${optionOf(p.path)} ${p.message}`}</li>
                  ))}
                </ul>
              )}
              <p>The node checks the file again when the package is deployed.</p>
            </>
          }
        />
      )}
      {saveFailure !== null && <Alert type="error" showIcon title="The file was not saved" description={saveFailure} />}
      {described.kind === 'reading' ? (
        <Spin aria-label="Reading the component's description" />
      ) : settings === null || !settings.described ? (
        <Form layout="vertical">
          <RawOptions
            key={generation}
            options={options}
            readOnly={!editing}
            onChange={(next) => {
              setCheck(null);
              setDraft(next);
            }}
            onInvalid={(bad) => markInvalid('', bad)}
          />
        </Form>
      ) : (
        <Tabs
          activeKey={activeTab}
          onChange={setTab}
          aria-label="Settings tabs"
          id={tabsId}
          items={tabs.map((t) => {
            const fields = fieldsOn(t.id);
            return {
              key: t.id,
              label: t.label,
              children:
                fields.length === 0 ? (
                  <p>No settings on this tab for an {file.direction} endpoint.</p>
                ) : (
                  <Form layout="vertical">
                    {fields.map((field) => (
                      <FieldControl
                        key={`${generation}-${field.name}`}
                        field={field}
                        value={options[field.name]}
                        readOnly={!editing}
                        onChange={(value) => change(field.name, value)}
                        onInvalid={(bad) => markInvalid(field.name, bad)}
                        problem={problemOf.get(field.name)}
                        {...(field.name === 'address' && arrival !== undefined ? { note: arrival } : {})}
                      />
                    ))}
                  </Form>
                ),
            };
          })}
        />
      )}
      <DiscardDialog isOpen={discarding !== null} name={file.id} then={discarding?.then ?? ''} onDiscard={discard} onKeepEditing={() => setDiscarding(null)} />
      <Modal open={conflict} title="The file changed on disk" okText="Reload" cancelText="Keep editing" onOk={() => void reload()} onCancel={() => setConflict(false)}>
        <p>
          <strong>{file.path}</strong> changed since it was opened, and nothing was saved. Reload shows the file as it is now and discards your changes.
        </p>
      </Modal>
    </Flex>
  );
}
