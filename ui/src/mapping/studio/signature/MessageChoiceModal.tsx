import { Alert, Form, Input, Modal, Select } from 'antd';
import { useId, useState } from 'react';
import type { Diagnostic } from '../../api/client';
import { choiceOf, draftOf, ROOT_KINDS, type ChoiceDraft, type Direction } from './message-choice';
import type { MessageChoice, RootKind } from '../shared/structure-names';

/** Why a choice was not applied: a title, and the diagnostics of a structure the service refused. */
export interface ChoiceFailure {
  title: string;
  diagnostics: Diagnostic[];
}

export interface MessageChoiceModalProps {
  title: string;
  description: string;
  /** The message the row has, whose bundle and root the fields start from. */
  initial?: MessageChoice;
  /** The root kind the row's Type select asked for, replacing the initial one. */
  kind?: RootKind;
  /** Applies the choice; resolves to why it was not applied, or `null` once it was, and the caller closes the dialog. */
  onApply: (choice: MessageChoice) => Promise<ChoiceFailure | null>;
  onClose: () => void;
}

/**
 * The message choice of the Signature tab, in a catalog dialog: the bundle, one reference per line relative to the
 * mapping's package, and the root selector. Apply is enabled once the bundle and the root's names are given; a failure
 * shows above the fields.
 */
export function MessageChoiceModal({ title, description, initial, kind, onApply, onClose }: MessageChoiceModalProps) {
  const [draft, setDraft] = useState(() => draftOf(initial, kind));
  const [applying, setApplying] = useState(false);
  const [failure, setFailure] = useState<ChoiceFailure | null>(null);
  const formId = useId();
  const choice = choiceOf(draft);
  const set = (change: Partial<ChoiceDraft>) => setDraft((current) => ({ ...current, ...change }));
  const fieldId = (name: string) => `${formId}-${name}`;

  // A form submits only by its default button, Apply, also on Enter (HTML implicit submission); Apply is disabled
  // without a choice and while one is applied.
  const submit = async () => {
    if (!choice || applying) return;
    setApplying(true);
    setFailure(null);
    const failed = await onApply(choice);
    setApplying(false);
    setFailure(failed);
  };

  const text = (name: string, label: string, value: string, change: (value: string) => void) => (
    <Form.Item label={label} htmlFor={fieldId(name)}>
      <Input aria-label={label} id={fieldId(name)} value={value} onChange={({ target: { value: next } }) => change(next)} />
    </Form.Item>
  );

  return (
    <Modal
      open
      title={title}
      onCancel={onClose}
      cancelText="Cancel"
      okText="Apply"
      confirmLoading={applying}
      okButtonProps={{ 'aria-label': 'Apply', form: formId, htmlType: 'submit', disabled: !choice || applying }}
    >
      <p>{description}</p>
      <Form colon={false} layout="vertical" id={formId} onFinish={submit}>
        {failure && (
          <Alert
            type="error"
            showIcon
            title={<h3>{failure.title}</h3>}
            description={
              <ul aria-label="Message diagnostics">
                {failure.diagnostics.map(({ severity, code, message }, i) => (
                  <li key={i}>
                    {severity} <code>{code}</code>: {message}
                  </li>
                ))}
              </ul>
            }
          />
        )}
        <Form.Item label="Bundle" htmlFor={fieldId('bundle')}>
          <Input.TextArea
            aria-label="Bundle"
            id={fieldId('bundle')}
            value={draft.bundle}
            onChange={(e) => set({ bundle: e.target.value })}
            placeholder="One path per line, relative to the mapping's package"
          />
        </Form.Item>
        <Form.Item label="Root" htmlFor={fieldId('kind')}>
          <Select
            aria-label="Root"
            id={fieldId('kind')}
            virtual={false}
            value={draft.kind}
            options={ROOT_KINDS.map(([value, label]) => ({ value, label }))}
            onChange={(kind) => set({ kind })}
          />
        </Form.Item>
        {draft.kind === 'element' && text('element', 'Root element', draft.element, (element) => set({ element }))}
        {draft.kind === 'wsdl' && (
          <>
            {text('interface', 'Interface', draft.wsdlInterface, (wsdlInterface) => set({ wsdlInterface }))}
            {text('operation', 'Operation', draft.operation, (operation) => set({ operation }))}
            <Form.Item label="Direction" htmlFor={fieldId('direction')}>
              <Select
                aria-label="Direction"
                id={fieldId('direction')}
                virtual={false}
                value={draft.direction}
                options={['input', 'output', 'fault'].map((value) => ({ value, label: value }))}
                onChange={(direction: Direction) => set({ direction })}
              />
            </Form.Item>
            {draft.direction === 'fault' && text('fault', 'Fault', draft.fault, (fault) => set({ fault }))}
          </>
        )}
      </Form>
    </Modal>
  );
}
