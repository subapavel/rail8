import { leaves, same, type SettingChange } from '../../leaves';
import { readFlow } from './flowYaml';
import type { Path } from './types';
import { currentLanguage, textsIn } from '../../language';

export type { SettingChange };

/**
 * One step that differs between the two versions, in its list (`steps`, or `processes.<name>.steps`):
 *
 * - `added`: only in the newer version; `path` is its place there.
 * - `removed`: only in the older version; `path` is its place there, and `follows` the name of the step before it
 *   there, null when it was first.
 * - `moved`: in both, out of order among the steps matched in its list; it may have changed settings too.
 * - `changed`: in both, in order, with changed settings.
 *
 * `path` is the place in the newer version for every status but `removed`.
 */
export interface StepChange {
  status: 'added' | 'removed' | 'moved' | 'changed';
  list: string;
  name: string;
  path: Path;
  follows?: string | null;
  settings: SettingChange[];
}

/**
 * How a flow differs between two versions, each read as a value with the flow screen's reader:
 *
 * - `status`: `added` and `deleted` when one version is missing; `formatting` when the values are equal and the texts
 *   are not; `same` when the texts are equal; `unreadable` when a version is no flow the reader reads, as `unreadable`
 *   says; `modified` otherwise.
 * - `settings`: the flow's own settings that changed, every top-level key but `steps` and `processes`, when both
 *   versions are there.
 * - `processes`: the processes, matched by name, that only one version has.
 * - `steps`: the steps that differ, list by list: the flow's own list first, then each process's, by name.
 * - `repeated`: the lists in which steps share a name, with those names. Their steps are matched in their order.
 */
export interface FlowChanges {
  status: 'added' | 'deleted' | 'modified' | 'formatting' | 'same' | 'unreadable';
  unreadable: { side: 'before' | 'after'; message: string }[];
  settings: SettingChange[];
  processes: { name: string; status: 'added' | 'removed' }[];
  steps: StepChange[];
  repeated: { list: string; names: string[] }[];
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/** A step's effective name, as the flow screen names it: its `name`, else the process a call calls, else its type; `step n` for an item that is no step. */
function nameOf(step: unknown, index: number): string {
  if (!isRecord(step) || typeof step.type !== 'string' || step.type === '') return `step ${index + 1}`;
  if (step.name !== undefined && step.name !== null) return String(step.name);
  return step.type === 'process' && typeof step.process === 'string' ? step.process : step.type;
}

/** The keys of `a` and `b` that their longest common subsequence keeps in order. */
function common(a: readonly string[], b: readonly string[]): Set<string> {
  const table = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0));
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      table[i]![j] = a[i] === b[j] ? table[i + 1]![j + 1]! + 1 : Math.max(table[i + 1]![j]!, table[i]![j + 1]!);
    }
  }
  const kept = new Set<string>();
  for (let i = 0, j = 0; i < a.length && j < b.length; ) {
    if (a[i] === b[j]) {
      kept.add(a[i]!);
      i++;
      j++;
    } else if (table[i + 1]![j]! >= table[i]![j + 1]!) i++;
    else j++;
  }
  return kept;
}

/** The key of each step of a list: its name, and how many steps before it in the list share that name. */
function keysOf(steps: readonly unknown[]): { key: string; name: string }[] {
  const seen = new Map<string, number>();
  return steps.map((step, i) => {
    const name = nameOf(step, i);
    const n = seen.get(name) ?? 0;
    seen.set(name, n + 1);
    return { key: `${n}\u0000${name}`, name };
  });
}

/** Compares the list of steps at `path` (`list` as it is written) between the two versions, adding its changes to `out`. */
function compareList(before: readonly unknown[], after: readonly unknown[], path: Path, list: string, out: FlowChanges): void {
  const older = keysOf(before);
  const newer = keysOf(after);
  const shared = new Set([...older, ...newer].filter((s) => s.key.startsWith('1\u0000')).map((s) => s.name));
  if (shared.size > 0) out.repeated.push({ list, names: [...shared] });
  const olderAt = new Map(older.map((s, i) => [s.key, i]));
  const newerKeys = new Set(newer.map((s) => s.key));
  const matchedOlder = older.filter((s) => newerKeys.has(s.key)).map((s) => s.key);
  const matchedNewer = newer.filter((s) => olderAt.has(s.key)).map((s) => s.key);
  const inOrder = common(matchedOlder, matchedNewer);
  newer.forEach((s, i) => {
    const at = olderAt.get(s.key);
    if (at === undefined) {
      out.steps.push({ status: 'added', list, name: s.name, path: [...path, i], settings: [] });
      return;
    }
    const settings = leaves(before[at], after[i]);
    if (!inOrder.has(s.key)) out.steps.push({ status: 'moved', list, name: s.name, path: [...path, i], settings });
    else if (settings.length > 0) out.steps.push({ status: 'changed', list, name: s.name, path: [...path, i], settings });
  });
  older.forEach((s, i) => {
    if (!newerKeys.has(s.key)) out.steps.push({ status: 'removed', list, name: s.name, path: [...path, i], follows: i === 0 ? null : older[i - 1]!.name, settings: [] });
  });
}

/** The parts of a flow `flowChanges` compares, from its value: its own settings, its list of steps and its processes. */
function partsOf(value: Record<string, unknown> | null) {
  const steps = value !== null && Array.isArray(value.steps) ? value.steps : [];
  const processes = value !== null && isRecord(value.processes) ? value.processes : {};
  const settings = Object.fromEntries(Object.entries(value ?? {}).filter(([k]) => k !== 'steps' && k !== 'processes'));
  return { steps, processes, settings };
}

/** The steps of a process, from its value: none when it holds no list of steps. */
const stepsOf = (process: unknown) => (isRecord(process) && Array.isArray(process.steps) ? process.steps : []);

/**
 * How the flow file changed from `before` to `after`, each its text or null when the file has no version there, as
 * {@link FlowChanges} describes. Every step of an added flow is added, and every step of a deleted one removed; their
 * own settings are not listed.
 */
export function flowChanges(before: string | null, after: string | null): FlowChanges {
  const out: FlowChanges = { status: 'modified', unreadable: [], settings: [], processes: [], steps: [], repeated: [] };
  if (before === after) return { ...out, status: 'same' };
  const valueOf = (text: string | null, side: 'before' | 'after') => {
    if (text === null) return null;
    const read = readFlow(text);
    if (read.kind === 'flow') return read.value;
    out.unreadable.push({ side, message: read.kind === 'problem' ? read.message : textsIn(currentLanguage()).t('flows.change.notFlow') });
    return null;
  };
  const older = valueOf(before, 'before');
  const newer = valueOf(after, 'after');
  if (out.unreadable.length > 0) return { ...out, status: 'unreadable' };
  if (older !== null && newer !== null && same(older, newer)) return { ...out, status: 'formatting' };

  const a = partsOf(older);
  const b = partsOf(newer);
  if (older !== null && newer !== null) out.settings = leaves(a.settings, b.settings);
  compareList(a.steps, b.steps, ['steps'], 'steps', out);
  const names = [...Object.keys(b.processes), ...Object.keys(a.processes).filter((n) => !Object.hasOwn(b.processes, n))];
  for (const name of names) {
    if (!Object.hasOwn(a.processes, name)) out.processes.push({ name, status: 'added' });
    else if (!Object.hasOwn(b.processes, name)) out.processes.push({ name, status: 'removed' });
    compareList(stepsOf(a.processes[name]), stepsOf(b.processes[name]), ['processes', name, 'steps'], `processes.${name}.steps`, out);
  }
  return { ...out, status: before === null ? 'added' : after === null ? 'deleted' : 'modified' };
}
