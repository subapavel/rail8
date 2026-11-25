import { Form, Input, Select, Typography } from 'antd';
import { useId, useState, type ReactNode } from 'react';
import { say, useText, type Texts } from '../language';
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
function placeholder({ t }: Texts, field: SettingsField): string | undefined {
  return field.default === undefined ? undefined : t('endpoints.field.default', { value: shown(field.default) });
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
 * One option of an endpoint as a form group: its label with its name beside it, its help text below, and the problem
 * the check finds. Read-only it shows the value as text, a secret masked and a choice by its label. Editing, text and secrets are text inputs (empty is not
 * set), a whole number a text input that keeps what was typed, true or false and a choice a select with "Not set",
 * and a field of a type the form does not know a raw editor of its value as JSON.
 */
export function FieldControl({ field, value, readOnly, onChange, onInvalid, problem, note }: FieldControlProps) {
  const texts = useText();
  const { t } = texts;
  const id = `${useId()}-${field.name}`;
  const known = KNOWN_TYPES.has(field.type);
  const [raw, setRaw] = useState<string | null>(null);
  const rawInvalid = raw !== null;
  const status = problem !== undefined || rawInvalid ? 'error' : undefined;
  const describedBy = `${id}-help`;

  const text = known ? shown(value) : value === undefined ? '' : JSON.stringify(value);
  const label = field.label === undefined ? field.name : say(texts, field.label);
  const choiceLabel = (choice: string) => field.choiceLabels?.[choice] ?? choice;

  let control;
  if (readOnly) {
    control = (
      <Input
        id={id}
        aria-label={label}
        readOnly
        type={field.type === 'secret' ? 'password' : 'text'}
        value={field.type === 'choice' && typeof value === 'string' ? choiceLabel(value) : text}
        placeholder={placeholder(texts, field) ?? t('endpoints.field.notSet')}
        aria-describedby={describedBy}
        aria-invalid={status === 'error'}
        status={status}
      />
    );
  } else if (field.type === 'boolean' || field.type === 'choice') {
    const values = field.type === 'boolean' ? ['true', 'false'] : (field.choices ?? []);
    const unset = field.default === undefined ? t('endpoints.field.notSet') : t('endpoints.field.notSetDefault', { value: shown(field.default) });
    control = (
      <Select
        id={id}
        aria-label={label}
        aria-describedby={describedBy}
        aria-invalid={status === 'error'}
        aria-required={field.required}
        status={status}
        value={shown(value)}
        virtual={false}
        options={[
          { value: '', label: unset },
          ...(value !== undefined && value !== null && !values.includes(shown(value)) ? [{ value: shown(value), label: shown(value) }] : []),
          ...values.map((v) => ({ value: v, label: choiceLabel(v) })),
        ]}
        onChange={(chosen) => onChange(chosen === '' ? undefined : field.type === 'boolean' ? chosen === 'true' : chosen)}
      />
    );
  } else if (known) {
    control = (
      <Input
        id={id}
        aria-label={label}
        type={field.type === 'secret' ? 'password' : 'text'}
        inputMode={field.type === 'integer' ? 'numeric' : undefined}
        value={shown(value)}
        placeholder={placeholder(texts, field)}
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
        aria-label={label}
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
      label={
        label === field.name ? (
          label
        ) : (
          <>
            {label}&nbsp;<Typography.Text type="secondary">{field.name}</Typography.Text>
          </>
        )
      }
      htmlFor={id}
      required={field.required}
      validateStatus={status}
      help={
        <div id={describedBy}>
          <div>{say(texts, field.help)}</div>
          {note !== undefined && <div>{note}</div>}
          {!known && <div>{t('endpoints.field.json')}</div>}
          {rawInvalid && <div>{t('endpoints.field.notJson')}</div>}
          {problem !== undefined && <div>{problem}</div>}
        </div>
      }
    >
      {control}
    </Form.Item>
  );
}
