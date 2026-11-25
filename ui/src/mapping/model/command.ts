import { currentLanguage, textsIn, type Texts } from '../../language';
import type { components } from '../generated/api';
import type { AuthoringId, BoxReference, FieldPath, MappingModel, TargetFieldMapping } from '../generated/mapping-model';

/** A refused field's refusal as `/api/structures` carries it: the reason's code and the declaration's position. */
export type FieldRefusal = components['schemas']['RefusalDto'];

/**
 * Why a command was refused at a box or mapping id, by the model check's codes;
 * `not-source-field-box` is the editor's own, as no model check sees a remap.
 */
export interface BoxRefusal {
  code:
    | 'duplicate-authoring-id'
    | 'cross-target-field-reference'
    | 'unknown-box-reference'
    | 'box-cycle'
    | 'wrong-arity'
    | 'context-not-ancestor'
    | 'unknown-parameter'
    | 'wrong-parameter-type'
    | 'invalid-parameter-value'
    | 'not-source-field-box';
  id: AuthoringId;
}

/** Why a command was refused at a refused field: the field and the refusal it carries in its tree. */
export interface RefusedField {
  code: 'refused-field';
  side: 'source' | 'target';
  field: FieldPath;
  refusal: FieldRefusal;
}

/** Why a command was refused. */
export type Refusal = BoxRefusal | RefusedField;

/** A command's answer: the next model, or why the model stays as it was. */
export type CommandResult = { kind: 'applied'; model: MappingModel } | ({ kind: 'refused' } & Refusal);

export const applied = (model: MappingModel): CommandResult => ({ kind: 'applied', model });

export const refused = (code: BoxRefusal['code'], id: AuthoringId): CommandResult => ({ kind: 'refused', code, id });

export const refusedField = (side: RefusedField['side'], field: FieldPath, refusal: FieldRefusal): CommandResult => ({
  kind: 'refused',
  code: 'refused-field',
  side,
  field,
  refusal,
});

/** The refusal of a refused command's result: the result without its `kind`. */
export function refusalOf(result: { kind: 'refused' } & Refusal): Refusal {
  const { kind, ...refusal } = result;
  void kind;
  return refusal;
}

/** The id of the text of each refusal at a box, which takes the box's `id`. */
const BOX_REFUSALS: Readonly<Record<BoxRefusal['code'], string>> = {
  'box-cycle': 'mapping.refusal.cycle',
  'cross-target-field-reference': 'mapping.refusal.otherField',
  'duplicate-authoring-id': 'mapping.refusal.duplicate',
  'unknown-box-reference': 'mapping.refusal.unknownBox',
  'wrong-arity': 'mapping.refusal.noSlot',
  'context-not-ancestor': 'mapping.refusal.context',
  'unknown-parameter': 'mapping.refusal.unknownParameter',
  'wrong-parameter-type': 'mapping.refusal.parameterType',
  'invalid-parameter-value': 'mapping.refusal.parameterValue',
  'not-source-field-box': 'mapping.refusal.notSource',
};

/**
 * What the editor tells the author about a refusal, in the language of `texts`, by default the one the UI shows; a
 * refused field names its reason's code and the position of the declaration that causes it.
 */
export function describeRefusal(refusal: Refusal, { t }: Texts = textsIn(currentLanguage())): string {
  if (refusal.code === 'refused-field') {
    const { code, file, line, column } = refusal.refusal;
    return t(`mapping.refusal.field.${refusal.side}`, { field: refusal.field, code, file, line, column });
  }
  return t(BOX_REFUSALS[refusal.code], { id: refusal.id });
}

/**
 * Refuses a reference from the target field mapping of `targetField` to box `id` unless that mapping has the box:
 * `cross-target-field-reference` when another mapping has it, else `unknown-box-reference`.
 */
export function refuseReference(model: MappingModel, targetField: FieldPath, id: AuthoringId): CommandResult | undefined {
  if (Object.hasOwn(model.targetFieldMappings?.[targetField]?.boxes ?? {}, id)) return undefined;
  const elsewhere = Object.values(model.targetFieldMappings ?? {}).some((mapping) => Object.hasOwn(mapping.boxes, id));
  return refused(elsewhere ? 'cross-target-field-reference' : 'unknown-box-reference', id);
}

/** The input slots of a box of `mapping`; a source-field box has none. */
export function inputsOf(mapping: TargetFieldMapping, id: AuthoringId): BoxReference[] {
  const box = mapping.boxes[id]!;
  return box.kind === 'source' ? [] : (box.inputs ?? []);
}
