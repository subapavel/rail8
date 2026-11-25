import type { FunctionCatalogue } from '../../../api/client';

/** The drilldown's groups, in the order of the SAP PO StandardFunctions library; `node` is Node Functions. */
export const GROUPS = ['arithmetic', 'boolean', 'text', 'conversions', 'date', 'constants', 'node', 'statistic'] as const;
export type Group = (typeof GROUPS)[number];

/** The follow-up issues of the functions the catalogue lacks. */
const LOOKUPS = 165;
const VALUE_MAPPING = 166;
const MISSING_FUNCTIONS = 172;

/**
 * The drilldown's entries in their order: each entry's group and our catalogue name, or for an entry the catalogue lacks,
 * its shown name, its input count and its follow-up issue. The 9 renamed functions (`1/x`, `FixValues`,
 * `FormatNum`, `DateTrans`, `DateBefore`, `DateAfter`, `CompareDates`, `CopyValue`, `SplitByValue`) carry our name.
 */
const PROTOTYPE: readonly (readonly [Group, string] | readonly [Group, string, number, number])[] = [
  ...(['add', 'subtract', 'equalsA', 'abs', 'sqrt', 'sqr', 'sign', 'neg', 'inv', 'power', 'less', 'greater', 'multiply', 'divide', 'max', 'min', 'ceil', 'floor', 'round', 'counter'].map(
    (fn) => ['arithmetic', fn] as const,
  )),
  ...(['and', 'or', 'not', 'equals', 'notEquals', 'if'].map((fn) => ['boolean', fn] as const)),
  ['boolean', 'ifS', 3, MISSING_FUNCTIONS],
  ['text', 'substring'],
  ['conversions', 'fixValues'],
  ['arithmetic', 'formatNum'],
  ['text', 'concat'],
  ['text', 'equalsS'],
  ['text', 'indexOf'],
  ['text', 'indexOf', 3, MISSING_FUNCTIONS],
  ...(['currentDate', 'dateTrans', 'dateBefore', 'dateAfter', 'compareDates'].map((fn) => ['date', fn] as const)),
  ['text', 'lastIndexOf'],
  ['text', 'lastIndexOf', 3, MISSING_FUNCTIONS],
  ...(['compare', 'replaceString', 'length', 'endsWith', 'startsWith'].map((fn) => ['text', fn] as const)),
  ['text', 'startsWith', 3, MISSING_FUNCTIONS],
  ...(['toUpperCase', 'trim', 'toLowerCase'].map((fn) => ['text', fn] as const)),
  ['constants', 'constant'],
  ['constants', 'copyValue'],
  ...(['createIf', 'removeContexts', 'replaceValue', 'exists'].map((fn) => ['node', fn] as const)),
  ['boolean', 'ifWithoutElse'],
  ['boolean', 'ifSWithoutElse', 2, MISSING_FUNCTIONS],
  ['node', 'splitByValue'],
  ['node', 'collapseContexts'],
  ['conversions', 'Value mapping', 1, VALUE_MAPPING], // untranslated: the function's name in SAP PO
  ...(['sum', 'average', 'count', 'index'].map((fn) => ['statistic', fn] as const)),
  ['node', 'useOneAsMany'],
  ['constants', 'sender', 0, MISSING_FUNCTIONS],
  ['constants', 'receiver', 0, MISSING_FUNCTIONS],
  ...(['sort', 'sortByKey', 'mapWithDefault', 'formatByExample'].map((fn) => ['node', fn] as const)),
  ['conversions', 'JDBC Lookup', 1, LOOKUPS], // untranslated: the function's name in SAP PO
  ['conversions', 'RFC Lookup', 0, LOOKUPS], // untranslated: the function's name in SAP PO
  ['constants', 'xsi:nil', 0, MISSING_FUNCTIONS],
  ['boolean', 'isNil'],
];

/** The group of a catalogue function the entries do not list, by its function family. */
const FAMILY_GROUPS: Record<string, Group> = {
  arithmetic: 'arithmetic',
  boolean: 'boolean',
  text: 'text',
  conversions: 'conversions',
  date: 'date',
  constant: 'constants',
  context: 'node',
  statistic: 'statistic',
};

/** The listed names with an overload, which show their input count. */
const OVERLOADED = new Set(['indexOf', 'lastIndexOf', 'startsWith']);

/** One function of the drilldown. */
export interface DrilldownFunction {
  /** Unique within the drilldown. */
  key: string;
  name: string;
  /** The catalogue function to add; absent for a function the catalogue lacks, which is shown disabled. */
  fn?: string;
  /** The input count an overloaded name shows. */
  overload?: number;
  /** Whether the function has box parameters. */
  configurable: boolean;
  /** The follow-up issue of a function the catalogue lacks. */
  issue?: number;
  /** The key of a preview function (`mock/preview-functions`) that stands in for a function the catalogue lacks. */
  preview?: string;
}

/** The listed names of the functions offered as previews, and their keys. */
const PREVIEWS: Record<string, string> = { 'Value mapping': 'valuemap', 'JDBC Lookup': 'tableLookup', 'RFC Lookup': 'rfclookup' }; // untranslated: the functions' names in SAP PO

export interface DrilldownGroup {
  group: Group;
  functions: DrilldownFunction[];
}

/**
 * The functions drilldown: every listed catalogue function under its group in the listed order, a catalogue function
 * the entries do not list at the end of its family's group (a family without a group goes to Node Functions), and the
 * listed functions the catalogue lacks with their follow-up issue: Value mapping and the
 * lookups as previews, the others disabled.
 */
export function drilldown(catalogue: FunctionCatalogue): DrilldownGroup[] {
  const groups = new Map<Group, DrilldownFunction[]>(GROUPS.map((g) => [g, []]));
  const listed = new Set<string>();
  const configurable = (fn: string) => (catalogue[fn]?.params?.length ?? 0) > 0;
  for (const [i, [group, name, inputs, issue]] of PROTOTYPE.entries()) {
    const known = issue === undefined && Object.hasOwn(catalogue, name);
    if (issue === undefined && !known) continue;
    if (known) listed.add(name);
    const count = known ? (catalogue[name]!.inputs?.length ?? 0) : inputs!;
    groups.get(group)!.push({
      key: `${i}:${name}`,
      name,
      ...(known && { fn: name }),
      ...(OVERLOADED.has(name) && { overload: count }),
      configurable: known && configurable(name),
      ...(issue !== undefined && { issue }),
      ...(Object.hasOwn(PREVIEWS, name) && { preview: PREVIEWS[name] }),
    });
  }
  for (const name of Object.keys(catalogue).sort()) {
    if (listed.has(name)) continue;
    groups.get(FAMILY_GROUPS[catalogue[name]!.family] ?? 'node')!.push({ key: name, name, fn: name, configurable: configurable(name) });
  }
  return GROUPS.map((group) => ({ group, functions: groups.get(group)! }));
}
