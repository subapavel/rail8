import { BlockOutlined } from '@ant-design/icons';

const NUMERIC_TYPES = new Set([
  'number',
  'decimal',
  'float',
  'double',
  'integer',
  'int',
  'long',
  'short',
  'byte',
  'positiveInteger',
  'negativeInteger',
  'nonPositiveInteger',
  'nonNegativeInteger',
  'unsignedLong',
  'unsignedInt',
  'unsignedShort',
  'unsignedByte',
]);
const SYMBOLS: Record<string, string> = { string: 'T', boolean: 'T/F', array: '[ ]', null: '∅', variable: 'V' };

/** A field's type as an icon: a cube for a structure field, else a symbol of its value type in the monospace font. */
export function FieldTypeIcon({ type }: { type: string }) {
  if (type === 'structure') {
    return <BlockOutlined title={type} aria-hidden />;
  }
  return (
    <span title={type} aria-hidden="true" data-field-type={type} style={{ fontFamily: 'var(--ant-font-family-code)', fontSize: 'var(--ant-font-size-sm)' }}>
      {NUMERIC_TYPES.has(type) ? '#' : (SYMBOLS[type] ?? '·')}
    </span>
  );
}
