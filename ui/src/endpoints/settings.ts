import type { Label, Texts } from '../language';

/** A tab of an endpoint's settings form: the platform's common ones and the adapter's own, in the order shown. */
export interface SettingsTab {
  id: string;
  label: string;
  common: boolean;
}

/**
 * One option an endpoint may set, as the admin API describes it. A `type` the form does not know gets a raw editor. Its
 * `help` is the node's text, or a message of the UI's own for a field the UI describes, such as a built-in step's.
 */
export interface SettingsField {
  name: string;
  type: 'text' | 'secret' | 'integer' | 'boolean' | 'choice' | (string & {});
  help: Label;
  tab: string;
  required: boolean;
  directions: ('in' | 'out')[];
  pattern?: string;
  default?: unknown;
  min?: number;
  max?: number;
  choices?: string[];
  visibleWhen?: { field: string; values: unknown[] };
}

/** A rule over several options; `exactlyOne` is the only kind so far, and the form passes over any other. */
export interface SettingsRule {
  kind: string;
  fields: string[];
}

/** A component's settings description, as `GET /admin/components` serves it. */
export interface SettingsDescription {
  format: number;
  /** False for a component that describes nothing: the form offers a raw editor of all its options. */
  described: boolean;
  tabs: SettingsTab[];
  fields: SettingsField[];
  rules: SettingsRule[];
}

/** An endpoint's options, the endpoint file's `config`. A key whose value is null or undefined is absent. */
export type Options = Record<string, unknown>;

/** One broken rule: the option's JSON pointer within the options (`/port`), or `''` for a rule over several; never the value. */
export interface Problem {
  path: string;
  message: string;
}

/** The types the form draws a control for; a field of any other type gets a raw editor and its value is not judged. */
export const KNOWN_TYPES = new Set(['text', 'secret', 'integer', 'boolean', 'choice']);

/** What the node sets on every in endpoint: neither is the description's business there. */
const NODE_KEYS = new Set(['flow', 'maxBodyBytes']);
const REFERENCE = /^secret:\/\/[a-z0-9][a-z0-9._-]*$/;

const present = (value: unknown) => value !== null && value !== undefined;
const serves = (field: SettingsField, direction: 'in' | 'out') => field.directions.includes(direction);
const pointer = (key: string) => `/${key.replaceAll('~', '~0').replaceAll('/', '~1')}`;

/**
 * The names of the fields shown for `options`: a field without `visibleWhen` always, one with it while the field it
 * names is shown and has, set or by default, one of its values. Visibility is transitive.
 */
export function shownFields(description: SettingsDescription, options: Options): Set<string> {
  const shown = new Set<string>();
  const values = new Map<string, unknown>();
  for (const field of description.fields) {
    const value = options[field.name];
    if (present(value)) values.set(field.name, value);
    else if (present(field.default)) values.set(field.name, field.default);
    const when = field.visibleWhen;
    const control = when === undefined ? undefined : values.get(when.field);
    if (when === undefined || (shown.has(when.field) && present(control) && when.values.includes(control))) shown.add(field.name);
  }
  return shown;
}

/** A whole number as the check takes one: an integer a 64-bit signed number holds. */
function wholeNumber(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isInteger(value)) return null;
  return value >= -(2 ** 63) && value < 2 ** 63 ? value : null;
}

/** Why a value is no whole number `field` takes, in `texts`' language: the bounds it lies within, when it has any. */
function notWhole({ t }: Texts, field: SettingsField): string {
  if (field.min !== undefined && field.max !== undefined) return t('endpoints.check.wholeFromTo', { min: String(field.min), max: String(field.max) });
  if (field.min !== undefined) return t('endpoints.check.wholeAtLeast', { min: String(field.min) });
  if (field.max !== undefined) return t('endpoints.check.wholeAtMost', { max: String(field.max) });
  return t('endpoints.check.whole');
}

/** Whether `text` matches `pattern` whole; true when the pattern is no JavaScript regular expression, as the node judges it. */
function matches(pattern: string, text: string): boolean {
  try {
    return new RegExp(`^(?:${pattern})$`).test(text);
  } catch {
    return true;
  }
}

/**
 * What is wrong with `value` for `field`, in `texts`' language, or null when it fits. A well-formed `secret://` reference
 * fits any text or secret.
 */
export function fieldProblem(texts: Texts, field: SettingsField, value: unknown): string | null {
  const { t } = texts;
  switch (field.type) {
    case 'text':
    case 'secret':
      if (typeof value !== 'string') return t('endpoints.check.text');
      if (value === '') return t('endpoints.check.empty');
      if (field.pattern === undefined || REFERENCE.test(value)) return null;
      return matches(field.pattern, value) ? null : t('endpoints.check.pattern', { pattern: field.pattern });
    case 'integer': {
      const n = wholeNumber(value);
      return n !== null && (field.min === undefined || n >= field.min) && (field.max === undefined || n <= field.max) ? null : notWhole(texts, field);
    }
    case 'boolean':
      return typeof value === 'boolean' ? null : t('endpoints.check.boolean');
    case 'choice':
      return typeof value === 'string' && (field.choices ?? []).includes(value) ? null : t('endpoints.check.choice', { choices: (field.choices ?? []).join(', ') });
    default:
      return null;
  }
}

/**
 * What is wrong with an endpoint's `options`, judged as the node judges them when it deploys, in `texts`' language: the
 * fields in their order (one of the other direction set, a value that does not fit, a required one left out while shown),
 * then keys no field has in name order, then the rules. A component that describes nothing has no problem here.
 */
export function checkOptions(texts: Texts, description: SettingsDescription, options: Options, direction: 'in' | 'out'): Problem[] {
  if (!description.described) return [];
  const { t } = texts;
  const problems: Problem[] = [];
  const shown = shownFields(description, options);
  const byName = new Map(description.fields.map((f) => [f.name, f]));
  const value = (name: string) => (present(options[name]) ? options[name] : byName.get(name)?.default);
  for (const field of description.fields) {
    if (direction === 'in' && NODE_KEYS.has(field.name)) continue;
    const set = options[field.name];
    const path = pointer(field.name);
    if (!serves(field, direction)) {
      if (present(set)) problems.push({ path, message: t(direction === 'in' ? 'endpoints.check.outOnly' : 'endpoints.check.inOnly') });
    } else if (present(set)) {
      const problem = fieldProblem(texts, field, set);
      if (problem !== null) problems.push({ path, message: problem });
    } else if (field.required && shown.has(field.name)) {
      const by = field.visibleWhen?.field;
      problems.push({ path, message: by === undefined ? t('endpoints.check.required') : t('endpoints.check.requiredWhen', { field: by, value: String(value(by)) }) });
    }
  }
  const unknown = Object.keys(options)
    .filter((key) => present(options[key]) && !byName.has(key) && !(direction === 'in' && NODE_KEYS.has(key)))
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  for (const key of unknown) problems.push({ path: pointer(key), message: t('endpoints.check.unknown') });
  for (const rule of description.rules) {
    if (rule.kind !== 'exactlyOne') continue;
    const first = byName.get(rule.fields[0] ?? '');
    if (first === undefined || !serves(first, direction)) continue;
    if (rule.fields.filter((name) => present(options[name])).length !== 1) {
      problems.push({ path: '', message: t('endpoints.check.exactlyOne', { fields: rule.fields.join(', ') }) });
    }
  }
  return problems;
}
