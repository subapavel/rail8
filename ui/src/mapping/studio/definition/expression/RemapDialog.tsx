import { Form, Select } from 'antd';
import { MountedModal } from '../../../../MountedModal';
import { useState } from 'react';
import { useText } from '../../../../language';
import type { FieldPath } from '../../../generated/mapping-model';
import { localPath, stepName } from '../../../model/field-path';

export interface RemapDialogProps {
  /** The field the orphaned box reads, which the source structure lacks. */
  field: FieldPath;
  /** The source fields it can read instead, in tree order. */
  choices: readonly FieldPath[];
  remap: (field: FieldPath) => void;
  close: () => void;
}

/**
 * The remap of an orphaned source-field box: a field of the source structure, chosen from all of them in tree order;
 * the box keeps its authoring id and its wires.
 */
export function RemapDialog({ field, choices, remap, close }: RemapDialogProps) {
  const [choice, setChoice] = useState('');
  const { t } = useText();
  const title = t('studio.remap.title', { name: stepName(field) });
  return (
    <MountedModal title={title} onCancel={close} okText={t('studio.canvas.remap')} okButtonProps={{ htmlType: 'submit', form: 'remap-form', disabled: !choice }}>
      <Form layout="vertical" id="remap-form" onFinish={() => remap(choice)}>
        <p>{t('studio.remap.why', { path: `/${localPath(field)}` })}</p>
        <Form.Item label={t('studio.role.source')} htmlFor="remap-source-field">
          <Select
            id="remap-source-field"
            aria-label={t('studio.role.source')}
            showSearch={{ optionFilterProp: 'label' }}
            placeholder={t('studio.remap.choose')}
            value={choice || undefined}
            onChange={setChoice}
            options={choices.map((path) => ({ value: path, label: `/${localPath(path)}` }))}
          />
        </Form.Item>
      </Form>
    </MountedModal>
  );
}
