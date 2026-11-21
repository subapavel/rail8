import { ArrowDownOutlined, ArrowUpOutlined, DeleteOutlined, PlusCircleOutlined } from '@ant-design/icons';
import { Alert, Button, Flex, Form, Input, Select, Table, Tabs, Typography } from 'antd';
import { useState, type ReactNode } from 'react';
import { FieldControl } from '../FieldControl';
import { RawOptions } from '../RawOptions';
import { placeOf, type Op } from './edit';
import { addEntry, addRow, moveRow, removeRow, renameEntry, setField, setKeys } from './ops';
import { FLOW_PARTS, STEP_KEYS, stepTabFields } from './steps';
import type { FlowNode, FlowProblem, Path, StepField, StepForm, ValueField } from './types';
import { pointer } from './view';

/** What the panel may change while the flow is edited. */
export interface PanelEdit {
  /**
   * Told the operations a change makes, or how to build them (a refusal of either is shown), and the key of the field it
   * changes: changes of one field in a row are undone at once. Answers whether the change was made.
   */
  change: (ops: Op[] | (() => Op[]), key?: string | null) => boolean;
  /** Told whether the field of `key` holds text that cannot become a value, which cannot be saved. */
  invalid: (key: string, bad: boolean) => void;
  /** Moves the selected step up (-1) or down (1) in its list. */
  move: (by: -1 | 1) => void;
  /** Deletes the selected step. */
  remove: () => void;
  /** Whether the selected step can move up, and down, in its list. */
  up: boolean;
  down: boolean;
  /** The other lists of steps the selected step can be moved to the end of, with their names. */
  lists: { path: Path; label: string }[];
  /** Moves the selected step to the end of the list at `list`. */
  moveTo: (list: Path) => void;
  /** Renames the process `from` to `to`, and every call of it. */
  renameProcess: (from: string, to: string) => void;
  /** Deletes the process `name`. */
  deleteProcess: (name: string) => void;
  /** Asks for the name and the first step of a new process. */
  addProcess: () => void;
  /** Changes each time the text is set from outside the form (undo, save, reload): controls holding text of their own start again. */
  generation: number;
}

const shown = (value: unknown) => (value === undefined || value === null ? '' : typeof value === 'string' ? value : JSON.stringify(value));

const samePath = (a: Path, b: Path) => a.length === b.length && a.every((s, i) => b[i] === s);

/** The problems at `path`, as a list. */
function Problems({ problems }: { problems: FlowProblem[] }) {
  if (problems.length === 0) return null;
  return (
    <Flex vertical>
      {problems.map((p, i) => (
        <Typography.Text key={i} type={p.warning === true ? 'warning' : 'danger'}>
          {placeOf(p.path)}: {p.message}
        </Typography.Text>
      ))}
    </Flex>
  );
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/** The problems at `at` or inside it. */
const inside = (problems: FlowProblem[], at: Path) => problems.filter((p) => at.every((s, i) => p.path[i] === s));

/**
 * One value of a row of a table: a select for a choice or true or false, else a text input, which takes empty text as
 * a value when its field is required. Read-only it shows the value.
 */
function Cell({ field, value, label, problem, onChange }: { field: StepField; value: unknown; label: string; problem: boolean; onChange?: (value: unknown) => void }) {
  const status = problem ? 'error' : undefined;
  if (onChange === undefined || field.type === 'list' || field.type === 'map' || field.type === 'raw') return <>{shown(value)}</>;
  if (field.type === 'choice' || field.type === 'boolean') {
    const values = field.type === 'boolean' ? ['true', 'false'] : (field.choices ?? []);
    return (
      <Select
        aria-label={label}
        virtual={false}
        value={shown(value)}
        status={status}
        aria-invalid={problem}
        options={[
          { value: '', label: 'Not set' },
          ...(value !== undefined && value !== null && !values.includes(shown(value)) ? [{ value: shown(value), label: shown(value) }] : []),
          ...values.map((v) => ({ value: v, label: v })),
        ]}
        onChange={(chosen) => onChange(chosen === '' ? undefined : field.type === 'boolean' ? chosen === 'true' : chosen)}
      />
    );
  }
  return (
    <Input
      aria-label={label}
      value={shown(value)}
      status={status}
      aria-invalid={problem}
      onChange={({ target: { value: text } }) => onChange(text === '' && !field.required ? undefined : text)}
    />
  );
}

/** The new row of a list field: each required field of an item, empty. */
const newRow = (item: readonly StepField[]) => Object.fromEntries(item.filter((f) => f.required).map((f) => [f.name, '']));

/**
 * A list field as a table: one row per item, one column per field of an item. While the flow is edited a row's values
 * change in place, and a row is added at the end, moved up or down, and removed, but the last one.
 */
function ListTable({
  field,
  values,
  at,
  problems,
  edit,
}: {
  field: Extract<StepField, { type: 'list' }>;
  values: Record<string, unknown>;
  at: Path;
  problems: FlowProblem[];
  edit?: PanelEdit;
}) {
  const items = Array.isArray(values[field.name]) ? (values[field.name] as unknown[]) : [];
  const step = at.slice(0, -1);
  return (
    <Form.Item label={field.name}>
      <Typography.Text type="secondary">{field.help}</Typography.Text>
      <Table
        aria-label={field.name}
        size="small"
        pagination={false}
        dataSource={items.map((item, index) => ({ key: index, item }))}
        columns={[
          ...field.item.map((f) => ({
            title: f.name,
            key: f.name,
            render: (_: unknown, { item, key: i }: { item: unknown; key: number }) => {
              const row = isRecord(item) ? item : {};
              return (
                <Cell
                  field={f}
                  value={row[f.name]}
                  label={`${f.name} of row ${i + 1}`}
                  problem={inside(problems, [...at, i, f.name]).length > 0}
                  {...(edit === undefined || !isRecord(item)
                    ? {}
                    : { onChange: (next: unknown) => edit.change(setField([...at, i], row, f.name, next, f.required), pointer([...at, i, f.name])) })}
                />
              );
            },
          })),
          ...(edit === undefined
            ? []
            : [
                {
                  title: 'Row actions',
                  key: 'actions',
                  render: (_: unknown, { key: i }: { key: number }) => (
                    <Flex gap="small" wrap>
                      <Button
                        type="text"
                        size="small"
                        aria-label={`Move row ${i + 1} up`}
                        icon={<ArrowUpOutlined aria-hidden />}
                        disabled={i === 0}
                        onClick={() => edit.change(moveRow(at, i, i - 1))}
                      />
                      <Button
                        type="text"
                        size="small"
                        aria-label={`Move row ${i + 1} down`}
                        icon={<ArrowDownOutlined aria-hidden />}
                        disabled={i === items.length - 1}
                        onClick={() => edit.change(moveRow(at, i, i + 1))}
                      />
                      <Button
                        type="text"
                        size="small"
                        aria-label={`Remove row ${i + 1}`}
                        icon={<DeleteOutlined aria-hidden />}
                        disabled={items.length === 1}
                        onClick={() => edit.change(removeRow(at, i))}
                      />
                    </Flex>
                  ),
                },
              ]),
        ]}
      />
      {edit !== undefined && (
        <Button type="link" icon={<PlusCircleOutlined aria-hidden />} onClick={() => edit.change(addRow(step, values, field.name, newRow(field.item)))}>
          Add a row
        </Button>
      )}
      <Problems problems={inside(problems, at)} />
    </Form.Item>
  );
}

/**
 * The key of an entry of a map field, while the flow is edited: renamed in place when the field loses the focus or Enter
 * is pressed, and held as typed, unsaveable, while it is empty, taken or does not match the field's pattern.
 */
function EntryKey({ field, name, taken, at, edit }: { field: Extract<StepField, { type: 'map' }>; name: string; taken: string[]; at: Path; edit: PanelEdit }) {
  const [text, setText] = useState(name);
  const key = `${pointer([...at, name])}#key`;
  const wrong =
    text === ''
      ? 'is empty'
      : text !== name && taken.includes(text)
        ? 'is taken'
        : field.key.pattern !== undefined && !new RegExp(`^(?:${field.key.pattern})$`).test(text)
          ? `must match ${field.key.pattern}`
          : null;
  const commit = () => {
    if (wrong !== null) return;
    edit.invalid(key, false);
    edit.change(renameEntry(at, name, text));
  };
  return (
    <>
      <Input
        aria-label={`Value ${name}`}
        value={text}
        status={wrong === null ? undefined : 'error'}
        aria-invalid={wrong !== null}
        onChange={({ target: { value: next } }) => {
          setText(next);
          edit.invalid(key, next !== name);
        }}
        onBlur={commit}
        onKeyDown={(event) => {
          if (event.key === 'Enter') commit();
        }}
      />
      {wrong !== null && <Typography.Text type="danger">{wrong}</Typography.Text>}
    </>
  );
}

/**
 * A map field as a table: one row per entry, its key and its value. While the flow is edited an entry's value changes
 * in place, its key is renamed in place, and an entry is added at the end and removed, but the last one.
 */
function MapTable({
  field,
  values,
  at,
  problems,
  edit,
}: {
  field: Extract<StepField, { type: 'map' }>;
  values: Record<string, unknown>;
  at: Path;
  problems: FlowProblem[];
  edit?: PanelEdit;
}) {
  const value = values[field.name];
  const entries = isRecord(value) ? Object.entries(value) : [];
  const keys = entries.map(([k]) => k);
  return (
    <Form.Item label={field.name}>
      <Typography.Text type="secondary">{field.help}</Typography.Text>
      <Table
        aria-label={field.name}
        size="small"
        pagination={false}
        dataSource={entries.map(([name, value]) => ({ key: name, name, value }))}
        columns={[
          {
            title: 'Value',
            key: 'name',
            render: (_, row) => (edit === undefined ? row.name : <EntryKey key={edit.generation} field={field} name={row.name} taken={keys} at={at} edit={edit} />),
          },
          {
            title: field.value.name,
            key: 'value',
            render: (_, row) => (
              <Cell
                field={field.value}
                value={row.value}
                label={`${field.value.name} of ${row.name}`}
                problem={inside(problems, [...at, row.name]).length > 0}
                {...(edit === undefined
                  ? {}
                  : { onChange: (next: unknown) => edit.change(setField(at, value as Record<string, unknown>, row.name, next, true), pointer([...at, row.name])) })}
              />
            ),
          },
          ...(edit === undefined
            ? []
            : [
                {
                  title: 'Row actions',
                  key: 'actions',
                  render: (_: unknown, row: { name: string }) => (
                    <Button
                      type="text"
                      size="small"
                      aria-label={`Remove ${row.name}`}
                      icon={<DeleteOutlined aria-hidden />}
                      disabled={entries.length === 1}
                      onClick={() => edit.change(removeRow(at, row.name))}
                    />
                  ),
                },
              ]),
        ]}
      />
      {edit !== undefined && (
        <Button type="link" icon={<PlusCircleOutlined aria-hidden />} onClick={() => edit.change(addEntry(at.slice(0, -1), values, field.name, ''))}>
          Add a row
        </Button>
      )}
      <Problems problems={inside(problems, at)} />
    </Form.Item>
  );
}

/** The fields of a form, each with its value from `values` and its problem, and editable with `edit`; lists and maps are tables. */
function Fields({ fields, values, at, problems, edit }: { fields: StepField[]; values: Record<string, unknown>; at: Path; problems: FlowProblem[]; edit?: PanelEdit }) {
  return (
    <Form layout="vertical">
      {fields.map((f) => {
        const path = [...at, ...f.name.split('.')];
        const value = f.name.split('.').reduce<unknown>((v, k) => (typeof v === 'object' && v !== null ? (v as Record<string, unknown>)[k] : undefined), values);
        if ((f.type === 'list' || f.type === 'map') && (value === undefined || value === null)) {
          return (
            <Form.Item key={f.name} label={f.name}>
              <Typography.Text type="secondary">Not set. {f.help}</Typography.Text>
              {edit !== undefined && (
                <Button
                  type="link"

                  icon={<PlusCircleOutlined aria-hidden />}
                  onClick={() => edit.change(f.type === 'list' ? addRow(at, values, f.name, newRow(f.item)) : addEntry(at, values, f.name, ''))}
                >
                  Add a row
                </Button>
              )}
            </Form.Item>
          );
        }
        const own = edit === undefined ? {} : { edit };
        if (f.type === 'list') return <ListTable key={f.name} field={f} values={values} at={path} problems={problems} {...own} />;
        if (f.type === 'map') return <MapTable key={f.name} field={f} values={values} at={path} problems={problems} {...own} />;
        const problem = problems.find((p) => samePath(p.path, path));
        const key = pointer(path);
        return (
          <FieldControl
            key={`${edit?.generation ?? 0}-${f.name}`}
            field={f as ValueField}
            value={value}
            readOnly={edit === undefined}
            onChange={(next) => edit?.change(setField(at, values, f.name, next, f.required), key)}
            onInvalid={(bad) => edit?.invalid(key, bad)}
            {...(problem === undefined ? {} : { problem: problem.message })}
          />
        );
      })}
    </Form>
  );
}

/** The name a step has when it is given none: the process a call calls, else its type. */
const defaultNameOf = (values: Record<string, unknown>) => String(values.type === 'process' && typeof values.process === 'string' ? values.process : values.type);

/** A step's form: its own settings, and the "Step" tab of the platform's keys. A rename, while it is edited, warns. */
function StepTabs({ node, form, problems, edit }: { node: FlowNode; form: StepForm; problems: FlowProblem[]; edit?: PanelEdit }) {
  const [tab, setTab] = useState('settings');
  const [named] = useState(node.label);
  const own = Object.fromEntries(Object.entries(form.values).filter(([k]) => !STEP_KEYS.has(k)));
  const described = new Set(form.settings.fields.map((f) => f.name));
  const undescribed = Object.fromEntries(Object.entries(own).filter(([k]) => !described.has(k)));
  return (
    <Tabs
      activeKey={tab}
      onChange={setTab}
      aria-label="Step settings"
      items={[
        {
          key: 'settings',
          label: 'Settings',
          children: (
            <Flex vertical gap="middle">
              {form.missing !== undefined && <Alert type="info" showIcon title={form.missing} />}
              {form.settings.described && (
                <Fields fields={form.settings.fields} values={form.values} at={form.path} problems={problems} {...(edit === undefined ? {} : { edit })} />
              )}
              {(!form.settings.described || Object.keys(undescribed).length > 0) && (
                <RawOptions
                  key={`${node.id}-${edit?.generation ?? 0}`}
                  options={form.settings.described ? undescribed : own}
                  readOnly={edit === undefined}
                  onChange={(next) => edit?.change(setKeys(form.path, form.settings.described ? undescribed : own, next), `${pointer(form.path)}#raw`)}
                  onInvalid={(bad) => edit?.invalid(`${pointer(form.path)}#raw`, bad)}
                />
              )}
            </Flex>
          ),
        },
        {
          key: 'step',
          label: 'Step',
          children: (
            <Flex vertical gap="middle">
              {edit !== undefined && node.label !== named && (
                <Alert type="warning" showIcon title={`Renamed from ${named}: the message history keeps ${named} on the rows written before.`} />
              )}
              <Fields
                fields={stepTabFields(String(form.values.type), defaultNameOf(form.values))}
                values={form.values}
                at={form.path}
                problems={problems}
                {...(edit === undefined ? {} : { edit })}
              />
            </Flex>
          ),
        },
      ]}
    />
  );
}

/**
 * The flow's own settings as a form, its keys that no field describes in a raw editor, and the problems of the flow that
 * no field shows. With `edit` they are editable, and a process can be added.
 */
function FlowSettings({ form, problems, edit }: { form: StepForm; problems: FlowProblem[]; edit?: PanelEdit }) {
  const described = new Set(form.settings.fields.map((f) => f.name.split('.')[0]!));
  const undescribed = Object.fromEntries(Object.entries(form.values).filter(([k]) => !described.has(k) && !FLOW_PARTS.has(k)));
  const fields = new Set(form.settings.fields.map((f) => pointer(f.name.split('.'))));
  const rest = problems.filter((p) => p.path.length === 0 || (p.path[0] !== 'steps' && p.path[0] !== 'processes' && !fields.has(pointer(p.path))));
  return (
    <Flex vertical gap="middle">
      <Fields fields={form.settings.fields} values={form.values} at={[]} problems={problems} {...(edit === undefined ? {} : { edit })} />
      {Object.keys(undescribed).length > 0 && (
        <RawOptions
          key={`flow-${edit?.generation ?? 0}`}
          options={undescribed}
          readOnly={edit === undefined}
          onChange={(next) => edit?.change(setKeys([], undescribed, next), '#raw')}
          onInvalid={(bad) => edit?.invalid('#raw', bad)}
        />
      )}
      <Problems problems={rest} />
      {edit !== undefined && (
        <div>
          <Button size="small" icon={<PlusCircleOutlined aria-hidden />} onClick={edit.addProcess}>
            Add a process
          </Button>
        </div>
      )}
    </Flex>
  );
}

/** A process's lane while the flow is edited: its name, which renames it and every call of it, and Delete while no step calls it. */
function LaneEdit({ name, calls, edit }: { name: string; calls: FlowNode[]; edit: PanelEdit }) {
  const [text, setText] = useState(name);
  const unnamed = calls.filter((c) => c.defaultName).length;
  return (
    <Form layout="vertical">
      <Form.Item label="Name" htmlFor={`lane-${name}`}>
        <Flex gap="small" wrap>
          <div style={{ flex: 1 }}>
            <Input aria-label="Name" id={`lane-${name}`} value={text} onChange={({ target: { value: next } }) => setText(next)} />
          </div>
          <Button size="small" disabled={text === name || text === ''} onClick={() => edit.renameProcess(name, text)}>
            Rename
          </Button>
        </Flex>
        {unnamed > 0 && (
          <Typography.Text type="warning">
            {unnamed === 1 ? 'A call' : `${unnamed} calls`} without a name {unnamed === 1 ? 'is' : 'are'} named after the process: a rename changes the names its steps run under,
            and the message history keeps {name} on the rows written before.
          </Typography.Text>
        )}
      </Form.Item>
      <Flex align="center" gap="small" wrap>
        <Button danger size="small" icon={<DeleteOutlined aria-hidden />} disabled={calls.length > 0} onClick={() => edit.deleteProcess(name)}>
          Delete process
        </Button>
        {calls.length > 0 && (
          <Typography.Text type="secondary">
            {calls.length === 1 ? 'A step calls' : `${calls.length} steps call`} it: {calls.map((c) => placeOf(c.path)).join(', ')}.
          </Typography.Text>
        )}
      </Flex>
    </Form>
  );
}

export interface FlowPanelProps {
  /** The flow's value as the file holds it. */
  flow: Record<string, unknown>;
  /** The form of the flow's own settings. */
  settings?: StepForm;
  /** The node selected, or null for the flow's own settings. */
  node: FlowNode | null;
  /** The flow a `to` of the selected node calls, when it calls one. */
  calls?: FlowNode;
  /** The steps that call the process of the selected lane, when a lane is selected. */
  laneCalls?: FlowNode[];
  problems: FlowProblem[];
  /** Shows the endpoint file at this path in the component's form, read-only. */
  endpointForm: (path: string) => ReactNode;
  openEndpoint: (path: string) => void;
  openFlow: (path: string) => void;
  /** What may be changed while the flow is edited; without it the panel only shows. */
  edit?: PanelEdit;
}

/**
 * What the drawer beside the canvas shows of the selection: a step's form with its "Step" tab, an endpoint in its
 * component's form, a link to a flow called, a process's lane, or, when nothing is selected or the arrival is, the flow's
 * own settings. While the flow is edited, a step that is not locked can be moved within its list or to another, and
 * deleted, and its fields changed; a process renamed, deleted while no step calls it, and added; and the flow's own
 * settings changed.
 */
export function FlowPanel({ flow, settings, node, calls, laneCalls, problems, endpointForm, openEndpoint, openFlow, edit }: FlowPanelProps) {
  const own = edit === undefined ? {} : { edit };
  if (node === null) {
    return (
      <Flex vertical gap="middle">
        <Typography.Title level={2}>Flow {shown(flow.id)}</Typography.Title>
        {settings !== undefined && <FlowSettings key={edit?.generation ?? 0} form={settings} problems={problems} {...own} />}
      </Flex>
    );
  }
  const at = node.path;
  const mine = problems.filter((p) => at.every((s, i) => p.path[i] === s));
  const opens = node.opens;
  const flowLink = calls?.opens !== undefined && 'flow' in calls.opens ? calls.opens.flow : opens !== undefined && 'flow' in opens ? opens.flow : undefined;
  const editable = edit !== undefined && node.kind === 'step' && node.locked === undefined ? edit : undefined;
  return (
    <Flex vertical gap="middle">
      <div>
        <Typography.Title level={2}>{node.kind === 'lane' ? `Process ${node.label}` : node.label}</Typography.Title>
        <Typography.Text type="secondary">
          {node.type ?? node.kind}
          {node.runs > 0 && ` · runs as ${node.runsAs.join(', ')}${node.runs > node.runsAs.length ? ` and ${node.runs - node.runsAs.length} more` : ''}`}
        </Typography.Text>
      </div>
      {editable !== undefined && (
        <Flex gap="small" wrap>
          <Button size="small" icon={<ArrowUpOutlined aria-hidden />} disabled={!editable.up} onClick={() => editable.move(-1)}>
            Move up
          </Button>
          <Button size="small" icon={<ArrowDownOutlined aria-hidden />} disabled={!editable.down} onClick={() => editable.move(1)}>
            Move down
          </Button>
          <Button danger size="small" icon={<DeleteOutlined aria-hidden />} onClick={editable.remove}>
            Delete
          </Button>
          {editable.lists.length > 0 && (
            <Select
              aria-label="Move to the end of"
              placeholder="Move to…"
              value={undefined}
              virtual={false}
              options={editable.lists.map((l) => ({ value: pointer(l.path), label: l.label }))}
              onChange={(chosen) => {
                const list = editable.lists.find((l) => pointer(l.path) === chosen);
                if (list !== undefined) editable.moveTo(list.path);
              }}
            />
          )}
        </Flex>
      )}
      {edit !== undefined && node.kind === 'lane' && <LaneEdit key={`${node.id}-${edit.generation}`} name={node.label} calls={laneCalls ?? []} edit={edit} />}
      {node.locked !== undefined && <Alert type="info" showIcon title={node.locked} />}
      {node.kind === 'lane' && node.runs === 0 && <Typography.Text>No step of this flow calls the process.</Typography.Text>}
      {node.form === undefined && mine.length > 0 && <Problems problems={mine} />}
      {node.form !== undefined && <StepTabs key={node.id} node={node} form={node.form} problems={mine} {...(editable === undefined ? {} : { edit: editable })} />}
      {node.form !== undefined && mine.some((p) => p.path.length <= at.length) && <Problems problems={mine.filter((p) => p.path.length <= at.length)} />}
      {flowLink !== undefined && (
        <div>
          <Button onClick={() => openFlow(flowLink)}>Open flow {calls?.label ?? node.label}</Button>
        </div>
      )}
      {opens !== undefined && 'endpoint' in opens && (
        <Flex vertical gap="middle">
          <div>
            <Button onClick={() => openEndpoint(opens.endpoint)}>Open endpoint</Button>
          </div>
          {endpointForm(opens.endpoint)}
        </Flex>
      )}
      {node.kind === 'from' && settings !== undefined && (
        <div>
          <Typography.Title level={3}>Flow {shown(flow.id)}</Typography.Title>
          <FlowSettings key={edit?.generation ?? 0} form={settings} problems={problems} {...own} />
        </div>
      )}
    </Flex>
  );
}
