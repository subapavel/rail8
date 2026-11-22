import { CaretRightOutlined, CheckCircleOutlined, DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined, SaveOutlined, SearchOutlined, UndoOutlined } from '@ant-design/icons';
import { ReactFlowProvider } from '@xyflow/react';
import { Alert, App, Button, Collapse, Flex, Form, Input, Menu, Modal, Select, Spin, Tag, Tooltip, Typography } from 'antd';
import { useCallback, useEffect, useId, useMemo, useState, type KeyboardEvent, type ReactNode } from 'react';
import { DiscardDialog } from '../../DiscardDialog';
import { ReadSpinner } from '../../ReadSpinner';
import type { CloseGuard } from '../EndpointForm';
import { FlowCanvas } from './FlowCanvas';
import { FlowInspector, type InspectorView } from './FlowInspector';
import { FlowPanel, type PanelEdit } from './FlowPanel';
import { SimulationDialog } from './SimulationDialog';
import { FlowsError, type FlowsApi, type StoredFlow } from './api';
import { canvasGraph } from './canvasGraph';
import { edit, EditRefused, follow, placeOf, type Op } from './edit';
import { readFlow } from './flowYaml';
import { addProcess, addStep, callable, callOf, callsOf, deleteProcess, deleteStep, moveStep, moveToList, renameProcess, stepListsOf } from './ops';
import type { SimulationRange } from './simulationRange';
import { BUILT_IN } from './steps';
import type { FlowIngress, FlowNode, FlowProblem, Path, ServedSteps } from './types';
import { pointer, view } from './view';
import './flow-editor.css';

/** The most texts undo goes back through. */
const MAX_UNDO = 100;

export interface FlowPictureProps {
  api: FlowsApi;
  /** The flow file's path in the working folder. */
  path: string;
  /** Shows the endpoint file at this path in the component's form, read-only. */
  endpointForm: (path: string) => ReactNode;
  openEndpoint: (path: string) => void;
  openFlow: (path: string) => void;
  /** Creates the endpoint file at this path with this text, and fails when it exists. */
  createEndpoint: (path: string, text: string) => Promise<unknown>;
  /** Given the question to ask before the tab closes while it has unsaved changes, and null when it has none. */
  guardClose?: (guard: CloseGuard | null) => void;
}

type Result = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; stored: StoredFlow };

/**
 * The working copy while the flow is edited: its text, the texts before its changes (the last at the end), and the key
 * of the field the last change changed.
 */
interface Work {
  text: string;
  undo: { text: string; movedStep: boolean }[];
  key: string | null;
}

type Discarding = { then: 'return to view mode' } | { then: 'close its tab'; close: () => void };

const messageOf = (error: unknown) => (error instanceof Error ? error.message : String(error));

/**
 * A flow file as a picture, with properties of the flow or the selected node below the canvas.
 * A file that cannot be drawn shows the problems that prevented the diagram from loading.
 *
 * It opens read-only; Edit, when the file may be edited and the flow is drawn, then Undo, Cancel, Check and Save. While
 * it is edited, the button on an edge adds a step there, or a call of another flow. The properties panel moves the step chosen
 * within its list or to another, deletes it and changes its fields, renames and deletes the process of the lane chosen,
 * adds a process and changes the flow's own settings; and the canvas, while it has the focus, takes Delete, the up and
 * down arrows and Ctrl+Z (Cmd+Z). A call of a flow whose address no `direct` out endpoint of the package names creates
 * that endpoint file first, as a save of its own that Cancel leaves in place.
 * Each change is a new text from `edit`; undo goes back through the last 100. The selection follows its step through
 * every change, and is kept through an undo while its path is in the view. The problems are found again after each
 * change and never block Save, which a field holding text that cannot become a value does. Save sends the text with the
 * version it read; a file changed on disk since opens a dialog. Cancel, or closing the tab, with unsaved changes asks
 * first, and the page asks before it unloads.
 */
export function FlowPicture({ api, path, endpointForm, openEndpoint, openFlow, createEndpoint, guardClose }: FlowPictureProps) {
  const [result, setResult] = useState<Result>({ kind: 'reading' });
  const [steps, setSteps] = useState<ServedSteps>();
  const [ingress, setIngress] = useState<FlowIngress>();
  const [asked, setAsked] = useState(0);
  const [selected, setSelected] = useState<Path | null>(null);
  const [searchTarget, setSearchTarget] = useState<{ id: string; sequence: number }>();
  const [inspector, setInspector] = useState<InspectorView>('minimized');
  const [work, setWork] = useState<Work | null>(null);
  const [layoutRevision, setLayoutRevision] = useState(0);
  const [range, setRange] = useState<SimulationRange | null>(null);
  const [simulationOpen, setSimulationOpen] = useState(false);
  // a new generation remounts the controls that hold text of their own, so that text goes with the text it belonged to
  const [generation, setGeneration] = useState(0);
  const [invalid, setInvalid] = useState<ReadonlySet<string>>(new Set());
  // The invalid controls belong to the selected node's form.
  const [formShown, setFormShown] = useState<string | null>(null);
  const [refused, setRefused] = useState<string | null>(null);
  const [check, setCheck] = useState<FlowProblem[] | null>(null);
  const [adding, setAdding] = useState<Path | null>(null);
  // the endpoint file a call of a flow is creating, and why that failed
  const [calling, setCalling] = useState<string | null>(null);
  const [callFailure, setCallFailure] = useState<string | null>(null);
  // the new process asked for, and why it was refused
  const [newProcess, setNewProcess] = useState<{ name: string; type: string; refused?: string } | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveFailure, setSaveFailure] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const { notification } = App.useApp();
  const [showProblems, setShowProblems] = useState(false);
  const [discarding, setDiscarding] = useState<Discarding | null>(null);
  const ids = useId();
  const refresh = useCallback(() => setAsked((n) => n + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    const { signal } = controller;
    api.read(path, signal).then(
      (stored) => !signal.aborted && setResult({ kind: 'read', stored }),
      (error: unknown) => !signal.aborted && setResult({ kind: 'failed', message: messageOf(error) }),
    );
    api.steps(signal).then(
      (types) => !signal.aborted && setSteps(types),
      (error: unknown) => !signal.aborted && setSteps({ missing: messageOf(error) }),
    );
    api.ingress(signal).then(
      (address) => !signal.aborted && setIngress(address),
      () => !signal.aborted && setIngress(undefined),
    );
    return () => controller.abort();
  }, [api, path, asked]);

  const stored = result.kind === 'read' ? result.stored : null;
  const text = work?.text ?? stored?.file.text ?? '';
  const drawn = useMemo(() => {
    if (result.kind !== 'read') return null;
    const read = readFlow(text);
    const context = { ...result.stored.file.context, steps, ingress };
    return { view: view(text, context), value: read.kind === 'flow' ? read.value : {} };
  }, [result, text, steps, ingress]);
  const searchable = useMemo(() => {
    if (!drawn?.view.readable) return [];
    return canvasGraph(drawn.view, false, false).nodes.flatMap((item) =>
      item.original && item.original.kind !== 'end' && item.original.kind !== 'group' ? [{ value: item.id, label: item.label, path: item.original.path }] : [],
    );
  }, [drawn]);

  const unsaved = work !== null && stored !== null && work.text !== stored.file.text;

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

  if (result.kind === 'reading') {
    return <ReadSpinner label="Reading the flow file" />;
  }
  if (result.kind === 'failed' || drawn === null) {
    return <Alert type="error" showIcon title="The flow file could not be read" description={result.kind === 'failed' ? result.message : ''} />;
  }

  const { view: picture, value } = drawn;
  const file = result.stored.file;
  const errors = picture.problems.filter((p) => p.warning !== true).length;
  const warnings = picture.problems.length - errors;
  // the selection is kept while its path is in the view
  const node: FlowNode | null = selected === null ? null : (picture.nodes.find((n) => n.id === pointer(selected)) ?? null);
  if (selected !== null && node === null) setSelected(null);
  // the form of another node, or of the selected step at its new place, starts from the text: what the controls before
  // it held that could not become a value is gone with them
  if ((node?.id ?? null) !== formShown) {
    setFormShown(node?.id ?? null);
    if (invalid.size > 0) setInvalid(new Set());
  }
  const calls = node === null ? undefined : picture.nodes.find((n) => n.id === `${node.id}#calls`);
  const canEdit = file.editable && picture.readable && picture.readOnly === undefined;
  const title = typeof value.id === 'string' ? value.id : path.split('/').at(-1) ?? path;
  const inspect = (at: Path | null) => {
    setSelected(at);
    setInspector((mode) => mode === 'minimized' ? 'split' : mode);
  };

  /**
   * Applies `ops`, or the operations `ops` builds, to the working copy; a change of the field `key` right after another
   * of it is undone with it.
   */
  const change = (build: Op[] | (() => Op[]), key: string | null = null): boolean => {
    if (work === null) return false;
    let next: string;
    let ops: Op[];
    try {
      ops = typeof build === 'function' ? build() : build;
      if (ops.length === 0) return false;
      next = edit(work.text, ...ops);
    } catch (failed) {
      if (!(failed instanceof EditRefused)) throw failed;
      setRefused(failed.message);
      return false;
    }
    setRefused(null);
    setCheck(null);
    if (selected !== null) setSelected(follow(selected, ops));
    if (next !== work.text) {
      const movedStep = ops.some(op => op.op === 'move' && picture.nodes.some(n => n.kind === 'step' && n.id === pointer(op.from)));
      const undo = key !== null && work.key === key ? work.undo : [...work.undo, { text: work.text, movedStep }].slice(-MAX_UNDO);
      if (movedStep) setLayoutRevision(r => r + 1);
      setWork({ text: next, undo, key });
    }
    return true;
  };

  const startAgain = () => {
    setGeneration((g) => g + 1);
    setInvalid(new Set());
    setRefused(null);
    setCheck(null);
  };

  const undo = () => {
    const last = work?.undo.at(-1);
    if (work === null || last === undefined) return;
    if (last.movedStep) setLayoutRevision(r => r + 1);
    setWork({ text: last.text, undo: work.undo.slice(0, -1), key: null });
    startAgain();
  };

  // the step chosen, where it stands in its list, and how long that list is
  const step = node !== null && node.kind === 'step' && typeof node.path.at(-1) === 'number' ? node : null;
  const index = step === null ? -1 : (step.path.at(-1) as number);
  const length =
    step === null ? 0 : picture.nodes.filter((n) => n.kind === 'step' && n.path.length === step.path.length && n.path.slice(0, -1).every((s, i) => step.path[i] === s)).length;
  const move = (by: -1 | 1) => {
    if (step !== null) change(moveStep(step.path, [...step.path.slice(0, -1), index + by]));
  };
  const removeStep = (target: FlowNode | null) => {
    if (target?.kind === 'step' && target.locked === undefined) change(deleteStep(target.path));
  };
  const remove = () => removeStep(step);
  const add = (type: string) => {
    const at = adding;
    setAdding(null);
    if (at !== null && change(addStep(picture, at, type))) inspect(at);
  };
  // a call of the flow `called` at `adding`: its endpoint file is created first when the package has none, and the
  // context read again so that the picture knows it
  const addCall = async (called: { id: string; address?: string }) => {
    const at = adding;
    if (at === null) return;
    const call = callOf(file.context, path, called);
    if (call.create !== undefined) {
      const created = call.create.path;
      setCalling(created);
      setCallFailure(null);
      try {
        await createEndpoint(created, call.create.text);
      } catch (error) {
        setCallFailure(`${created} was not created: ${messageOf(error)}`);
        setCalling(null);
        return;
      }
      try {
        const fresh = await api.read(path);
        setResult({ ...result, stored: { ...result.stored, file: { ...result.stored.file, context: fresh.file.context } } });
      } catch (error) {
        setCallFailure(`${created} was created, but the flow's endpoints could not be read again: ${messageOf(error)}. Refresh after Save shows them.`);
        setCalling(null);
        return;
      }
      setCalling(null);
    }
    setAdding(null);
    if (change(addStep(picture, at, 'to', { endpoint: call.endpoint }))) inspect(at);
  };
  const flowsCalled = callable(file.context, path);
  const lane = node !== null && node.kind === 'lane' ? node : null;
  const lists = step === null ? [] : stepListsOf(picture).filter((l) => pointer(l) !== pointer(step.path.slice(0, -1)));
  const createProcess = () => {
    if (newProcess === null) return;
    const name = newProcess.name;
    let ops: Op[];
    try {
      ops = addProcess(picture, value, name, newProcess.type);
    } catch (failed) {
      if (!(failed instanceof EditRefused)) throw failed;
      setNewProcess({ ...newProcess, refused: failed.message });
      return;
    }
    if (change(ops)) {
      setNewProcess(null);
      inspect(['processes', name, 'steps']);
    }
  };

  // the types a step can be added of: the built-in ones, then those the node serves
  const served = Array.isArray(steps) ? steps.map((s) => s.type).filter((t) => !Object.hasOwn(BUILT_IN, t)) : [];
  const addable = [...Object.keys(BUILT_IN).map((type) => ({ type, from: 'Built in' })), ...[...served].sort().map((type) => ({ type, from: 'Served by the node' }))];

  const markInvalid = (key: string, bad: boolean) =>
    setInvalid((set) => {
      if (set.has(key) === bad) return set;
      const next = new Set(set);
      if (bad) next.add(key);
      else next.delete(key);
      return next;
    });

  const panelEdit: PanelEdit | undefined =
    work === null
      ? undefined
      : {
          change,
          invalid: markInvalid,
          move,
          remove,
          up: index > 0,
          down: index >= 0 && index < length - 1,
          lists: lists.map((l) => ({ path: l, label: l.length === 1 ? 'the flow’s steps' : `process ${String(l[1])}` })),
          moveTo: (list) => {
            if (step !== null) change(moveToList(picture, step.path, list));
          },
          renameProcess: (from, to) => {
            if (change(() => renameProcess(picture, value, from, to))) setSelected(['processes', to, 'steps']);
          },
          deleteProcess: (name) => change(deleteProcess(value, name)),
          addProcess: () => setNewProcess({ name: '', type: addable[0]?.type ?? 'to' }),
          generation,
        };

  /** The canvas's keys, while it has the focus and the flow is edited: never those typed into a field. */
  const onKey = (event: KeyboardEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement;
    if (work === null || event.defaultPrevented || !event.currentTarget.contains(target)) return;
    const control = target.closest('button, input, textarea, select, [contenteditable="true"], [role="menu"], [role="menuitem"]');
    if (control && !control.matches('.react-flow__node button[aria-pressed="true"]')) return;
    if ((event.ctrlKey || event.metaKey) && !event.shiftKey && event.key.toLowerCase() === 'z') {
      event.preventDefault();
      undo();
    } else if (step !== null && (event.key === 'Delete' || event.key === 'Backspace')) {
      event.preventDefault();
      event.currentTarget.focus({ preventScroll: true });
      remove();
    } else if (step !== null && (event.key === 'ArrowUp' || event.key === 'ArrowDown')) {
      event.preventDefault();
      if (event.key === 'ArrowUp' ? index > 0 : index < length - 1) {
        // The old button remains at its file path when the selected step moves to another one.
        event.currentTarget.focus({ preventScroll: true });
        move(event.key === 'ArrowUp' ? -1 : 1);
      }
    }
  };

  const leaveEditing = () => {
    setDiscarding(null);
    setSaveFailure(null);
    if (unsaved && work?.undo.some(previous => previous.movedStep)) setLayoutRevision(r => r + 1);
    setWork(null);
    startAgain();
  };
  const discard = () => {
    if (discarding?.then === 'close its tab') {
      setDiscarding(null);
      discarding.close();
    } else leaveEditing();
  };

  const save = async () => {
    if (work === null) return;
    setSaving(true);
    setSaveFailure(null);
    try {
      const next = await api.save(path, work.text, result.stored.etag);
      // editing goes on from the text saved, and undo still goes back through it
      setResult({ kind: 'read', stored: next });
      notification.success({
        key: path,
        title: `Flow ${typeof value.id === 'string' ? value.id : path} saved`,
        duration: 8,
        role: 'status',
        closable: { 'aria-label': 'Dismiss save notification' },
      });
    } catch (error) {
      if (error instanceof FlowsError && error.status === 412) setConflict(true);
      else setSaveFailure(messageOf(error));
    } finally {
      setSaving(false);
    }
  };

  const reload = () => {
    setConflict(false);
    leaveEditing();
    refresh();
  };

  const header = (
    <div className="rail8-flow-header">
      <div className="rail8-flow-document">
        <div className="rail8-flow-name">
          <Typography.Text type="secondary" title={path}>{path.split('/').slice(0, -1).join(' / ')}</Typography.Text>
          <Typography.Title level={1}>{title}</Typography.Title>
        </div>
        {!canEdit && <Tag>Read-only</Tag>}
        {errors > 0 && <Tag color="error">{errors === 1 ? '1 problem' : `${errors} problems`}</Tag>}
        {warnings > 0 && <Tag color="warning">{warnings === 1 ? '1 warning' : `${warnings} warnings`}</Tag>}
        <Flex className="rail8-flow-actions" wrap gap={4} align="center">
          {work !== null ? (
            <>
              <Button size="small" type="text" onClick={() => (unsaved ? setDiscarding({ then: 'return to view mode' }) : leaveEditing())}>
                Cancel
              </Button>
              <Button size="small" icon={<CheckCircleOutlined aria-hidden />} onClick={() => setCheck(picture.problems)}>
                Check
              </Button>
              <Button size="small" type="primary" icon={<SaveOutlined aria-hidden />} disabled={!unsaved || saving || invalid.size > 0} loading={saving} onClick={() => void save()}>
                Save
              </Button>
            </>
          ) : (
            <>
              <Button size="small" type="text" icon={<ReloadOutlined aria-hidden />} onClick={refresh}>
                Refresh
              </Button>
              {canEdit && (
                <Button size="small" type="primary" icon={<EditOutlined aria-hidden />} disabled={steps === undefined} onClick={() => setWork({ text: file.text, undo: [], key: null })}>
                  Edit
                </Button>
              )}
            </>
          )}
        </Flex>
      </div>
      {file.editable && picture.readable && picture.readOnly !== undefined && <Alert type="info" showIcon title={`The editor shows this flow read-only: ${picture.readOnly}.`} />}
      {refused !== null && (
        <Alert type="error" showIcon title="The change was not made" description={refused} closable={{ 'aria-label': 'Dismiss the refusal', onClose: () => setRefused(null) }} />
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
                    <li key={i}>
                      {placeOf(p.path)}: {p.message}
                    </li>
                  ))}
                </ul>
              )}
              <p>The node checks the flow again when the package is deployed.</p>
            </>
          }
        />
      )}
      {saveFailure !== null && <Alert type="error" showIcon title="The file was not saved" description={saveFailure} />}
      {picture.problems.length > 0 && (
        <Collapse
          size="small"
          activeKey={!picture.readable || showProblems ? ['problems'] : []}
          onChange={(keys) => setShowProblems(keys.includes('problems'))}
          items={[
            {
              key: 'problems',
              label: !picture.readable || showProblems ? 'Hide the problems' : 'Show the problems',
              children: (
                <Flex vertical>
                  {picture.problems.map((p, i) => (
                    <Typography.Text key={i} type={p.warning === true ? 'warning' : 'danger'}>
                      {placeOf(p.path)}: {p.message}
                    </Typography.Text>
                  ))}
                </Flex>
              ),
            },
          ]}
        />
      )}
    </div>
  );

  if (!picture.readable) {
    return (
      <div className="rail8-flow-editor rail8-flow-unavailable" data-fill-workspace>
        {header}
        <Alert type="warning" showIcon title="The flow cannot be drawn" description="Correct the listed problems in the working folder, then refresh the diagram." />
      </div>
    );
  }

  const insertionPoint = (): Path => {
    if (step !== null) return [...step.path.slice(0, -1), index + 1];
    if (node?.kind === 'from') return ['steps', 0];
    const next = node && picture.edges.find((edge) => edge.source === node.id && edge.insertAt);
    if (next?.insertAt) return next.insertAt;
    const list = lane?.path ?? ['steps'];
    return [...list, picture.nodes.filter((n) => n.kind === 'step' && pointer(n.path.slice(0, -1)) === pointer(list)).length];
  };

  return (
    <div className="rail8-flow-editor" data-fill-workspace>
      {header}
      <div className="rail8-flow-tools" role="toolbar" aria-label="Flow editing">
        <Select
          size="small"
          className="rail8-flow-find"
          aria-label="Find step"
          placeholder="Find step"
          value={null}
          showSearch={{ optionFilterProp: 'label' }}
          suffix={<SearchOutlined aria-hidden />}
          options={searchable}
          onChange={(id: string) => {
            const chosen = searchable.find((option) => option.value === id);
            if (chosen) {
              inspect(chosen.path);
              setSearchTarget((previous) => ({ id, sequence: (previous?.sequence ?? 0) + 1 }));
            }
          }}
        />
        <Tooltip title={range ? 'Open simulation preview' : 'Set a start and a later end on connections in the same step list'}>
          <Button size="small" aria-label="Open simulation" icon={<CaretRightOutlined aria-hidden />} disabled={!range} onClick={() => setSimulationOpen(true)} />
        </Tooltip>
        {work !== null && (
          <>
            <span className="rail8-flow-rule" />
            <Button size="small" type="primary" icon={<PlusOutlined aria-hidden />} onClick={() => setAdding(insertionPoint())}>
              Add step
            </Button>
            <Tooltip title="Delete selected step">
              <Button size="small" type="text" aria-label="Delete selected step" icon={<DeleteOutlined aria-hidden />} disabled={!step || step.locked !== undefined} onClick={remove} />
            </Tooltip>
            <Tooltip title="Undo last change">
              <Button size="small" type="text" aria-label="Undo" icon={<UndoOutlined aria-hidden />} disabled={!work.undo.length} onClick={undo} />
            </Tooltip>
          </>
        )}
      </div>
      <FlowInspector
        mode={inspector}
        setMode={setInspector}
        selection={node?.label ?? title}
        onFlowSelect={node === null ? undefined : () => inspect(null)}
        canvas={
          <div className="rail8-flow-graph" data-testid="flow-canvas" inert={inspector === 'maximized'} tabIndex={-1} onKeyDown={onKey}>
            <ReactFlowProvider>
              <FlowCanvas
                view={picture}
                selected={selected === null ? null : pointer(selected)}
                inspectTarget={searchTarget?.id}
                inspectSequence={searchTarget?.sequence}
                layoutRevision={layoutRevision}
                onRangeChange={setRange}
                title={title}
                {...(typeof value.description === 'string' ? { description: value.description } : {})}
                onFlowSelect={() => inspect(null)}
                onSelect={(n) => inspect(n.path)}
                {...(work === null ? {} : { onInsert: setAdding, onRemove: removeStep })}
              />
            </ReactFlowProvider>
          </div>
        }
      >
        <FlowPanel
          flow={value}
          {...(picture.settings === undefined ? {} : { settings: picture.settings })}
          node={node}
          {...(calls === undefined ? {} : { calls })}
          {...(lane === null ? {} : { laneCalls: callsOf(picture, lane.label) })}
          problems={picture.problems}
          endpointForm={endpointForm}
          openEndpoint={openEndpoint}
          openFlow={openFlow}
          {...(panelEdit === undefined ? {} : { edit: panelEdit })}
        />
      </FlowInspector>
      <SimulationDialog open={simulationOpen && range !== null} onClose={() => setSimulationOpen(false)}
        startLabel={range?.steps[0]?.label ?? ''} endLabel={range?.steps.at(-1)?.label ?? ''} />
      <Modal
        open={adding !== null}
        title="Add a step"
        footer={null}
        onCancel={() => {
          if (calling === null) {
            setAdding(null);
            setCallFailure(null);
          }
        }}
      >
        {adding !== null && <p>At {placeOf(adding)}</p>}
        <Flex vertical gap="middle">
          <Menu
            aria-label="Step types"
            selectable={false}
            onClick={({ key }) => add(key)}
            items={addable.map(({ type, from }) => ({
              key: type,
              disabled: calling !== null,
              label: (
                <>
                  {type} <Typography.Text type="secondary">{from}</Typography.Text>
                </>
              ),
            }))}
          />
          {flowsCalled.length > 0 && (
            <>
              <Typography.Title level={3}>Call a flow</Typography.Title>
              <Menu
                aria-label="Flows to call"
                selectable={false}
                onClick={({ key }) => {
                  const called = flowsCalled.find((f) => f.path === key);
                  if (called !== undefined) void addCall(called);
                }}
                items={flowsCalled.map((f) => ({
                  key: f.path,
                  disabled: calling !== null,
                  label: (
                    <>
                      {f.id} <Typography.Text type="secondary">Serves {f.address ?? ''}</Typography.Text>
                    </>
                  ),
                }))}
              />
            </>
          )}
          {calling !== null && (
            <Flex gap="small">
              <Spin aria-label={`Creating ${calling}`} /> Creating the endpoint {calling}
            </Flex>
          )}
          {callFailure !== null && <Alert type="error" showIcon title="The call was not added" description={callFailure} />}
        </Flex>
      </Modal>
      <Modal
        open={newProcess !== null}
        title="Add a process"
        okText="Add"
        okButtonProps={{ htmlType: 'submit', form: `${ids}-process-form`, disabled: !newProcess?.name }}
        onCancel={() => setNewProcess(null)}
      >
        <p>A process holds at least one step: choose its first.</p>
        <Form layout="vertical" id={`${ids}-process-form`} onFinish={createProcess}>
          <Form.Item label="Name" required htmlFor={`${ids}-process-name`} validateStatus={newProcess?.refused === undefined ? undefined : 'error'} help={newProcess?.refused}>
            <Input
              id={`${ids}-process-name`}
              aria-label="Name"
              required
              value={newProcess?.name ?? ''}
              onChange={({ target: { value: name } }) => setNewProcess((p) => (p === null ? p : { name, type: p.type }))}
            />
          </Form.Item>
          <Form.Item label="First step" htmlFor={`${ids}-process-type`}>
            <Select
              virtual={false}
              id={`${ids}-process-type`}
              aria-label="First step"
              value={newProcess?.type ?? ''}
              options={addable.map(({ type }) => ({ value: type, label: type }))}
              onChange={(type) => setNewProcess((p) => (p === null ? p : { ...p, type }))}
            />
          </Form.Item>
        </Form>
      </Modal>
      <DiscardDialog
        isOpen={discarding !== null}
        name={typeof value.id === 'string' ? value.id : path}
        then={discarding?.then ?? ''}
        onDiscard={discard}
        onKeepEditing={() => setDiscarding(null)}
      />
      <Modal open={conflict} title="The file changed on disk" okText="Reload" cancelText="Keep editing" onOk={reload} onCancel={() => setConflict(false)}>
        <p>
          <strong>{path}</strong> changed since it was opened, and nothing was saved. Reload shows the file as it is now and discards your changes.
        </p>
      </Modal>
    </div>
  );
}
