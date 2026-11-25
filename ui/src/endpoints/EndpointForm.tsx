import { CheckCircleOutlined, EditOutlined, SaveOutlined } from '@ant-design/icons';
import { Alert, App, Button, Flex, Form, Modal, Tabs, Tag, Typography } from 'antd';
import { useEffect, useId, useState, type ReactNode } from 'react';
import { DiscardDialog } from '../DiscardDialog';
import { msg, say, useText, type Label, type Message } from '../language';
import { ReadSpinner } from '../ReadSpinner';
import type { UnsavedChanges } from '../screens';
import { useWorkingFile } from '../workingFiles';
import { FieldControl } from './FieldControl';
import { RawOptions } from './RawOptions';
import type { EndpointsApi, StoredEndpoint } from './api';
import { inboundAddress, optionsIn, type NodeIngress } from './inbound';
import { sameValue } from './same-value';
import { checkOptions, shownFields, type Options, type Problem, type SettingsDescription, type SettingsField } from './settings';

export interface EndpointFormProps {
  api: EndpointsApi;
  /** The endpoint file's path in the working folder. */
  path: string;
  /** Given what saves the form while it has unsaved changes, so that its tab can save them before it closes, and null when it has none. */
  guardClose?: (changes: UnsavedChanges | null) => void;
}

/**
 * The component's description, while it is read, once read with its HTTP prefix and where the node takes requests, or
 * why it could not be.
 */
type Described = { kind: 'reading' } | { kind: 'read'; settings: SettingsDescription; httpPrefix: string | null; ingress: NodeIngress } | { kind: 'missing'; why: Message };

/**
 * Under the address of an in endpoint: a preview of the URL senders call, or of the path on the node's HTTP port when
 * the node names no origin, built from the options shown with the node environment's overlay laid over them.
 */
function ArrivalNote({ url, ingress, overlaid }: { url: string; ingress: NodeIngress; overlaid: boolean }) {
  const { t, rich } = useText();
  const strong = (chunks: ReactNode[]) => <strong>{chunks}</strong>;
  return (
    <>
      <Tag>{t('endpoints.arrival.preview')}</Tag> {rich(ingress.httpOrigin === null ? 'endpoints.arrival.path' : 'endpoints.arrival.senders', { url, strong })}{' '}
      {t(overlaid ? 'endpoints.arrival.builtOverlaid' : 'endpoints.arrival.built', { environment: ingress.environment })}
    </>
  );
}

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
  return { name, type: 'raw', help: msg('endpoints.form.undescribed'), tab: 'general', required: false, directions: ['in', 'out'] };
}

/**
 * An endpoint's settings form, drawn from its component's settings description: the endpoint's id, scheme, direction
 * and file; the environments that override options; then the description's tabs, each with the fields it holds that
 * serve the endpoint's direction and are shown, and the problems the check finds in them. It opens read-only; Edit, when
 * the file may be edited and the description could be read, then Cancel, Check and Save. Save writes only `config`,
 * sending the version it read, and a file changed on disk since opens a dialog. Cancel with unsaved changes asks
 * first; while there are some, `guardClose` is given the form's save, and the page asks before it unloads. Without the
 * description the file's options are shown as JSON, read-only, with the reason.
 */
export function EndpointForm({ api, path, guardClose }: EndpointFormProps) {
  const texts = useText();
  const { t, rich } = texts;
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
  const [saveFailure, setSaveFailure] = useState<Label | null>(null);
  const [conflict, setConflict] = useState(false);
  const { notification } = App.useApp();
  const [discarding, setDiscarding] = useState(false);
  // while a reload reads the file, Edit waits, so a draft always starts from the version it saves over
  const [reloading, setReloading] = useState(false);
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
            : { kind: 'missing', why: msg('endpoints.form.noComponent', { scheme }) },
        );
      },
      (error: unknown) => current && setDescribed({ kind: 'missing', why: msg('endpoints.form.componentsUnread', { reason: error instanceof Error ? error.message : String(error) }) }),
    );
    return () => {
      current = false;
    };
  }, [api, scheme]);

  // a control that holds text that is no value yet holds an unsaved change too
  const unsaved = editing && stored !== null && (invalid.size > 0 || !sameValue(present(draft), stored.file.config));

  useEffect(() => {
    if (!unsaved) return undefined;
    const ask = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', ask);
    return () => window.removeEventListener('beforeunload', ask);
  }, [unsaved]);

  /** Saves the draft: true once it is saved; false when it is not, and the form says why. */
  const save = async (): Promise<boolean> => {
    if (stored === null) return false;
    setSaveFailure(null);
    if (invalid.size > 0) {
      setSaveFailure(msg('endpoints.form.invalid'));
      return false;
    }
    setSaving(true);
    try {
      startFrom(await api.save(path, present(draft), stored.etag));
      notification.success({ key: path, title: t('endpoints.form.saved', { id: stored.file.id }), duration: 8, role: 'status', closable: { 'aria-label': t('endpoints.form.saved.dismiss') } });
      return true;
    } catch (error) {
      if (error instanceof Error && 'status' in error && error.status === 412) setConflict(true);
      else setSaveFailure(error instanceof Error ? error.message : String(error));
      return false;
    } finally {
      setSaving(false);
    }
  };

  // the save handed over is the one of the latest render, which knows the draft as it is now
  useEffect(() => {
    if (!unsaved) return undefined;
    guardClose?.({ save });
    return () => guardClose?.(null);
  });

  const reload = async () => {
    setConflict(false);
    setEditing(false);
    setCheck(null);
    setReloading(true);
    try {
      startFrom(await api.read(path));
    } catch (error) {
      setFailed(error instanceof Error ? error.message : String(error));
    } finally {
      setReloading(false);
    }
  };

  useWorkingFile(path, () => unsaved, () => void reload());

  if (failed !== null) {
    return <Alert showIcon type="error" title={t('endpoints.form.unread', { path })} description={failed} />;
  }
  if (stored === null) {
    return <ReadSpinner label={t('endpoints.form.reading', { path })} />;
  }

  const { file } = stored;
  const settings = described.kind === 'read' ? described.settings : null;
  const options = editing ? draft : file.config;
  const problems = settings === null ? [] : checkOptions(texts, settings, present(options), file.direction);
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
    setDiscarding(false);
    setSaveFailure(null);
    setCheck(null);
    setEditing(false);
    startFrom(stored);
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
  const activeTab = tabs.some((one) => one.id === tab) ? tab : (tabs[0]?.id ?? 'general');

  return (
    <Flex vertical gap="middle">
      <Flex align="center" wrap gap="small">
        <Typography.Title level={1} style={{ margin: 0 }}>
          {file.id}
        </Typography.Title>
        <Flex align="center" role="group" aria-label={t('endpoints.list.endpoint')} gap="small">
          <Tag color="blue">{file.scheme}</Tag>
          <Tag>{file.direction}</Tag>
          {!file.editable && <Tag>{t('endpoints.readOnly')}</Tag>}
        </Flex>
        <Flex gap="small" wrap style={{ marginInlineStart: 'auto' }}>
          {editing ? (
            <>
              <Button type="text" onClick={() => (unsaved ? setDiscarding(true) : leaveEditing())}>
                {t('frame.cancel')}
              </Button>
              <Button icon={<CheckCircleOutlined aria-hidden />} onClick={() => setCheck(problems)}>
                {t('endpoints.form.check')}
              </Button>
              <Button type="primary" icon={<SaveOutlined aria-hidden />} disabled={!unsaved || saving || invalid.size > 0} onClick={() => void save()}>
                {t('endpoints.form.save')}
              </Button>
            </>
          ) : (
            canEdit && (
              <Button type="primary" icon={<EditOutlined aria-hidden />} disabled={reloading} onClick={() => setEditing(true)}>
                {t('endpoints.form.edit')}
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
          title={t('endpoints.form.noDescription')}
          description={
            <>
              <p>{say(texts, described.why)}</p>
              <p>{t('endpoints.form.noDescription.hint')}</p>
            </>
          }
        />
      )}
      {file.environments.length > 0 && (
        <Alert
          type="info"
          showIcon
          title={t('endpoints.form.overrides')}
          description={
            <ul aria-label={t('endpoints.form.overrides.list')}>
              {file.environments.map(({ name, options: overridden }) => (
                <li key={name}>
                  <strong>{name}</strong>: {overridden.length > 0 ? overridden.join(', ') : t('endpoints.form.overrides.nothing')}
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
          title={t(check.length > 0 ? 'endpoints.form.check.issues' : 'endpoints.form.check.passed')}
          closable={{ 'aria-label': t('endpoints.form.check.dismiss'), onClose: () => setCheck(null) }}
          description={
            <>
              {check.length > 0 && (
                <ul aria-label={t('endpoints.form.check.list')}>
                  {check.map((p, i) => (
                    <li key={i}>{p.path === '' ? p.message : t('endpoints.form.check.issue', { option: optionOf(p.path), problem: p.message })}</li>
                  ))}
                </ul>
              )}
              <p>{t('endpoints.form.check.again')}</p>
            </>
          }
        />
      )}
      {saveFailure !== null && <Alert type="error" showIcon title={t('endpoints.form.notSaved')} description={say(texts, saveFailure)} />}
      {described.kind === 'reading' ? (
        <ReadSpinner label={t('endpoints.form.describing')} />
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
          aria-label={t('endpoints.form.tabs')}
          id={tabsId}
          items={tabs.map((one) => {
            const fields = fieldsOn(one.id);
            return {
              key: one.id,
              label: one.label,
              children:
                fields.length === 0 ? (
                  <p>{t(file.direction === 'in' ? 'endpoints.form.noFields.in' : 'endpoints.form.noFields.out')}</p>
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
      <DiscardDialog isOpen={discarding} name={file.id} onDiscard={leaveEditing} onKeepEditing={() => setDiscarding(false)} />
      <Modal open={conflict} title={t('endpoints.conflict')} okText={t('endpoints.conflict.reload')} cancelText={t('frame.discard.keep')} onOk={() => void reload()} onCancel={() => setConflict(false)}>
        <p>{rich('endpoints.conflict.hint', { path: file.path, strong: (chunks) => <strong>{chunks}</strong> })}</p>
      </Modal>
    </Flex>
  );
}
