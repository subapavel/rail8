import { Alert, Form, Input, Modal, Select } from 'antd';
import { UploadOutlined, FileAddOutlined, SaveOutlined, DeleteOutlined } from '@ant-design/icons';
import { useId, useRef, useState, type ChangeEvent } from 'react';
import { useStore } from 'zustand';
import { Tool } from '../shared/Tool';
import { PreviewBadge } from './PreviewBadge';
import {
  currentInstance,
  instanceActions,
  instanceChoice,
  instancesOf,
  readInstance,
  TEST_INSTANCES_ISSUE,
  type InstanceFormat,
  type InstanceResource,
  type TestInstancesStore,
} from './test-instances';
import { parseDocument } from '../shared/documents';

export interface InstanceToolsProps {
  library: TestInstancesStore;
  format: InstanceFormat;
  /** What Add and Save take, read when they are clicked: the source document and the parameter values as they are then. */
  resource: () => InstanceResource;
  /** Loads a document read from a file as the source document. */
  onLoad: (text: string) => void;
  /** Whether the studio is in edit mode: Load, Add, Save and Delete are edit-only; the select chooses in read mode too. */
  editing: boolean;
  /** Whether every tool, the select too, is disabled, as while a run is under way. */
  disabled: boolean;
  /** Whether a file read must parse as the format before it loads (the queue test input). */
  validateFile?: boolean;
  selectLabel?: string;
}

type Dialog = 'add' | 'delete' | null;

/**
 * The test instance tools (a preview): Load Test Instance reads a file or the
 * `instance.<format>` of a zip into the source document; Add, Save and Delete Test Instance and the select change the
 * mapping's test instance library, a draft of the edit session, in edit mode. The test session follows the
 * current instance. In read mode only the select works: it shows the instance chosen in read mode, else the current one,
 * and a choice loads it into the source without changing the library.
 */
export function InstanceTools({
  library,
  format,
  resource,
  onLoad,
  editing,
  disabled,
  validateFile = false,
  selectLabel = 'Select test instance',
}: InstanceToolsProps) {
  const list = useStore(library, (s) => s.value);
  const chosen = useStore(instanceChoice(library), (s) => s.id);
  const entries = instancesOf(list, format);
  const current = currentInstance(list, format);
  // edit mode shows the current instance, the one Save and Delete change
  const shown = editing ? current : (entries.find((i) => i.id === chosen) ?? current);
  const actions = instanceActions(library, format);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [name, setName] = useState('');
  const [notice, setNotice] = useState('');
  const [reading, setReading] = useState(false);
  const fieldId = useId();
  const file = useRef<HTMLInputElement>(null);
  // the file input is disabled while a file is read, so one read is under way at most
  const busy = disabled || reading;
  const locked = busy || !editing;

  const load = async (event: ChangeEvent<HTMLInputElement>) => {
    // the input is cleared after each read, so a change always brings a file
    const chosen = event.target.files![0]!;
    event.target.value = '';
    setReading(true);
    setNotice('');
    try {
      const text = await readInstance(chosen, format);
      if (validateFile) parseDocument(text, format);
      onLoad(text);
    } catch {
      setNotice(`Could not read this file. Choose a valid ${format} test input.`);
    } finally {
      setReading(false);
    }
  };
  const add = () => {
    if (!name.trim()) return;
    try {
      actions.create(name.trim(), resource());
      setNotice('');
      setDialog(null);
    } catch (e) {
      setNotice((e as Error).message);
    }
  };
  const close = () => {
    setDialog(null);
    setNotice('');
  };

  return (
    <>
      <PreviewBadge feature="Test instances" issue={TEST_INSTANCES_ISSUE} />
      <Tool label="Load Test Instance" icon={UploadOutlined} disabled={locked} onClick={() => file.current?.click()} />
      <Tool
        label="Add Test Instance"
        icon={FileAddOutlined}
        disabled={locked}
        onClick={() => {
          setName('');
          setNotice('');
          setDialog('add');
        }}
      />
      <Tool
        label="Save Test Instance"
        icon={SaveOutlined}
        disabled={locked || !current}
        onClick={() => {
          actions.save(resource());
          setNotice('Test instance saved. Save the mapping to keep it in this browser.');
        }}
      />
      <Tool
        label="Delete Test Instance"
        icon={DeleteOutlined}
        disabled={locked || !current}
        onClick={() => {
          setNotice('');
          setDialog('delete');
        }}
      />
      <Select
        virtual={entries.length > 100}
        aria-label={selectLabel}
        value={shown?.id ?? ''}
        disabled={busy || !current}
        onChange={(value) => (editing ? actions.select(value) : instanceChoice(library).getState().choose(value))}
        options={entries.map((i) => ({ value: i.id, label: i.name }))}
      />
      <input
        ref={file}
        type="file"
        hidden
        disabled={locked}
        aria-label="Test instance file"
        accept={`.${format.toLowerCase()},.zip`}
        onChange={(e) => void load(e)}
      />
      {notice && !dialog && <small role="status">{notice}</small>}

      {dialog === 'add' && (
        <Modal
          title="Add Test Instance"
          open
          onCancel={close}
          cancelText="Cancel"
          okText="OK"
          okButtonProps={{ htmlType: 'submit', form: fieldId + '-form', disabled: !name.trim() }}
        >
          <Form layout="vertical" id={fieldId + '-form'} onFinish={add}>
            <Form.Item label="Test instance name" htmlFor={fieldId}>
              <Input id={fieldId} autoFocus maxLength={100} value={name} onChange={(event) => setName(event.target.value)} />
            </Form.Item>
            {notice && <Alert type="error" title={notice} />}
          </Form>
        </Modal>
      )}
      {dialog === 'delete' && current && (
        <Modal
          title="Delete Test Instance"
          open
          onCancel={close}
          cancelText="Cancel"
          okText="Delete"
          okButtonProps={{ danger: true }}
          onOk={() => {
            actions.remove();
            setDialog(null);
          }}
        >
          Delete {current.name} from this browser?
        </Modal>
      )}
    </>
  );
}
