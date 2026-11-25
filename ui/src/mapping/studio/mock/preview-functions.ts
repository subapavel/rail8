import { LOOKUPS_ISSUE, VALUE_MAPPING_ISSUE, type PreviewParam } from './definition-preview';

/** A parameter of a preview function. */
export interface PreviewFunctionParameter {
  key: string;
  /** The id of its label's text. */
  label: string;
  type: 'String' | 'vmsagency' | 'vmtagency' | 'vmstrategy' | 'field_list' | 'bool' | 'Channel';
  initial: PreviewParam;
}

/** A function the catalogue lacks that the drilldown offers as a preview. */
export interface PreviewFunction {
  key: 'valuemap' | 'tableLookup' | 'rfclookup';
  name: string;
  inputs: string[];
  /** Whether it has an output port: a lookup's ports are its export fields, which no table offers yet. */
  output: boolean;
  parameters: PreviewFunctionParameter[];
  issue: number;
}

/** The preview functions: Value mapping, JDBC Lookup and RFC Lookup. */
export const PREVIEW_FUNCTIONS: readonly PreviewFunction[] = [
  {
    key: 'valuemap',
    name: 'Value mapping', // untranslated: the function's name in SAP PO
    inputs: ['a'],
    output: true,
    issue: VALUE_MAPPING_ISSUE,
    parameters: [
      // no namespace parameter (http://sap.com/xi/XI): the studio has no namespace
      { key: 'context', label: 'studio.vm.context', type: 'String', initial: '' },
      { key: 'agency1', label: 'studio.vm.sourceAgency', type: 'vmsagency', initial: 'SenderParty' },
      { key: 'schema1', label: 'studio.vm.sourceSchema', type: 'String', initial: '' },
      { key: 'agency2', label: 'studio.vm.targetAgency', type: 'vmtagency', initial: 'ReceiverParty' },
      { key: 'schema2', label: 'studio.vm.targetSchema', type: 'String', initial: '' },
      { key: 'vmstrategy', label: 'studio.param.missing', type: 'vmstrategy', initial: '2' },
      { key: 'vmdefault', label: 'studio.param.default', type: 'String', initial: '' },
    ],
  },
  {
    key: 'tableLookup',
    name: 'JDBC Lookup', // untranslated: the function's name in SAP PO
    inputs: [],
    output: false,
    issue: LOOKUPS_ISSUE,
    parameters: [
      { key: 'ref', label: 'studio.lookup.table', type: 'String', initial: '' },
      { key: 'ins', label: 'studio.lookup.inputs', type: 'field_list', initial: [] },
      { key: 'outs', label: 'studio.lookup.outputs', type: 'field_list', initial: [] },
      { key: 'split', label: 'studio.lookup.split', type: 'bool', initial: false },
      { key: 'channel', label: 'studio.lookup.channel', type: 'Channel', initial: '' },
    ],
  },
  {
    key: 'rfclookup',
    name: 'RFC Lookup', // untranslated: the function's name in SAP PO
    inputs: [],
    output: false,
    issue: LOOKUPS_ISSUE,
    parameters: [
      { key: 'ref', label: 'studio.lookup.rfcInterface', type: 'String', initial: '' },
      { key: 'channel', label: 'studio.lookup.channel', type: 'Channel', initial: '' },
      { key: 'useExc', label: 'studio.lookup.useException', type: 'bool', initial: false },
      { key: 'ins', label: 'studio.lookup.imports', type: 'field_list', initial: [] },
      { key: 'outs', label: 'studio.lookup.exports', type: 'field_list', initial: [] },
    ],
  },
];

export const previewFunction = (key: string): PreviewFunction | undefined => PREVIEW_FUNCTIONS.find((f) => f.key === key);

/** A preview function's parameters with their initial values. */
export const initialParams = (fn: PreviewFunction): Record<string, PreviewParam> => Object.fromEntries(fn.parameters.map((p) => [p.key, p.initial]));

/** The lookup catalogue: the one RFC module it offers; no database table. */
export const RFC_MODULE = 'SXIDEMO_AIRL_FLIGHT_CHECKAVAIL';
export const RFC_NAMESPACE = 'urn:sap-com:document:sap:rfc:functions';
