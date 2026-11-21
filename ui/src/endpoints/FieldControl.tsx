import { Form, Input, Select } from 'antd';
import { useId, useState, type ReactNode } from 'react';
import { KNOWN_TYPES, type SettingsField } from './settings';

export interface FieldControlProps {
  /** The field; a key of the file that no field describes comes as a field of no known type. */
  field: SettingsField;
  /** The option's value in the file or the draft; undefined when it is not set. */
  value: unknown;
  readOnly: boolean;
  /** Told the new value, or undefined when the option is cleared. */
  onChange: (value: unknown) => void;
  /** Told whether a raw editor holds text that is no JSON, which cannot be saved. */
  onInvalid: (invalid: boolean) => void;
  /** What the check finds wrong with the option, or undefined. */
  problem?: string;
  /** More to say under the field, after its help text. */
  note?: ReactNode;
}

const shown = (value: unknown) => (value === undefined || value === null ? '' : typeof value === 'string' ? value : JSON.stringify(value));

/** The text that stands in an empty control: the default when there is one. */
function placeholder(field: SettingsField): string | undefined {
  return field.default === undefined ? undefined : `Default: ${shown(field.default)}`;
}

/**
 * A whole number as the draft keeps it: a number when the text is one JavaScript holds exactly, else the text, which the
 * check refuses.
 */
function integer(text: string): unknown {
  if (text.trim() === '') return undefined;
  const n = Number(text.trim());
  return /^-?\d+$/.test(text.trim()) && Number.isSafeInteger(n) ? n : text;
}

/**
 * One option of an endpoint as a form group: its name as the label, its help text below, and the problem the check
 * finds. Read-only it shows the value as text, a secret masked. Editing, text and secrets are text inputs (empty is not
 * set), a whole number a text input that keeps what was typed, true or false and a choice a select with "Not set",
 * and a field of a type the form does not know a raw editor of its value as JSON.
 */
export function FieldControl({ field, value, readOnly, onChange, onInvalid, problem, note }: FieldControlProps) {
  const id = `${useId()}-${field.name}`;
  const known = KNOWN_TYPES.has(field.type);
  const [raw, setRaw] = useState<string | null>(null);
  const rawInvalid = raw !== null;
  const status = problem !== undefined || rawInvalid ? 'error' : undefined;
  const describedBy = `${id}-help`;

  const text = known ? shown(value) : value === undefined ? '' : JSON.stringify(value);

  let control;
  if (readOnly) {
    control = (
      <Input
        id={id}
        aria-label={field.name}
        readOnly
        type={field.type === 'secret' ? 'password' : 'text'}
        value={text}
        placeholder={placeholder(field) ?? 'Not set'}
        aria-describedby={describedBy}
        aria-invalid={status === 'error'}
        status={status}
      />
    );
  } else if (field.type === 'boolean' || field.type === 'choice') {
    const values = field.type === 'boolean' ? ['true', 'false'] : (field.choices ?? []);
    const unset = field.default === undefined ? 'Not set' : `Not set (default: ${shown(field.default)})`;
    control = (
      <Select
        id={id}
        aria-label={field.name}
        aria-describedby={describedBy}
        aria-invalid={status === 'error'}
        aria-required={field.required}
        status={status}
        value={shown(value)}
        virtual={false}
        options={[
          { value: '', label: unset },
          ...(value !== undefined && value !== null && !values.includes(shown(value)) ? [{ value: shown(value), label: shown(value) }] : []),
          ...values.map((v) => ({ value: v, label: v })),
        ]}
        onChange={(chosen) => onChange(chosen === '' ? undefined : field.type === 'boolean' ? chosen === 'true' : chosen)}
      />
    );
  } else if (known) {
    control = (
      <Input
        id={id}
        aria-label={field.name}
        type={field.type === 'secret' ? 'password' : 'text'}
        inputMode={field.type === 'integer' ? 'numeric' : undefined}
        value={shown(value)}
        placeholder={placeholder(field)}
        required={field.required}
        aria-describedby={describedBy}
        aria-invalid={status === 'error'}
        status={status}
        onChange={({ target: { value: text } }) => onChange(field.type === 'integer' ? integer(text) : text === '' ? undefined : text)}
      />
    );
  } else {
    control = (
      <Input
        id={id}
        aria-label={field.name}
        value={raw ?? text}
        aria-describedby={describedBy}
        aria-invalid={status === 'error'}
        status={status}
        onChange={({ target: { value: text } }) => {
          if (text.trim() === '') {
            setRaw(null);
            onInvalid(false);
            onChange(undefined);
            return;
          }
          try {
            onChange(JSON.parse(text));
            setRaw(null);
            onInvalid(false);
          } catch {
            setRaw(text);
            onInvalid(true);
          }
        }}
      />
    );
  }

  return (
    <Form.Item
      label={field.name}
      htmlFor={id}
      required={field.required}
      validateStatus={status}
      help={
        <div id={describedBy}>
          <div>{field.help}</div>
          {note !== undefined && <div>{note}</div>}
          {!known && <div>Written as JSON.</div>}
          {rawInvalid && <div>is not JSON</div>}
          {problem !== undefined && <div>{problem}</div>}
        </div>
      }
    >
      {control}
    </Form.Item>
  );
}
