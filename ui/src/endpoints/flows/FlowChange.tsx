import { ReactFlowProvider } from '@xyflow/react';
import { Alert, Button, Card, Flex, Table, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import type { Side } from '../../artifacts';
import { ReadSpinner } from '../../ReadSpinner';
import { contextOf, surveyOf } from '../files/flowContext';
import type { FlowsApi } from './api';
import { flowChanges, type FlowChanges, type SettingChange, type StepChange } from './changes';
import { FlowCanvas } from './FlowCanvas';
import type { FlowContext, FlowProblem, Path } from './types';
import { pointer, view } from './view';
import './flow-editor.css';

export interface FlowChangeProps {
  /** The flows of the working folder on disk, and the node's step types and ingress. */
  api: FlowsApi;
  /** The flow file's path, relative to the working folder. */
  path: string;
  before: Side | null;
  after: Side | null;
}

const messageOf = (error: unknown) => (error instanceof Error ? error.message : String(error));

/**
 * The context the flow at `path` is drawn in at the revision of `side`: the endpoints and flows of its package at that
 * revision, read through the side, the flows of the other packages as they are on disk, and the node's step types and
 * ingress.
 */
async function contextAt(api: FlowsApi, path: string, side: Side): Promise<FlowContext> {
  const pkg = path.slice(0, path.indexOf('/') + 1);
  const artifacts = await side.artifacts();
  const own = artifacts.filter((a) => (a.kind === 'flow' || a.kind === 'endpoint') && a.path !== path);
  const texts = await Promise.all(own.map(async (a) => ({ path: a.path, text: await side.read(a.path) })));
  const files = [
    // marks the package's folder, so that every file of the side is taken as one of its package
    { path: `${pkg}package.yaml`, text: 'kind: package\n' },
    { path, text: side.text },
    ...texts.filter((f): f is { path: string; text: string } => f.text !== null),
  ];
  const context = contextOf(surveyOf(files), path, false);
  const [disk, steps, ingress] = await Promise.all([diskFlows(api), api.steps(), api.ingress()]);
  return {
    endpoints: context.endpoints,
    flows: [...context.flows, ...disk.flows.filter((f) => !f.path.startsWith(pkg))],
    partial: context.partial || disk.partial,
    steps,
    ...(ingress === undefined ? {} : { ingress }),
  };
}

/** Every flow of the working folder on disk, as the flow screen's server gives them; none, and partial, when they cannot be read. */
async function diskFlows(api: FlowsApi): Promise<{ flows: FlowContext['flows']; partial: boolean }> {
  try {
    const list = await api.list();
    const any = list.items.find((item) => 'id' in item);
    if (any === undefined) return { flows: [], partial: list.truncated };
    const { file } = await api.read(any.path);
    return { flows: file.context.flows, partial: list.truncated || file.context.partial };
  } catch {
    return { flows: [], partial: true };
  }
}

/** The problems that keep a version from being drawn, each with where it stands. */
function Diagnostics({ title, problems }: { title: string; problems: readonly FlowProblem[] }) {
  return (
    <Alert
      type="error"
      showIcon
      title={title}
      description={
        <ul>
          {problems.map((p, i) => (
            <li key={i}>{p.path.length > 0 ? `${p.path.join('.')}: ${p.message}` : p.message}</li>
          ))}
        </ul>
      }
    />
  );
}

/** One line of the list of changes under the picture. */
interface Line {
  key: string;
  change: string;
  what: string;
  /** The step the line selects in the picture, when it is drawn there. */
  select?: Path;
  settings: SettingChange[];
}

const STATUS: Record<StepChange['status'], string> = { added: 'Added', removed: 'Removed', moved: 'Moved', changed: 'Changed' };

/** The lines of `changes`, the flow's own settings first; a step selects when it is in the version drawn. */
function linesOf(changes: FlowChanges, drawnIsOlder: boolean): Line[] {
  const lines: Line[] = [];
  if (changes.settings.length > 0) lines.push({ key: 'settings', change: 'Changed', what: 'The flow’s settings', settings: changes.settings });
  for (const p of changes.processes) lines.push({ key: `process:${p.name}`, change: p.status === 'added' ? 'Added' : 'Removed', what: `Process ${p.name}`, settings: [] });
  changes.steps.forEach((s, i) => {
    const drawn = (s.status === 'removed') === drawnIsOlder;
    const place = s.status === 'removed' ? `, after ${s.follows ?? 'nothing: it was first'}` : '';
    lines.push({ key: `step:${i}`, change: STATUS[s.status], what: `${s.name} in ${s.list}${place}`, ...(drawn ? { select: s.path } : {}), settings: s.settings });
  });
  return lines;
}

/**
 * The readable change of a flow file between two versions. The newer version is drawn as the flow screen's picture,
 * read-only, with its added steps marked with the success token and its changed and moved ones with the warning
 * token; the removed steps are listed beside it, each with the step it followed; the list of changes is under it, and
 * choosing a line selects its step. An added flow is drawn all as added, and a deleted one as the older version's
 * picture, marked deleted. Each version is drawn in the context of its own revision. A change of comments or
 * formatting only says so, and a version that cannot be drawn shows its diagnostics.
 */
export function FlowChange({ api, path, before, after }: FlowChangeProps) {
  const changes = useMemo(() => flowChanges(before?.text ?? null, after?.text ?? null), [before, after]);
  const drawn = changes.status === 'deleted' ? before : after;
  const [context, setContext] = useState<{ side: Side; context: FlowContext } | { side: Side; failed: string } | null>(null);
  const [selected, setSelected] = useState<{ at: string; sequence: number } | null>(null);
  const shows = changes.status === 'added' || changes.status === 'deleted' || changes.status === 'modified';

  useEffect(() => {
    if (!shows || drawn === null) return undefined;
    let current = true;
    contextAt(api, path, drawn).then(
      (read) => current && setContext({ side: drawn, context: read }),
      (error: unknown) => current && setContext({ side: drawn, failed: messageOf(error) }),
    );
    return () => {
      current = false;
    };
  }, [api, path, drawn, shows]);

  const marks = useMemo(
    () =>
      new Map(changes.status === 'deleted' ? [] : changes.steps.flatMap((s) => (s.status === 'removed' ? [] : [[pointer(s.path), s.status === 'added' ? 'added' : 'changed'] as const]))),
    [changes],
  );
  const picture = useMemo(() => (context !== null && 'context' in context && drawn !== null ? view(drawn.text, context.context) : null), [context, drawn]);

  if (changes.status === 'same') return <Typography.Text type="secondary">No change.</Typography.Text>;
  if (changes.status === 'formatting') return <Typography.Text>Only comments or formatting changed.</Typography.Text>;
  if (changes.status === 'unreadable') {
    return (
      <Flex vertical gap="small">
        {changes.unreadable.map((u) => (
          <Diagnostics
            key={u.side}
            title={`The ${u.side === 'before' ? 'older' : 'newer'} version cannot be drawn`}
            problems={view((u.side === 'before' ? before : after)?.text ?? '', { endpoints: [], flows: [], steps: [] }).problems}
          />
        ))}
      </Flex>
    );
  }
  if (drawn === null || context === null || context.side !== drawn) return <ReadSpinner label={`Reading the context of ${path}`} />;
  if ('failed' in context) return <Alert type="error" showIcon title="The context of the flow could not be read" description={context.failed} />;
  if (picture === null || !picture.readable) {
    return <Diagnostics title={`The ${changes.status === 'deleted' ? 'older' : 'newer'} version cannot be drawn`} problems={picture?.problems ?? []} />;
  }

  const removed = changes.status === 'modified' ? changes.steps.filter((s) => s.status === 'removed') : [];
  const lines = linesOf(changes, changes.status === 'deleted');
  const select = (at: Path) => setSelected((old) => ({ at: pointer(at), sequence: (old?.sequence ?? 0) + 1 }));
  return (
    <Flex vertical gap="small">
      {changes.status === 'deleted' && <Alert type="warning" showIcon title="Deleted: the older version is drawn." />}
      {changes.status === 'added' && <Alert type="success" showIcon title="Added: every step is new." />}
      {changes.repeated.map((r) => (
        <Alert key={r.list} type="info" showIcon title={`Steps share a name in ${r.list} (${r.names.join(', ')}): they are matched in their order.`} />
      ))}
      <Flex gap="small" align="stretch">
        <div className="rail8-flow-change" aria-label={`Flow picture of ${path}`}>
          <ReactFlowProvider>
            <FlowCanvas
              view={picture}
              selected={selected?.at ?? null}
              {...(selected === null ? {} : { inspectTarget: selected.at, inspectSequence: selected.sequence })}
              onSelect={(node) => select(node.path)}
              marks={marks}
            />
          </ReactFlowProvider>
        </div>
        {removed.length > 0 && (
          <Card size="small" title="Removed steps" className="rail8-flow-removed">
            <ul aria-label="Removed steps">
              {removed.map((s) => (
                <li key={`${s.list}:${pointer(s.path)}`}>
                  <Typography.Text delete>{s.name}</Typography.Text>{' '}
                  <Typography.Text type="secondary">
                    in {s.list}, after {s.follows ?? 'nothing: it was first'}
                  </Typography.Text>
                </li>
              ))}
            </ul>
          </Card>
        )}
      </Flex>
      <Table<Line>
        aria-label={`Changes of ${path}`}
        size="small"
        pagination={false}
        rowKey="key"
        dataSource={lines}
        columns={[
          {
            title: 'Change',
            dataIndex: 'change',
            width: 110,
            render: (change: string) => <Tag color={change === 'Added' ? 'success' : change === 'Removed' ? 'error' : 'warning'}>{change}</Tag>,
          },
          {
            title: 'What',
            dataIndex: 'what',
            render: (what: string, line) =>
              line.select === undefined ? (
                what
              ) : (
                <Button type="link" size="small" onClick={() => select(line.select!)}>
                  {what}
                </Button>
              ),
          },
          {
            title: 'Settings',
            dataIndex: 'settings',
            render: (settings: SettingChange[]) =>
              settings.map((s) => (
                <div key={s.key}>
                  <Typography.Text code>{`${s.key}: ${s.before} → ${s.after}`}</Typography.Text>
                </div>
              )),
          },
        ]}
      />
    </Flex>
  );
}
