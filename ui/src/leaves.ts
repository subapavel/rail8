/** The longest a value of a changed setting is written: longer ones are cut, ending in `…`. */
const MAX_VALUE = 200;

/** One changed setting, leaf by leaf: its key path, such as `delivery.mode` or `routes[1].when`, and its two values on one line each; `none` when absent. */
export interface SettingChange {
  key: string;
  before: string;
  after: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/** Whether two values read from YAML or JSON are equal: the same scalars, lists in order, and mappings by key in any order. */
export function same(a: unknown, b: unknown): boolean {
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
export function written(value: unknown): string {
  const text = value instanceof Date ? JSON.stringify(value.toISOString()) : (JSON.stringify(value) ?? String(value));
  return text.length > MAX_VALUE ? `${text.slice(0, MAX_VALUE - 1)}…` : text;
}

/** The key path of `key` under `at`: `a.b`, and `a[2]` for an item of a list. */
const keyOf = (at: string, key: string | number) => (typeof key === 'number' ? `${at}[${key}]` : at === '' ? key : `${at}.${key}`);

/**
 * The leaves that differ between `before` and `after`, two values read from YAML or JSON, each with its key path from
 * `at` (`''` by default) and its two values as {@link written} gives them, `none` for a missing one. A mapping or a
 * list on both sides is compared key by key, or item by item; a mapping or list against a missing value is listed
 * leaf by leaf; anything else is one leaf. `undefined` is a missing value.
 */
export function leaves(before: unknown, after: unknown, at = ''): SettingChange[] {
  const out: SettingChange[] = [];
  collect(before, after, at, out);
  return out;
}

function collect(before: unknown, after: unknown, at: string, out: SettingChange[]): void {
  if (same(before, after)) return;
  const nonEmpty = (v: unknown) => (isRecord(v) && Object.keys(v).length > 0) || (Array.isArray(v) && v.length > 0);
  if (isRecord(before) && isRecord(after)) {
    const keys = [...Object.keys(before), ...Object.keys(after).filter((k) => !Object.hasOwn(before, k))];
    for (const k of keys) collect(before[k], after[k], keyOf(at, k), out);
    return;
  }
  if (Array.isArray(before) && Array.isArray(after)) {
    for (let i = 0; i < Math.max(before.length, after.length); i++) collect(before[i], after[i], keyOf(at, i), out);
    return;
  }
  // a mapping or list against a missing value is listed leaf by leaf; against a value of another shape it is one leaf
  if (before === undefined && nonEmpty(after)) return collect(Array.isArray(after) ? [] : {}, after, at, out);
  if (after === undefined && nonEmpty(before)) return collect(before, Array.isArray(before) ? [] : {}, at, out);
  out.push({ key: at, before: before === undefined ? 'none' : written(before), after: after === undefined ? 'none' : written(after) });
}
