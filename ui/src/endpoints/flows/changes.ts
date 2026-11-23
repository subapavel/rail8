import { readFlow } from './flowYaml';
import type { Path } from './types';

/** The longest a value of a changed setting is written: longer ones are cut, ending in `…`. */
const MAX_VALUE = 200;

/** One changed setting, leaf by leaf: its key path, such as `delivery.mode` or `routes[1].when`, and its two values on one line each; `none` when absent. */
export interface SettingChange {
  key: string;
  before: string;
  after: string;
}

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

/** Whether two values read from YAML are equal: the same scalars, lists in order, and mappings by key in any order. */
function same(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) return true;
  if (a instanceof Date && b instanceof Date) return a.getTime() === b.getTime();
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((v, i) => same(v, b[i]));
  if (isRecord(a) && isRecord(b)) {
    const keys = Object.keys(a);
    return keys.length === Object.keys(b).length && keys.every((k) => Object.hasOwn(b, k) && same(a[k], b[k]));
  }
  return false;
}

/** A value on one line of at most {@link MAX_VALUE} characters: JSON, so a text is in quotes as written, `secret://` names included. */
function written(value: unknown): string {
  const text = value instanceof Date ? JSON.stringify(value.toISOString()) : (JSON.stringify(value) ?? String(value));
  return text.length > MAX_VALUE ? `${text.slice(0, MAX_VALUE - 1)}…` : text;
}

/** The key path of `key` under `at`: `a.b`, and `a[2]` for an item of a list. */
const keyOf = (at: string, key: string | number) => (typeof key === 'number' ? `${at}[${key}]` : at === '' ? key : `${at}.${key}`);

/**
 * The leaves that differ between `before` and `after`, under the key path `at`. A mapping or a list on both sides is
 * compared key by key, or item by item; anything else is one leaf. `undefined` is a missing value.
 */
function leaves(before: unknown, after: unknown, at: string, out: SettingChange[]): void {
  if (same(before, after)) return;
  const nonEmpty = (v: unknown) => (isRecord(v) && Object.keys(v).length > 0) || (Array.isArray(v) && v.length > 0);
  if (isRecord(before) && isRecord(after)) {
    const keys = [...Object.keys(before), ...Object.keys(after).filter((k) => !Object.hasOwn(before, k))];
    for (const k of keys) leaves(before[k], after[k], keyOf(at, k), out);
    return;
  }
  if (Array.isArray(before) && Array.isArray(after)) {
    for (let i = 0; i < Math.max(before.length, after.length); i++) leaves(before[i], after[i], keyOf(at, i), out);
    return;
  }
  // a mapping or list against a missing value is listed leaf by leaf; against a value of another shape it is one leaf
  if (before === undefined && nonEmpty(after)) return leaves(Array.isArray(after) ? [] : {}, after, at, out);
  if (after === undefined && nonEmpty(before)) return leaves(before, Array.isArray(before) ? [] : {}, at, out);
  out.push({ key: at, before: before === undefined ? 'none' : written(before), after: after === undefined ? 'none' : written(after) });
}

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
    const settings: SettingChange[] = [];
    leaves(before[at], after[i], '', settings);
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
    out.unreadable.push({ side, message: read.kind === 'problem' ? read.message : 'the file is not a flow' });
    return null;
  };
  const older = valueOf(before, 'before');
  const newer = valueOf(after, 'after');
  if (out.unreadable.length > 0) return { ...out, status: 'unreadable' };
  if (older !== null && newer !== null && same(older, newer)) return { ...out, status: 'formatting' };

  const a = partsOf(older);
  const b = partsOf(newer);
  if (older !== null && newer !== null) leaves(a.settings, b.settings, '', out.settings);
  compareList(a.steps, b.steps, ['steps'], 'steps', out);
  const names = [...Object.keys(b.processes), ...Object.keys(a.processes).filter((n) => !Object.hasOwn(b.processes, n))];
  for (const name of names) {
    if (!Object.hasOwn(a.processes, name)) out.processes.push({ name, status: 'added' });
    else if (!Object.hasOwn(b.processes, name)) out.processes.push({ name, status: 'removed' });
    compareList(stepsOf(a.processes[name]), stepsOf(b.processes[name]), ['processes', name, 'steps'], `processes.${name}.steps`, out);
  }
  return { ...out, status: before === null ? 'added' : after === null ? 'deleted' : 'modified' };
}
