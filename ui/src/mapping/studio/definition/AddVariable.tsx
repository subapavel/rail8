import { Form, Input, Modal } from 'antd';
import { useId, useState } from 'react';
import type { FieldPath } from '../../generated/mapping-model';
import { localPath } from '../../model/field-path';

export interface AddVariableProps {
  /** The structure field the variable goes under. */
  parent: FieldPath;
  /** Whether a path is in use: a variable's or a field's. */
  taken: (path: FieldPath) => boolean;
  create: (name: string) => void;
  close: () => void;
}

const NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;

/**
 * The Add Variable dialog: a name of a letter or `_` and then letters, digits or `_`, at most 90 characters, one the
 * parent does not use yet.
 */
export function AddVariable({ parent, taken, create, close }: AddVariableProps) {
  const [name, setName] = useState('');
  const formId = useId();
  const value = name.trim();
  const valid = NAME.test(value) && value.length <= 90;
  const used = valid && taken(`${parent}/$${value}`);
  return (
    <Modal open title="Add Variable" onCancel={close} okText="Create" okButtonProps={{ htmlType: 'submit', form: formId, disabled: !valid || used }}>
      <p>/{localPath(parent)}</p>
      <Form
        layout="vertical"
        id={formId}
        onFinish={() => {
          if (valid && !used) create(value);
        }}
      >
        <Form.Item
          label="Variable name"
          htmlFor={`${formId}-name`}
          required
          validateStatus={value && (!valid || used) ? 'error' : undefined}
          help={<span id={`${formId}-help`}>{used ? 'This field already has a variable or a child of that name.' : 'A letter or _, then letters, digits or _.'}</span>}
        >
          <Input
            id={`${formId}-name`}
            aria-label="Variable name"
            required
            maxLength={90}
            value={name}
            aria-describedby={`${formId}-help`}
            aria-invalid={Boolean(value && (!valid || used))}
            onChange={(event) => setName(event.target.value)}
          />
        </Form.Item>
      </Form>
      <small>A preview: the variable is kept in this browser only, never in the mapping file.</small>
    </Modal>
  );
}
