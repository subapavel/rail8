import { Alert, Form, Input } from 'antd';
import { MountedModal } from '../../MountedModal';
import { useMutation } from '@tanstack/react-query';
import { useId, useState } from 'react';
import { describeError, type ApiClient, type CreateMappingRequest } from '../api/client';

// A workspace path segment as the service accepts it: the service checks again and refuses reserved names.
const SEGMENT = /^[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}$/;
/** An id has at most 16 segments: the name and at most 15 folders. */
const MAX_FOLDERS = 15;
/** A description of at most 4,000 code points. */
const MAX_DESCRIPTION = 4000;

const count = (n: number) => n.toLocaleString('en-US');

function nameError(name: string): string | undefined {
  return SEGMENT.test(name) ? undefined : 'Use letters, digits, ".", "_" and "-", up to 64, not starting with ".".';
}

function packageError(pkg: string): string | undefined {
  if (pkg === '') return undefined;
  const folders = pkg.split('/');
  if (!folders.every((folder) => SEGMENT.test(folder))) return 'Use folder names of letters, digits, ".", "_" and "-", separated by "/".';
  return folders.length > MAX_FOLDERS ? `Use at most ${MAX_FOLDERS} folders.` : undefined;
}

function descriptionError(description: string): string | undefined {
  const length = [...description].length;
  return length > MAX_DESCRIPTION ? `Use at most ${count(MAX_DESCRIPTION)} characters; this has ${count(length)}.` : undefined;
}

export interface NewMappingModalProps {
  api: ApiClient;
  onClose: () => void;
  /** Told the new mapping's id once the service created it. */
  onCreated: (id: string) => void;
}

/**
 * The New mapping modal of the start page: Name, Package and Description, and no message choice; the messages are
 * chosen on the Signature tab. The id is the package's folders and the name. Each field says
 * what it allows; the service checks again, and its refusal (400, 409 `mapping-exists`) shows above the fields.
 */
export function NewMappingModal({ api, onClose, onCreated }: NewMappingModalProps) {
  const [name, setName] = useState('');
  const [pkg, setPkg] = useState('');
  const [description, setDescription] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const create = useMutation({
    mutationFn: (request: CreateMappingRequest) => api.createMapping(request),
    onSuccess: (_, request) => onCreated(request.id),
  });
  const failure = create.isError ? describeError(create.error) : undefined;
  const ids = { form: useId(), name: useId(), pkg: useId(), text: useId() };

  const trimmedName = name.trim();
  const trimmedPkg = pkg.trim();
  // an empty name is asked for only once Create was chosen; any other error shows while typing
  const errors = {
    name: trimmedName === '' ? (submitted ? 'Enter a name.' : undefined) : nameError(trimmedName),
    pkg: packageError(trimmedPkg),
    description: descriptionError(description),
  };

  const submit = () => {
    setSubmitted(true);
    if (create.isPending || trimmedName === '' || errors.name || errors.pkg || errors.description) return;
    create.mutate({ id: trimmedPkg === '' ? trimmedName : `${trimmedPkg}/${trimmedName}`, ...(description === '' ? {} : { description }) });
  };

  const fields = [
    { id: ids.name, label: 'Name', value: name, change: setName, error: errors.name, hint: 'The file name of the mapping in its package.' },
    {
      id: ids.pkg,
      label: 'Package',
      value: pkg,
      change: setPkg,
      error: errors.pkg,
      hint: 'The workspace folder, such as orders/inbound; empty for the top folder.',
    },
    {
      id: ids.text,
      label: 'Description',
      value: description,
      change: setDescription,
      error: errors.description,
      hint: `Up to ${count(MAX_DESCRIPTION)} characters.`,
    },
  ];
  return (
    <MountedModal
      title="New mapping"
      onCancel={onClose}
      okText="Create"
      cancelText="Cancel"
      confirmLoading={create.isPending}
      okButtonProps={{ 'aria-label': 'Create', form: ids.form, htmlType: 'submit', disabled: create.isPending }}
    >
      <p>Choose its source and target messages on the Signature tab once it is created.</p>
      {failure && <Alert type="error" showIcon title={<h3>{failure.title}</h3>} description={failure.detail} />}
      <Form colon={false} id={ids.form} layout="vertical" onFinish={submit}>
        {fields.map(({ id, label, value, change, error, hint }) => {
          const Control = id === ids.text ? Input.TextArea : Input;
          return (
            <Form.Item
              key={id}
              label={label}
              htmlFor={id}
              required={id === ids.name}
              validateStatus={error ? 'error' : undefined}
              help={<span id={id + '-help'}>{error ?? hint}</span>}
            >
              <Control
                aria-label={label}
                id={id}
                value={value}
                aria-required={id === ids.name}
                aria-invalid={!!error}
                aria-describedby={id + '-help'}
                onChange={(event) => change(event.target.value)}
              />
            </Form.Item>
          );
        })}
      </Form>
    </MountedModal>
  );
}
