import { isAlias, isMap, isNode, isScalar, isSeq, parseAllDocuments, parseDocument, type Document } from 'yaml';
import type { Texts } from '../../language';
import type { FlowProblem, Path } from './types';

/**
 * A text read as a flow file:
 *
 * - `flow`: a YAML mapping whose `kind` is `flow`, with a text `id` and a list as `steps`: its first document as the
 *   `yaml` library parsed it (keys used twice are kept, for the view to find, and merge keys are not merged, as the node does not merge them), its value, and how many documents the text
 *   holds.
 * - `problem`: a text that is no YAML, or a flow without a text `id` or with a `steps` that is no list. Whether such a
 *   text is a flow file at all is for the caller to judge, as {@link saysFlow} does.
 * - `other`: YAML of another kind, or not a mapping.
 */
export type FlowRead =
  | { kind: 'flow'; doc: Document.Parsed; value: Record<string, unknown>; id: string; documents: number }
  | { kind: 'problem'; message: string }
  | { kind: 'other' };

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/** Whether `text` says, on a line of its own, that it is a flow file: what makes a file that cannot be read a flow file. */
export const saysFlow = (text: string) => /^kind:\s*["']?flow["']?\s*(#.*)?$/m.test(text);

/** Reads `text` as a flow file, as {@link FlowRead} describes. The node reads only the first document of a file too. */
export function readFlow(text: string): FlowRead {
  const docs = parseAllDocuments(text, { uniqueKeys: false, prettyErrors: false, merge: false, logLevel: 'error' });
  if (!Array.isArray(docs) || docs.length === 0) return { kind: 'other' };
  const doc = docs[0]!;
  const error = doc.errors[0] ?? docs.find((d) => d.errors.length > 0)?.errors[0];
  if (error !== undefined) return { kind: 'problem', message: `the file is not YAML: ${error.message.split('\n')[0]}` }; // untranslated: the development server reads flows with this module too, and it has no translations
  let value: unknown;
  try {
    value = doc.toJS();
  } catch (failed) {
    return { kind: 'problem', message: `the file cannot be read: ${failed instanceof Error ? failed.message : String(failed)}` }; // untranslated: the development server reads flows with this module too, and it has no translations
  }
  if (!isRecord(value) || value.kind !== 'flow') return { kind: 'other' };
  if (typeof value.id !== 'string') return { kind: 'problem', message: 'a flow file names its id' }; // untranslated: the development server reads flows with this module too, and it has no translations
  if (!Array.isArray(value.steps)) return { kind: 'problem', message: 'the flow has no list of steps under steps' }; // untranslated: the development server reads flows with this module too, and it has no translations
  return { kind: 'flow', doc, value, id: value.id, documents: docs.length };
}

/**
 * What locks a part of the file, by the path of the YAML node that holds it, and of what kind it is. `holds` says what
 * the file holds there, with its `name` for an alias, an anchor, a tag or a key used twice. `covers` is true when what
 * the node holds is locked too: the items of a list on one line, or of a mapping in braces. A key used twice and a merge
 * key lock their mapping and name the `key`.
 */
export interface Lock {
  path: Path;
  holds: 'alias' | 'anchor' | 'tag' | 'list' | 'braces' | 'complex' | 'merge' | 'twice' | 'plain' | 'step';
  name?: string;
  kind: 'anchor' | 'alias' | 'tag' | 'flow' | 'complex' | 'merge' | 'twice' | 'plain' | 'step';
  key?: string;
  covers?: true;
}

/**
 * The locks of the file and the problems of its YAML: anchors, aliases, tags, merge keys, lists on one line, mappings in
 * braces over several lines and complex keys lock the node that holds them. The node reads an alias as the anchor's name,
 * as text, and a merge key as a key of its own, so each of those is a problem too. A key used twice is a problem and
 * locks its mapping. The node reads YAML 1.1 but keeps yes, no, on, off, y and n as text, and reads numbers with colons
 * (1:30) as text too; a plain value it reads otherwise than the browser is a problem and locks its key. The problems are
 * in the language of `texts`.
 */
export function yamlFindings(doc: Document.Parsed, text: string, problems: FlowProblem[], { t }: Texts): Lock[] {
  const locks: Lock[] = [];
  const other = parseDocument(text, { version: '1.1', uniqueKeys: false, merge: false, logLevel: 'error' });
  const visit = (node: unknown, twin: unknown, path: Path) => {
    if (!isNode(node)) return;
    if (isAlias(node)) {
      problems.push({ path, message: t('flows.yaml.alias', { name: node.source }) });
      locks.push({ path, holds: 'alias', name: node.source, kind: 'alias' });
      return;
    }
    if (node.anchor !== undefined) locks.push({ path, holds: 'anchor', name: node.anchor, kind: 'anchor' });
    if (node.tag !== undefined) locks.push({ path, holds: 'tag', name: doc.directives?.tagString(node.tag) ?? node.tag, kind: 'tag' });
    if (isSeq(node)) {
      if (node.flow === true) locks.push({ path, holds: 'list', kind: 'flow', covers: true });
      node.items.forEach((item, i) => visit(item, isSeq(twin) ? twin.items[i] : undefined, [...path, i]));
    } else if (isMap(node)) {
      const range = node.range;
      if (node.flow === true && range !== undefined && range !== null && text.slice(range[0], range[1]).includes('\n')) {
        locks.push({ path, holds: 'braces', kind: 'flow', covers: true });
      }
      const seen = new Set<string>();
      node.items.forEach((pair, i) => {
        if (!isScalar(pair.key)) {
          locks.push({ path, holds: 'complex', kind: 'complex' });
          return;
        }
        const key = String(pair.key.value);
        if (key === '<<' && pair.key.type === 'PLAIN') {
          problems.push({ path: [...path, key], message: t('flows.yaml.merge') });
          locks.push({ path, holds: 'merge', kind: 'merge', key });
        }
        if (seen.has(key)) {
          problems.push({ path: [...path, key], message: t('flows.yaml.twice', { key }) });
          locks.push({ path, holds: 'twice', name: key, kind: 'twice', key });
        }
        seen.add(key);
        const twinPair = isMap(twin) ? twin.items[i] : undefined;
        visit(pair.value, twinPair !== undefined && isScalar(twinPair.key) && String(twinPair.key.value) === key ? twinPair.value : undefined, [...path, key]);
      });
    } else if (isScalar(node) && node.type === 'PLAIN' && isScalar(twin)) {
      const browser = node.value;
      const node11 = twin.value instanceof Date ? node.source : twin.value;
      const source = node.source ?? String(browser);
      if (!sameReading(browser, node11, source)) {
        problems.push({ path, message: t('flows.yaml.plain', { source }) });
        locks.push({ path, holds: 'plain', kind: 'plain' });
      }
    }
  };
  visit(doc.contents, other.contents, []);
  return locks;
}

/**
 * Whether the node reads the plain value `source` as the browser does: `browser` is the browser's reading, `node11` the
 * YAML 1.1 reading. The node keeps yes, no, on, off, y and n as text, and numbers with colons (1:30) too.
 */
function sameReading(browser: unknown, node11: unknown, source: string): boolean {
  const keptAsText =
    typeof browser === 'string' &&
    ((typeof node11 === 'boolean' && /^(y|n|yes|no|on|off)$/i.test(browser)) || /^[-+]?[0-9][0-9_]*(:[0-5]?[0-9])+(\.[0-9_]*)?$/.test(source));
  return keptAsText || Object.is(browser, node11);
}
