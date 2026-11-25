import { Form, Input } from 'antd';
import { useId, useState } from 'react';
import { useText } from '../language';
import type { Options } from './settings';

export interface RawOptionsProps {
  options: Options;
  readOnly: boolean;
  /** Told the new options each time the text is a JSON object. */
  onChange: (options: Options) => void;
  /** Told whether the text is no JSON object, which cannot be saved. */
  onInvalid: (invalid: boolean) => void;
}

const isOptions = (value: unknown): value is Options => typeof value === 'object' && value !== null && !Array.isArray(value);

/** All of an endpoint's options as one JSON object, for a component that describes none of them. */
export function RawOptions({ options, readOnly, onChange, onInvalid }: RawOptionsProps) {
  const { t } = useText();
  const id = useId();
  const [text, setText] = useState(() => JSON.stringify(options, null, 2));
  const [invalid, setInvalid] = useState(false);
  return (
    <Form.Item
      label={t('endpoints.raw.label')}
      htmlFor={id}
      validateStatus={invalid ? 'error' : undefined}
      extra={<span id={`${id}-help`}>{t('endpoints.raw.help')}</span>}
      help={invalid && <span id={`${id}-error`}>{t('endpoints.raw.notObject')}</span>}
    >
      <Input.TextArea
        id={id}
        aria-label={t('endpoints.raw.label')}
        readOnly={readOnly}
        value={text}
        rows={Math.min(20, text.split('\n').length + 1)}
        style={{ resize: 'vertical' }}
        aria-describedby={invalid ? `${id}-help ${id}-error` : `${id}-help`}
        aria-invalid={invalid}
        onChange={({ target: { value: next } }) => {
          setText(next);
          let parsed: unknown;
          try {
            parsed = JSON.parse(next);
          } catch {
            parsed = undefined;
          }
          setInvalid(!isOptions(parsed));
          onInvalid(!isOptions(parsed));
          if (isOptions(parsed)) onChange(parsed);
        }}
      />
    </Form.Item>
  );
}
