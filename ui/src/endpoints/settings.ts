import { msg, type Label, type Texts } from '../language';

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
  /** What the form calls the field; its name when it has none. */
  label?: Label;
  help: Label;
  tab: string;
  required: boolean;
  directions: ('in' | 'out')[];
  pattern?: string;
  default?: unknown;
  min?: number;
  max?: number;
  choices?: string[];
  /** What the form calls each of the choices, by value; a choice without one is called by its value. */
  choiceLabels?: Record<string, string>;
  visibleWhen?: { field: string; values: unknown[] };
  /** The form of the adapter the field belongs to, on an endpoint's form. */
  group?: Group;
}

/** The two forms of an adapter: what reaches the partner, and what one use of it in a flow sets. */
export type Group = 'connection' | 'flow';

/** A rule over several options; `exactlyOne` is the only kind so far, and the form passes over any other. */
export interface SettingsRule {
  kind: string;
  fields: string[];
}

/** One form's settings description: a step's, or an endpoint's as {@link endpointSettings} reads it. */
export interface SettingsDescription {
  format: number;
  /** False for a component that describes nothing: the form offers a raw editor of all its options. */
  described: boolean;
  tabs: SettingsTab[];
  fields: SettingsField[];
  rules: SettingsRule[];
}

/** A field as the node describes it: with its label, its texts' keys, and its choices each with its label. */
export interface DescribedField extends Omit<SettingsField, 'label' | 'help' | 'choices' | 'choiceLabels' | 'group'> {
  label: string;
  labelKey?: string;
  help: string;
  helpKey?: string;
  choices?: { value: string; label: string; labelKey?: string }[];
  /** How a value written as an expression is escaped, on a field of the flow's form that takes one. */
  expression?: string;
}

/** One of an adapter's forms as the node describes it, such as `Settings.toJson` gives it. */
export interface DescribedForm {
  /** False for a form that describes nothing: it takes what the other form does not describe. */
  described: boolean;
  tabs: SettingsTab[];
  fields: DescribedField[];
  rules: SettingsRule[];
}

/**
 * A component's settings, as `GET /admin/components` serves them (format 2): which directions it serves and whether
 * each reaches a connection, whether each of its two forms is described, the tabs, the fields of the connection's form
 * and of the flow's, and the rules over both.
 */
export interface ComponentForms {
  format: number;
  reach: Partial<Record<'in' | 'out', 'connection' | 'none'>>;
  described: { connection: boolean; flow: boolean };
  tabs: (SettingsTab & { labelKey?: string })[];
  connection: DescribedField[];
  flow: DescribedField[];
  rules: SettingsRule[];
}

/**
 * The settings of an endpoint of one direction, as the form shows them and the check judges them: the fields of both
 * forms, each with its group, in that order. `described` is false when the form has nothing to draw, so it offers a raw
 * editor of all the options.
 */
export interface EndpointSettings extends SettingsDescription {
  /** False when the component does not serve the endpoint's direction. */
  served: boolean;
  /** The group a key that no field describes belongs to: the one whose form describes nothing, or null when such a key is refused. */
  stray: Group | null;
}

/** An endpoint's options, the endpoint file's `config`. A key whose value is null or undefined is absent. */
export type Options = Record<string, unknown>;

/** One broken rule: the option's JSON pointer within the options (`/port`), or `''` for a rule over several; never the value. */
export interface Problem {
  path: string;
  /** What kind of problem it is, as the node names it: `required`, `unknown`, `pattern`, `direction` and the like. */
  code: string;
  message: string;
}

/** The types the form draws a control for; a field of any other type gets a raw editor and its value is not judged. */
export const KNOWN_TYPES = new Set(['text', 'secret', 'integer', 'boolean', 'choice']);

/** What the node sets on every in endpoint: neither is the description's business there. */
const NODE_KEYS = new Set(['flow', 'maxBodyBytes']);
/** The reply's limit of an out endpoint that reaches a connection: the platform's, never a field of either form. */
const REPLY = 'maxBodyBytes';
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

/** What is wrong with `value` for `field`: its code and its message in `texts`' language, or null when it fits. */
function wrong(texts: Texts, field: SettingsField, value: unknown): { code: string; message: string } | null {
  const { t } = texts;
  switch (field.type) {
    case 'text':
    case 'secret':
      if (typeof value !== 'string') return { code: 'type', message: t('endpoints.check.text') };
      if (value === '') return { code: 'type', message: t('endpoints.check.empty') };
      if (field.pattern === undefined || REFERENCE.test(value)) return null;
      return matches(field.pattern, value) ? null : { code: 'pattern', message: t('endpoints.check.pattern', { pattern: field.pattern }) };
    case 'integer': {
      const n = wholeNumber(value);
      if (n !== null && (field.min === undefined || n >= field.min) && (field.max === undefined || n <= field.max)) return null;
      return { code: n === null ? 'type' : field.min !== undefined && n < field.min ? 'min' : 'max', message: notWhole(texts, field) };
    }
    case 'boolean':
      return typeof value === 'boolean' ? null : { code: 'type', message: t('endpoints.check.boolean') };
    case 'choice':
      return typeof value === 'string' && (field.choices ?? []).includes(value) ? null : { code: 'choice', message: t('endpoints.check.choice', { choices: (field.choices ?? []).join(', ') }) };
    default:
      return null;
  }
}

/**
 * What is wrong with `value` for `field`, in `texts`' language, or null when it fits. A well-formed `secret://` reference
 * fits any text or secret.
 */
export function fieldProblem(texts: Texts, field: SettingsField, value: unknown): string | null {
  return wrong(texts, field, value)?.message ?? null;
}

/** `form` as the form takes it: each choice by its value, with its label beside it, and each field in `group`. */
export function readForm(form: DescribedForm, group?: Group): SettingsDescription {
  return { format: 2, described: form.described, tabs: form.tabs, fields: form.fields.map((f) => readField(f, group)), rules: form.rules };
}

function readField(described: DescribedField, group?: Group): SettingsField {
  const { choices, ...field } = described;
  const read: SettingsField = { ...field, ...(group === undefined ? {} : { group }) };
  // the texts' keys and the expression are the description's, no business of the form yet
  delete (read as Partial<DescribedField>).labelKey;
  delete (read as Partial<DescribedField>).helpKey;
  delete (read as Partial<DescribedField>).expression;
  if (choices !== undefined) {
    read.choices = choices.map((c) => c.value);
    read.choiceLabels = Object.fromEntries(choices.map((c) => [c.value, c.label]));
  }
  return read;
}

/**
 * The settings of an endpoint of `direction` over the component's `forms`, split as the node splits an endpoint file:
 * the connection's fields, which take no pattern here because a file may still hold what a connection of an
 * environment may not, such as a user name in a URL; on an out endpoint that reaches a described connection the reply's
 * limit `maxBodyBytes`, optional (the flow's limit holds without it), of at least 1; then the flow's fields.
 */
export function endpointSettings(forms: ComponentForms, direction: 'in' | 'out'): EndpointSettings {
  const connects = forms.reach[direction] === 'connection';
  const connection = forms.connection.map((f): SettingsField => {
    const field = readField(f, 'connection');
    delete field.pattern;
    return field;
  });
  const reply: SettingsField[] =
    direction === 'out' && connects && forms.described.connection
      ? [{ name: REPLY, type: 'integer', label: msg('endpoints.field.replyLimit'), help: msg('endpoints.field.replyLimit.help'), tab: 'general', required: false, directions: ['out'], min: 1, group: 'connection' }]
      : [];
  return {
    format: forms.format,
    served: forms.reach[direction] !== undefined,
    described: (connects && forms.described.connection) || forms.described.flow,
    stray: connects && !forms.described.connection ? 'connection' : !forms.described.flow ? 'flow' : null,
    tabs: forms.tabs.map(({ id, label, common }) => ({ id, label, common })),
    fields: [...connection, ...reply, ...forms.flow.map((f) => readField(f, 'flow'))],
    rules: forms.rules,
  };
}

/**
 * What is wrong with `options` for one form, judged as the node's check of a form judges them, in `texts`' language: the
 * fields in their order (one of the other direction set, a value that does not fit, a required one left out while
 * shown), then keys no field has in name order unless `strays` is false, then the rules. A form that describes nothing
 * has no problem here.
 */
export function checkForm(texts: Texts, description: SettingsDescription, options: Options, direction: 'in' | 'out', strays = true): Problem[] {
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
      if (present(set)) problems.push({ path, code: 'direction', message: t(direction === 'in' ? 'endpoints.check.outOnly' : 'endpoints.check.inOnly') });
    } else if (present(set)) {
      const problem = wrong(texts, field, set);
      if (problem !== null) problems.push({ path, ...problem });
    } else if (field.required && shown.has(field.name)) {
      const by = field.visibleWhen?.field;
      problems.push({ path, code: 'required', message: by === undefined ? t('endpoints.check.required') : t('endpoints.check.requiredWhen', { field: by, value: String(value(by)) }) });
    }
  }
  if (strays) problems.push(...unknownKeys(texts, Object.keys(options).filter((key) => present(options[key]) && !byName.has(key) && !(direction === 'in' && NODE_KEYS.has(key)))));
  for (const rule of description.rules) {
    if (rule.kind !== 'exactlyOne') continue;
    const first = byName.get(rule.fields[0] ?? '');
    if (first === undefined || !serves(first, direction)) continue;
    if (rule.fields.filter((name) => present(options[name])).length !== 1) {
      problems.push({ path: '', code: 'exactlyOne', message: t('endpoints.check.exactlyOne', { fields: rule.fields.join(', ') }) });
    }
  }
  return problems;
}

function unknownKeys({ t }: Texts, keys: string[]): Problem[] {
  return [...keys].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)).map((key) => ({ path: pointer(key), code: 'unknown', message: t('endpoints.check.unknown') }));
}

/** The group of the form a key of an endpoint's options belongs to, as the node splits them; null for one the node sets, or one no form takes. */
export function groupOf(settings: EndpointSettings, key: string, direction: 'in' | 'out'): Group | null {
  if (direction === 'in' && NODE_KEYS.has(key)) return null;
  const field = settings.fields.find((f) => f.name === key);
  return field?.group ?? settings.stray;
}

/**
 * What is wrong with an endpoint's `options`, judged as the node judges them when it deploys, in `texts`' language: the
 * connection's form, then the flow's, each with its rules, then the reply's limit, then the keys no form takes in name
 * order. An endpoint whose component serves its direction no other way has that one problem; one whose settings have
 * nothing to draw has none.
 */
export function checkOptions(texts: Texts, settings: EndpointSettings, options: Options, direction: 'in' | 'out'): Problem[] {
  if (!settings.served) return [{ path: '', code: 'direction', message: texts.t('endpoints.check.direction', { direction }) }];
  if (!settings.described) return [];
  const share: Record<Group | 'reply', Options> = { connection: {}, flow: {}, reply: {} };
  const unknown: string[] = [];
  for (const [key, value] of Object.entries(options)) {
    if (!present(value)) continue;
    if (direction === 'out' && key === REPLY && settings.fields.some((f) => f.name === REPLY)) share.reply[key] = value;
    else {
      const group = groupOf(settings, key, direction);
      if (group !== null) share[group][key] = value;
      else if (!(direction === 'in' && NODE_KEYS.has(key))) unknown.push(key);
    }
  }
  // a rule is judged with the form its first field is in: the other form has no such field and passes over it
  const form = (group: Group): SettingsDescription => ({ ...settings, described: true, fields: settings.fields.filter((f) => f.group === group && f.name !== REPLY) });
  const reply = settings.fields.filter((f) => f.name === REPLY);
  return [
    ...checkForm(texts, form('connection'), share.connection, direction, false),
    ...checkForm(texts, form('flow'), share.flow, direction, false),
    ...(reply.length === 0 ? [] : checkForm(texts, { ...settings, described: true, fields: reply, rules: [] }, share.reply, direction, false)),
    ...unknownKeys(texts, unknown),
  ];
}
