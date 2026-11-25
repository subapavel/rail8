import { Form, Input } from 'antd';
import { MountedModal } from '../../../MountedModal';
import { useId, useState } from 'react';
import { useText } from '../../../language';
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
  const { t } = useText();
  return (
    <MountedModal title={t('studio.variable.add')} onCancel={close} okText={t('mapping.new.create')} okButtonProps={{ htmlType: 'submit', form: formId, disabled: !valid || used }}>
      <p>/{localPath(parent)}</p>
      <Form
        layout="vertical"
        id={formId}
        onFinish={() => {
          if (valid && !used) create(value);
        }}
      >
        <Form.Item
          label={t('studio.variable.name')}
          htmlFor={`${formId}-name`}
          required
          validateStatus={value && (!valid || used) ? 'error' : undefined}
          help={<span id={`${formId}-help`}>{used ? t('studio.variable.taken') : t('studio.variable.rule')}</span>}
        >
          <Input
            id={`${formId}-name`}
            aria-label={t('studio.variable.name')}
            required
            maxLength={90}
            value={name}
            aria-describedby={`${formId}-help`}
            aria-invalid={Boolean(value && (!valid || used))}
            onChange={(event) => setName(event.target.value)}
          />
        </Form.Item>
      </Form>
      <small>{t('studio.variable.preview')}</small>
    </MountedModal>
  );
}
