import { currentLanguage, textsIn, type Texts } from '../../language';
import { same } from '../../leaves';
import type { Box, MappingModel, StructureRef, TargetFieldMapping, UdfDeclaration } from '../generated/mapping-model';

/** The longest a formula is written: a longer one is cut, ending in `…`. Two formulas are compared whole all the same. */
const MAX_FORMULA = 500;

/**
 * One row of a mapping's change: what changed (a part of the signature, a target field's path, a part of a UDF, the
 * description), its two values on one line each, `none` when absent, and its status. A row of a UDF's source file
 * holds the file's two texts in `diff`, `''` for a side that has none, to be shown as a line diff.
 */
export interface MappingRow {
  key: string;
  field: string;
  before: string;
  after: string;
  status: 'added' | 'removed' | 'changed' | 'structure changed'; // untranslated: a status, shown by its label
  diff?: { before: string; after: string };
}

/**
 * How a mapping file differs between two versions:
 *
 * - `status`: `added` and `deleted` when one version is missing; `formatting` when the values are equal and the texts
 *   are not; `same` when the texts are equal; `unreadable` when a version is no mapping model of format 1, as
 *   `unreadable` says; `modified` otherwise.
 * - `rows`: the signature (the source's and the target's bundle, root and structure), then each target field added,
 *   removed or changed, by path, then the UDFs by name and their source files, then the description.
 * - `moved`: how many target fields have the same formula on both sides and their boxes placed differently.
 *
 * Boxes that are only numbered differently are no change.
 */
export interface MappingChanges {
  status: 'added' | 'deleted' | 'modified' | 'formatting' | 'same' | 'unreadable';
  unreadable: { side: 'before' | 'after'; message: string }[];
  rows: MappingRow[];
  moved: number;
}

/** The texts of the UDF source files of each side, by their path relative to the working folder; null for a file that is not there. */
export interface UdfSources {
  before: ReadonlyMap<string, string | null>;
  after: ReadonlyMap<string, string | null>;
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/** `text` read as a mapping model, or why it is none, in the language of `texts`. */
function modelOf(text: string, texts: Texts): MappingModel | string {
  const { t } = texts;
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch (error) {
    return t('mapping.change.notJson', { reason: error instanceof Error ? error.message : String(error) });
  }
  if (!isRecord(value)) return t('mapping.change.noObject');
  if (value.formatVersion !== 1) return t('mapping.change.formatVersion', { version: JSON.stringify(value.formatVersion) ?? t('mapping.change.missing') });
  return shapeProblem(value, texts) ?? (value as unknown as MappingModel);
}

/**
 * What keeps `model` from the shape {@link mappingChanges} reads, or null when it has it: its target field mappings
 * and UDFs are objects of objects, every box is an object, and every list of inputs a list.
 */
function shapeProblem(model: Record<string, unknown>, { t }: Texts): string | null {
  const { targetFieldMappings: fields, udfs } = model;
  if (fields !== undefined && (!isRecord(fields) || !Object.values(fields).every(isRecord))) return t('mapping.change.fieldsNotObjects');
  if (udfs !== undefined && (!isRecord(udfs) || !Object.values(udfs).every(isRecord))) return t('mapping.change.udfsNotObjects');
  for (const [path, field] of Object.entries((fields ?? {}) as Record<string, Record<string, unknown>>)) {
    const boxes = field.boxes ?? {};
    if (!isRecord(boxes) || !Object.values(boxes).every((box) => isRecord(box) && (box.inputs === undefined || Array.isArray(box.inputs)))) {
      return t('mapping.change.boxesNotObjects', { path });
    }
  }
  return null;
}

/**
 * The path, relative to the working folder, of the file a UDF's `source` names (a file reference, `#`, the method),
 * read from the folder of the mapping file at `mappingPath`; null when it names no file of the mapping's package: a
 * reference to another package, one that leaves the package, or one that is no reference.
 */
export function udfSourcePath(mappingPath: string, source: string): string | null {
  const hash = source.lastIndexOf('#');
  const reference = hash < 0 ? source : source.slice(0, hash);
  if (reference === '' || reference.startsWith('/') || reference.includes(':') || reference.includes('\\')) return null;
  const segments = mappingPath.split('/').slice(0, -1);
  for (const segment of reference.split('/')) {
    if (segment === '..') segments.pop();
    else if (segment !== '.' && segment !== '') segments.push(segment);
  }
  const pkg = mappingPath.slice(0, mappingPath.indexOf('/'));
  return segments.length > 1 && segments[0] === pkg ? segments.join('/') : null;
}

/** The UDF source files the mapping file at `path` holding `text` names, by their path relative to the working folder; none when it is no mapping. */
export function udfSourcePaths(path: string, text: string | null): string[] {
  const model = text === null ? null : modelOf(text, textsIn('en'));
  return model === null || typeof model === 'string' ? [] : sourcePathsOf(path, model);
}

/** The UDF source files the mapping `model` at `path` names, by their path relative to the working folder. */
function sourcePathsOf(path: string, model: MappingModel | null): string[] {
  if (model === null || !isRecord(model.udfs)) return [];
  const paths = Object.values(model.udfs).flatMap((udf) => (isRecord(udf) && typeof udf.source === 'string' ? [udfSourcePath(path, udf.source)] : []));
  return [...new Set(paths.filter((p): p is string => p !== null))].sort();
}

/** A value on one line, as JSON: a text in double quotes. */
const quoted = (value: unknown) => JSON.stringify(value) ?? String(value);

/** A parameter's value as written in a formula: a text quoted, a table as its keys and values sorted by key. */
function paramValue(value: unknown): string {
  if (!isRecord(value)) return quoted(value);
  return `{${Object.keys(value)
    .sort()
    .map((k) => `${quoted(k)}: ${quoted(value[k])}`)
    .join(', ')}}`;
}

/**
 * The formulas of target field mappings, written from their result boxes down, and the canonical form two formulas
 * are compared by. A box's canonical form names its children by the number of their own canonical form, so that a box
 * that feeds several others is never written out more than once, and a formula's size stays that of its boxes.
 */
class Formulas {
  private readonly numbers = new Map<string, number>();

  /** `texts` writes the words of a formula: none, nothing connected, a missing box, a cycle. */
  constructor(private readonly texts: Texts) {}

  /**
   * The canonical number of the formula from `result` down, with the boxes not connected to it, and its text, cut at
   * {@link MAX_FORMULA} characters. The boxes not connected are written after the formula, each with the boxes that
   * feed it, as `; not connected: upper(_)`.
   */
  of(mapping: TargetFieldMapping | undefined): { canonical: number; text: string } {
    const { t } = this.texts;
    if (mapping === undefined) return { canonical: -1, text: t('mapping.change.none') };
    const boxes = isRecord(mapping.boxes) ? (mapping.boxes as Record<string, Box>) : {};
    const memo = new Map<string, number>();
    const result = this.canonical(boxes, mapping.result, memo, new Set());
    const formula = mapping.result === null ? t('mapping.change.nothingConnected') : this.text(boxes, mapping.result, new Set(), { left: MAX_FORMULA });
    const connected = reached(boxes, mapping.result);
    const loose = Object.keys(boxes).filter((id) => !connected.has(id));
    if (loose.length === 0) return { canonical: result, text: formula };
    const fed = new Set(loose.flatMap((id) => inputsOf(boxes[id]!)));
    const tops = loose.filter((id) => !fed.has(id));
    const shown = (tops.length > 0 ? tops : loose).map((id) => this.text(boxes, id, new Set(), { left: MAX_FORMULA })).sort();
    const canonical = this.number(JSON.stringify([result, loose.map((id) => this.canonical(boxes, id, memo, new Set())).sort((x, y) => x - y)]));
    const text = t('mapping.change.notConnected', { formula, boxes: shown.join(', ') });
    return { canonical, text: text.length > MAX_FORMULA ? `${text.slice(0, MAX_FORMULA - 1)}…` : text };
  }

  private number(form: string): number {
    let n = this.numbers.get(form);
    if (n === undefined) {
      n = this.numbers.size;
      this.numbers.set(form, n);
    }
    return n;
  }

  private canonical(boxes: Record<string, Box>, id: string | null | undefined, memo: Map<string, number>, open: Set<string>): number {
    if (id === null || id === undefined) return this.number('_');
    const known = memo.get(id);
    if (known !== undefined) return known;
    const box = boxes[id];
    if (box === undefined) return this.number('missing');
    if (open.has(id)) return this.number('cycle');
    open.add(id);
    const inputs = 'inputs' in box && Array.isArray(box.inputs) ? box.inputs.map((input) => this.canonical(boxes, input, memo, open)) : [];
    open.delete(id);
    const params = 'params' in box && isRecord(box.params) ? Object.keys(box.params).sort().map((k) => [k, paramValue(box.params![k])]) : [];
    const head =
      box.kind === 'source' ? ['source', box.field, box.context ?? null] : box.kind === 'function' ? ['function', box.fn] : box.kind === 'udf' ? ['udf', box.udf] : [box.kind, box.name, box.origin];
    const n = this.number(JSON.stringify([...head, inputs, params]));
    memo.set(id, n);
    return n;
  }

  private text(boxes: Record<string, Box>, id: string | null | undefined, open: Set<string>, budget: { left: number }): string {
    if (budget.left <= 0) return '…';
    if (id === null || id === undefined) return '_';
    const box = boxes[id];
    const { t } = this.texts;
    if (box === undefined) return t('mapping.change.missingBox');
    if (open.has(id)) return t('mapping.change.cycle');
    let text: string;
    if (box.kind === 'source') text = box.context === undefined ? box.field : t('mapping.change.inContext', { field: box.field, context: box.context });
    else {
      const params = 'params' in box && isRecord(box.params) ? box.params : {};
      const names = Object.keys(params).sort();
      if (box.kind === 'function' && box.fn === 'constant' && (box.inputs ?? []).length === 0 && names.length === 1 && names[0] === 'value') return quoted(params.value);
      open.add(id);
      const inputs = (box.inputs ?? []).map((input) => this.text(boxes, input, open, budget));
      open.delete(id);
      const name = box.kind === 'function' ? box.fn : box.kind === 'udf' ? box.udf : t('mapping.change.unresolved', { name: box.name });
      text = `${name}(${[...inputs, ...names.map((k) => `${k}: ${paramValue(params[k])}`)].join(', ')})`;
    }
    budget.left -= text.length;
    return text.length > MAX_FORMULA ? `${text.slice(0, MAX_FORMULA - 1)}…` : text;
  }
}

/** The inputs of a box, none for a source field. */
const inputsOf = (box: Box) => ('inputs' in box && Array.isArray(box.inputs) ? box.inputs : []);

/** The ids of the boxes the formula reaches from `result`, each once, in the order it reaches them. */
function reached(boxes: Record<string, Box>, result: string | null): Set<string> {
  const seen = new Set<string>();
  const visit = (id: string | null | undefined) => {
    if (id === null || id === undefined || seen.has(id) || boxes[id] === undefined) return;
    seen.add(id);
    inputsOf(boxes[id]).forEach(visit);
  };
  visit(result);
  return seen;
}

/**
 * Where the target and each box of a target field mapping are placed: the boxes the formula reaches in the order it
 * reaches them, then the positions of the boxes not connected to it, sorted.
 */
function placesOf(mapping: TargetFieldMapping): unknown[] {
  const boxes = isRecord(mapping.boxes) ? (mapping.boxes as Record<string, Box>) : {};
  const at = isRecord(mapping.layout?.boxes) ? mapping.layout.boxes : {};
  const connected = reached(boxes, mapping.result);
  const loose = Object.keys(boxes)
    .filter((id) => !connected.has(id))
    .map((id) => JSON.stringify(at[id] ?? null))
    .sort();
  return [mapping.layout?.target ?? null, ...[...connected].map((id) => at[id] ?? null), ...loose];
}

/** A structure's root on one line: an element's EQName, a WSDL operation's interface, operation and direction, or a JSON root. */
function rootOf(root: unknown): string {
  if (!isRecord(root)) return quoted(root);
  if (typeof root.element === 'string') return root.element;
  if (isRecord(root.wsdl)) {
    const { interface: owner, operation, direction, fault } = root.wsdl;
    return `${String(owner)} ${String(operation)} ${String(direction)}${fault === undefined ? '' : ` ${String(fault)}`}`;
  }
  if (root.json === '$') return 'JSON $';
  return quoted(root);
}

/** The rows of a side of the signature, `Source` or `Target`, named in the language of `texts`. */
function signatureRows(side: 'Source' | 'Target', before: StructureRef | undefined, after: StructureRef | undefined, out: MappingRow[], { t }: Texts): void {
  const none = t('mapping.change.none');
  const bundle = (s: StructureRef | undefined) => (s === undefined || !Array.isArray(s.bundle) ? none : s.bundle.join(', '));
  const root = (s: StructureRef | undefined) => (s === undefined ? none : rootOf(s.root));
  for (const [part, of] of [
    ['bundle', bundle],
    ['root', root],
  ] as const) {
    const b = of(before);
    const a = of(after);
    if (b !== a) out.push({ key: `${side}:${part}`, field: t(`mapping.change.${side.toLowerCase()}.${part}`), before: b, after: a, status: statusOf(b, a, none) });
  }
  if (before !== undefined && after !== undefined && before.fingerprint !== after.fingerprint) {
    const field = t(`mapping.change.${side.toLowerCase()}.structure`);
    out.push({ key: `${side}:structure`, field, before: String(before.fingerprint), after: String(after.fingerprint), status: 'structure changed' }); // untranslated: a status, shown by its label
  }
}

/** A row's status from its two values, `none` the text of an absent one. */
const statusOf = (before: string, after: string, none: string): MappingRow['status'] => (before === none ? 'added' : after === none ? 'removed' : 'changed');

/** A UDF's parameters with their types, by name. */
const paramsOf = (udf: UdfDeclaration) => (isRecord(udf.params) ? Object.keys(udf.params).sort().map((k) => `${k}: ${String(udf.params![k])}`) : []);

/** A UDF's declaration on one line: its execution kind, its inputs and its parameters with their types. */
const declarationOf = (udf: UdfDeclaration) => `${String(udf.executionKind)}(${[...(udf.inputs ?? []), ...paramsOf(udf)].join(', ')})`;

/** The rows of the UDFs, by name, and of their source files, by path, named in the language of `texts`. */
function udfRows(path: string, before: MappingModel | null, after: MappingModel | null, sources: UdfSources, out: MappingRow[], { t }: Texts): void {
  const none = t('mapping.change.none');
  const olderUdfs = isRecord(before?.udfs) ? before.udfs : {};
  const newerUdfs = isRecord(after?.udfs) ? after.udfs : {};
  for (const name of [...new Set([...Object.keys(olderUdfs), ...Object.keys(newerUdfs)])].sort()) {
    const older = olderUdfs[name];
    const newer = newerUdfs[name];
    if (older === undefined || newer === undefined) {
      const was = older === undefined ? none : declarationOf(older);
      const is = newer === undefined ? none : declarationOf(newer);
      out.push({ key: `udf:${name}`, field: t('mapping.change.udf', { name }), before: was, after: is, status: statusOf(was, is, none) });
      continue;
    }
    // each part: its row key, its text's id, and its two values
    const parts: [string, string, string, string][] = [
      ['execution kind', 'executionKind', String(older.executionKind), String(newer.executionKind)], // untranslated: a row key
      ['inputs', 'inputs', (older.inputs ?? []).join(', ') || none, (newer.inputs ?? []).join(', ') || none],
      ['parameters', 'parameters', paramsOf(older).join(', ') || none, paramsOf(newer).join(', ') || none],
      ['source', 'source', String(older.source), String(newer.source)],
    ];
    for (const [part, id, was, is] of parts) {
      if (was !== is) out.push({ key: `udf:${name}:${part}`, field: t(`mapping.change.udf.${id}`, { name }), before: was, after: is, status: 'changed' });
    }
  }
  const files = [...new Set([...sourcePathsOf(path, before), ...sourcePathsOf(path, after)])].sort();
  for (const file of files) {
    const older = before === null ? null : (sources.before.get(file) ?? null);
    const newer = after === null ? null : (sources.after.get(file) ?? null);
    if (older === newer) continue;
    const was = older === null ? none : file;
    const is = newer === null ? none : file;
    out.push({ key: `source:${file}`, field: t('mapping.change.udfSource', { file }), before: was, after: is, status: statusOf(was, is, none), diff: { before: older ?? '', after: newer ?? '' } });
  }
}

/**
 * How the mapping file at `path` changed from `before` to `after`, each its text or null when the file has no version
 * there, as {@link MappingChanges} describes. Each target field's side is one formula from its result box down:
 * functions with their inputs and parameters, source fields with their contexts, and UDFs by name, such as
 * `concat(/Orders/Order/Id, "-")`; `_` is an input with nothing connected. `sources` holds the texts of the UDF source
 * files each side names, as {@link udfSourcePaths} lists them. The rows' names, the words of the formulas and the
 * reasons a version is no mapping are in the language of `texts`, by default the one the UI shows.
 */
export function mappingChanges(path: string, before: string | null, after: string | null, sources: UdfSources, texts: Texts = textsIn(currentLanguage())): MappingChanges {
  const { t } = texts;
  const out: MappingChanges = { status: 'modified', unreadable: [], rows: [], moved: 0 };
  if (before === after) return { ...out, status: 'same' };
  const read = (text: string | null, side: 'before' | 'after') => {
    if (text === null) return null;
    const model = modelOf(text, texts);
    if (typeof model !== 'string') return model;
    out.unreadable.push({ side, message: model });
    return null;
  };
  const older = read(before, 'before');
  const newer = read(after, 'after');
  if (out.unreadable.length > 0) return { ...out, status: 'unreadable' };
  const sourcesSame = [...new Set([...sources.before.keys(), ...sources.after.keys()])].every((f) => sources.before.get(f) === sources.after.get(f));
  if (older !== null && newer !== null && same(older, newer) && sourcesSame) return { ...out, status: 'formatting' };

  signatureRows('Source', older?.source, newer?.source, out.rows, texts);
  signatureRows('Target', older?.target, newer?.target, out.rows, texts);

  const formulas = new Formulas(texts);
  const a = isRecord(older?.targetFieldMappings) ? older.targetFieldMappings : {};
  const b = isRecord(newer?.targetFieldMappings) ? newer.targetFieldMappings : {};
  for (const field of [...new Set([...Object.keys(a), ...Object.keys(b)])].sort()) {
    const f = formulas.of(a[field]);
    const g = formulas.of(b[field]);
    if (f.canonical !== g.canonical) out.rows.push({ key: `field:${field}`, field, before: f.text, after: g.text, status: statusOf(f.text, g.text, t('mapping.change.none')) });
    else if (a[field] !== undefined && b[field] !== undefined && !same(placesOf(a[field]), placesOf(b[field]))) out.moved++;
  }

  udfRows(path, older, newer, sources, out.rows, texts);
  const none = t('mapping.change.none');
  const description = (m: MappingModel | null) => (typeof m?.description === 'string' && m.description !== '' ? m.description : none);
  if (description(older) !== description(newer)) {
    out.rows.push({ key: 'description', field: t('mapping.change.description'), before: description(older), after: description(newer), status: statusOf(description(older), description(newer), none) });
  }
  return { ...out, status: before === null ? 'added' : after === null ? 'deleted' : 'modified' };
}
