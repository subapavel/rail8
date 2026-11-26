import { msg, say, type Label, type Message, type Texts } from '../../language';
import { fieldProblem, type SettingsField, type SettingsRule } from '../settings';
import type { FlowProblem, Path, StepField, StepSettings, ValueField } from './types';

/**
 * A built-in step type as the UI describes it: the fields of its form, its rules, which paths inside it hold lists of steps (`stepLists`, where `*` stands for any
 * key or index), which key its node shows as its detail, and a check of what its fields alone cannot say.
 */
export interface StepType {
  fields: StepField[];
  rules?: SettingsRule[];
  stepLists?: readonly (readonly string[])[];
  detail?: string;
  check?: (values: Record<string, unknown>) => { key: string; message: Message }[];
}

/** The keys of a step that are the platform's, on the "Step" tab: never the step's own settings. */
export const STEP_KEYS = new Set(['type', 'name', 'retryable', 'recording']);

/** A key or prop name, as the flow schema writes it: never one that starts with `rail8.`. */
export const KEY_NAME = '(?!rail8\\.)[A-Za-z][A-Za-z0-9_.-]*';
/** A step's name, as the flow schema writes it. */
export const STEP_NAME = '[a-z][a-zA-Z0-9_-]*';

type Extra = Partial<Omit<ValueField, 'name' | 'type'>>;

const field = (name: string, type: ValueField['type'], help: Label, extra: Extra = {}): ValueField => ({
  name,
  type,
  help,
  tab: 'general',
  required: false,
  directions: ['in', 'out'],
  ...extra,
});

const language = field('language', 'text', msg('flows.help.language'), { default: 'xpath' });

/** The built-in steps, described in the UI. The choices of `endpoint` and `process` are filled from the flow and its context. */
export const BUILT_IN: Readonly<Record<string, StepType>> = {
  to: {
    fields: [
      field('endpoint', 'choice', msg('flows.help.to.endpoint'), { choices: [], reference: 'endpoint' }),
      field('byProp', 'text', msg('flows.help.to.byProp'), { pattern: KEY_NAME }),
      {
        ...field('endpoints', 'text', msg('flows.help.to.endpoints')),
        type: 'map',
        key: {},
        value: field('endpoint', 'choice', msg('flows.help.to.endpointsValue'), { required: true, choices: [], reference: 'endpoint' }),
      },
    ],
    rules: [{ kind: 'exactlyOne', fields: ['endpoint', 'byProp'] }],
    check: (values) =>
      values.byProp !== undefined && values.byProp !== null && (values.endpoints === undefined || values.endpoints === null)
        ? [{ key: 'endpoints', message: msg('flows.check.endpointsRequired') }]
        : [],
  },
  route: {
    fields: [
      field('prop', 'text', msg('flows.help.route.prop'), { required: true, pattern: KEY_NAME }),
      language,
      {
        ...field('routes', 'text', msg('flows.help.route.routes'), { required: true }),
        type: 'list',
        item: [
          field('when', 'text', msg('flows.help.route.when'), { required: true }),
          field('set', 'text', msg('flows.help.route.set'), { required: true, emptyAllowed: true }),
        ],
      },
      field('otherwise', 'text', msg('flows.help.route.otherwise'), { emptyAllowed: true }),
    ],
    detail: 'prop',
  },
  filter: {
    fields: [field('when', 'text', msg('flows.help.filter.when'), { required: true }), language],
    detail: 'when',
  },
  extractKey: {
    fields: [
      field('key', 'text', msg('flows.help.extractKey.key'), { required: true, pattern: KEY_NAME }),
      field('xpath', 'text', msg('flows.help.extractKey.xpath'), { required: true }),
      language,
    ],
    detail: 'key',
  },
  validate: {
    fields: [field('required', 'text', msg('flows.help.validate.required'), { required: true }), language],
    detail: 'required',
  },
  split: {
    fields: [field('xpath', 'text', msg('flows.help.split.xpath'), { required: true }), language],
    detail: 'xpath',
  },
  process: {
    fields: [field('process', 'choice', msg('flows.help.process.process'), { required: true, choices: [], reference: 'process' })],
    detail: 'process',
  },
};

/** An adapter's name, as the flow schema writes it. */
export const ADAPTER = '[a-z][a-z0-9+.-]{0,31}';
/** A connection key of two or three segments, as the flow schema writes it. */
export const CONNECTION_KEY = '(?!(rail8|subscribers|by-agreement)/)[a-z][a-z0-9-]{0,63}(/[a-z][a-z0-9-]{0,63}){1,2}';

/**
 * The `to` step of a flow of schemaVersion 2: its receiver's adapter and the connection it sends through, or none for the
 * platform's own `direct`, with the settings in the flow.
 */
export const NAMED_TO: StepType = {
  fields: [
    field('adapter', 'text', msg('flows.help.to.adapter'), { required: true, pattern: ADAPTER }),
    field('connection', 'text', msg('flows.help.to.connection'), { pattern: CONNECTION_KEY }),
    field('settings', 'raw', msg('flows.help.to.settings')),
    field('maxReplyBytes', 'integer', msg('flows.help.to.maxReplyBytes'), { min: 1 }),
  ],
};

const duration = { pattern: '\\d+(ms|s|m|h)' };

/**
 * The flow's own settings, every top-level key of a flow file but `schemaVersion`, `kind`, `steps` and `processes`, a
 * key inside a key dotted (`delivery.mode`). The choices of `from.endpoint` are the in endpoints of the package.
 */
export const FLOW_FIELDS: StepField[] = [
  field('id', 'text', msg('flows.help.flow.id'), { required: true, pattern: '[a-z][a-z0-9-]*' }),
  field('version', 'text', msg('flows.help.flow.version'), { required: true, pattern: '\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?' }),
  field('description', 'text', msg('flows.help.flow.description')),
  field('from.endpoint', 'choice', msg('flows.help.flow.from'), { required: true, choices: [], reference: 'endpoint' }),
  field('delivery.mode', 'choice', msg('flows.help.flow.deliveryMode'), { required: true, choices: ['sync', 'at-least-once', 'at-most-once'] }),
  field('delivery.timeout', 'text', msg('flows.help.flow.deliveryTimeout'), duration),
  field('delivery.saveTimeout', 'text', msg('flows.help.flow.saveTimeout'), duration),
  field('delivery.queue', 'choice', msg('flows.help.flow.queue'), { choices: ['postgres', 'kafka'] }),
  field('delivery.retries', 'raw', msg('flows.help.flow.retries')),
  field('recording.level', 'choice', msg('flows.help.flow.recordingLevel'), { choices: ['all', 'ends', 'errors', 'off'] }),
  field('recording.payload', 'choice', msg('flows.help.flow.recordingPayload'), { choices: ['keep', 'metadata-only'] }),
  field('limits.maxBodyBytes', 'integer', msg('flows.help.flow.maxBodyBytes'), { required: true, min: 1 }),
  field('limits.maxInFlight', 'integer', msg('flows.help.flow.maxInFlight'), { min: 1 }),
  field('limits.maxInFlightBytes', 'integer', msg('flows.help.flow.maxInFlightBytes'), { min: 1 }),
  field('partitionBy', 'text', msg('flows.help.flow.partitionBy'), { pattern: KEY_NAME }),
  field('forwardHeaders', 'raw', msg('flows.help.flow.forwardHeaders')),
  field('retry', 'raw', msg('flows.help.flow.retry')),
];

/**
 * The flow's own settings in a flow of schemaVersion 2: its sender is an adapter, the connection it polls or none, and the
 * settings in the flow, in place of an endpoint.
 */
export const NAMED_FLOW_FIELDS: StepField[] = FLOW_FIELDS.flatMap((f) =>
  f.name === 'from.endpoint'
    ? [
        field('from.adapter', 'text', msg('flows.help.flow.fromAdapter'), { required: true, pattern: ADAPTER }),
        field('from.connection', 'text', msg('flows.help.flow.fromConnection'), { pattern: CONNECTION_KEY }),
        field('from.settings', 'raw', msg('flows.help.flow.fromSettings')),
      ]
    : [f],
);

/** The top-level keys of a flow file that are not among its own settings. */
export const FLOW_PARTS = new Set(['schemaVersion', 'kind', 'steps', 'processes']);

/** The fields of the "Step" tab: a process call takes only the name. */
export function stepTabFields(type: string | undefined, defaultName: string): StepField[] {
  const name = field('name', 'text', msg('flows.help.step.name', { name: defaultName }), { pattern: STEP_NAME });
  if (type === 'process') return [name];
  return [
    name,
    field('retryable', 'boolean', msg('flows.help.step.retryable')),
    field('recording.level', 'choice', msg('flows.help.step.recordingLevel'), { choices: ['all', 'ends', 'errors', 'off'] }),
    field('recording.payload', 'choice', msg('flows.help.step.recordingPayload'), { choices: ['keep', 'metadata-only'] }),
  ];
}

/** A settings description of `fields` and `rules`, on one tab. */
export function settingsOf(fields: StepField[], rules: SettingsRule[] = []): StepSettings {
  return { format: 1, described: true, tabs: [{ id: 'general', label: 'Settings', common: false }], fields, rules }; // untranslated: the panel names its own tabs, so this label is never shown
}

/** A description of no settings: the step's keys go in one raw editor. */
export const UNDESCRIBED: StepSettings = { format: 1, described: false, tabs: [], fields: [], rules: [] };

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/**
 * The form of a step of a module or plugin from its `configSchema`: each top-level property but the platform's keys
 * becomes a field (a string text with its pattern, or a choice when it has an `enum`; an integer a whole number with its
 * bounds; a boolean a switch; anything else a raw field), with its `description`, `default` and `required`. Null when the
 * schema has no top-level properties. `closed` is true when the schema refuses other keys.
 */
export function describeSchema(schema: unknown): { settings: StepSettings; closed: boolean } | null {
  if (!isRecord(schema) || !isRecord(schema.properties)) return null;
  const required = new Set(Array.isArray(schema.required) ? schema.required.filter((r): r is string => typeof r === 'string') : []);
  const fields: StepField[] = [];
  for (const [name, property] of Object.entries(schema.properties)) {
    if (STEP_KEYS.has(name)) continue;
    const p = isRecord(property) ? property : {};
    const extra: Extra = { required: required.has(name) };
    if (typeof p.description === 'string') extra.help = p.description;
    if (p.default !== undefined) extra.default = p.default;
    let type: ValueField['type'] = 'raw';
    if (p.type === 'string' && Array.isArray(p.enum) && p.enum.every((c) => typeof c === 'string')) {
      type = 'choice';
      extra.choices = p.enum as string[];
    } else if (p.type === 'string') {
      type = 'text';
      // a schema's pattern may match any part of the value; a field's pattern matches the whole of it
      if (typeof p.pattern === 'string') extra.pattern = `[\\s\\S]*(?:${p.pattern})[\\s\\S]*`;
    } else if (p.type === 'integer') {
      type = 'integer';
      if (typeof p.minimum === 'number') extra.min = p.minimum;
      if (typeof p.maximum === 'number') extra.max = p.maximum;
    } else if (p.type === 'boolean') type = 'boolean';
    const { help, ...rest } = extra;
    fields.push(field(name, type, help ?? '', rest));
  }
  return { settings: settingsOf(fields), closed: schema.additionalProperties === false };
}

const present = (value: unknown) => value !== null && value !== undefined;

/** What is wrong with `value` for `field`, by path below the field's own, in `texts`' language, or nothing. */
function judge(texts: Texts, field: StepField, value: unknown, at: Path): FlowProblem[] {
  const { t } = texts;
  if (field.type === 'list') {
    if (!Array.isArray(value)) return [{ path: at, message: t('flows.check.list') }];
    return value.flatMap((item, i) =>
      isRecord(item) ? checkFields(texts, field.item, item, [...at, i]) : [{ path: [...at, i], message: t('flows.check.mapping') }],
    );
  }
  if (field.type === 'map') {
    if (!isRecord(value)) return [{ path: at, message: t('flows.check.mapping') }];
    return Object.entries(value).flatMap(([key, v]) => {
      const found: FlowProblem[] = [];
      if (field.key.pattern !== undefined && !new RegExp(`^(?:${field.key.pattern})$`).test(key)) {
        found.push({ path: [...at, key], message: t('flows.check.keyPattern', { pattern: field.key.pattern }) });
      }
      return [...found, ...judge(texts, field.value, v, [...at, key])];
    });
  }
  if ('emptyAllowed' in field && field.emptyAllowed === true && value === '') return [];
  // what a reference names is judged against the flow and its context, which knows when the working folder was read in part
  if ('reference' in field && field.reference !== undefined) return typeof value === 'string' ? [] : [{ path: at, message: t('endpoints.check.text') }];
  const problem = fieldProblem(texts, field as SettingsField, value);
  return problem === null ? [] : [{ path: at, message: problem }];
}

/**
 * What is wrong with `values` for `fields`, each at its path below `at`, a dotted name a key inside a key, in `texts`'
 * language: a value that does not fit, or a required one left out.
 */
export function checkFields(texts: Texts, fields: readonly StepField[], values: Record<string, unknown>, at: Path): FlowProblem[] {
  const problems: FlowProblem[] = [];
  for (const f of fields) {
    const keys = f.name.split('.');
    const value = keys.reduce<unknown>((v, k) => (isRecord(v) ? v[k] : undefined), values);
    if (present(value)) problems.push(...judge(texts, f, value, [...at, ...keys]));
    else if (f.required) problems.push({ path: [...at, ...keys], message: texts.t('endpoints.check.required') });
  }
  return problems;
}

/**
 * What is wrong with the step `values` at `at` for its description, in `texts`' language: its fields, then the rules,
 * then its type's own check, then, when `closed`, each key that no field describes. The keys of the "Step" tab are
 * judged by {@link checkStepTab}.
 */
export function checkStep(texts: Texts, settings: StepSettings, values: Record<string, unknown>, at: Path, closed: boolean, own?: StepType['check']): FlowProblem[] {
  if (!settings.described) return [];
  const { t } = texts;
  const problems = checkFields(texts, settings.fields, values, at);
  for (const rule of settings.rules) {
    if (rule.kind === 'exactlyOne' && rule.fields.filter((name) => present(values[name])).length !== 1) {
      problems.push({ path: at, message: t('endpoints.check.exactlyOne', { fields: rule.fields.join(', ') }) });
    }
  }
  for (const { key, message } of own?.(values) ?? []) problems.push({ path: [...at, key], message: say(texts, message) });
  if (closed) {
    const described = new Set(settings.fields.map((f) => f.name));
    for (const key of Object.keys(values)) {
      if (!described.has(key) && !STEP_KEYS.has(key)) problems.push({ path: [...at, key], message: t('endpoints.check.unknown') });
    }
  }
  return problems;
}

/**
 * What is wrong with the keys of the "Step" tab of the step `values` at `at`, in `texts`' language; a process call's are
 * judged by the rules of calls.
 */
export function checkStepTab(texts: Texts, values: Record<string, unknown>, at: Path): FlowProblem[] {
  if (values.type === 'process') {
    return present(values.name) ? judge(texts, stepTabFields('process', '')[0]!, values.name, [...at, 'name']) : [];
  }
  const [name, retryable, level, payload] = stepTabFields(undefined, '');
  const problems = checkFields(texts, [name!, retryable!], values, at);
  const recording = values.recording;
  if (present(recording)) {
    if (!isRecord(recording)) problems.push({ path: [...at, 'recording'], message: texts.t('flows.check.recording') });
    else {
      for (const [key, f] of [['level', level!], ['payload', payload!]] as const) {
        if (present(recording[key])) problems.push(...judge(texts, f, recording[key], [...at, 'recording', key]));
      }
      for (const key of Object.keys(recording)) {
        if (key !== 'level' && key !== 'payload') problems.push({ path: [...at, 'recording', key], message: texts.t('endpoints.check.unknown') });
      }
    }
  }
  return problems;
}
