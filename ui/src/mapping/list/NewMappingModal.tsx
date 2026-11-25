import { Alert, Form, Input } from 'antd';
import { MountedModal } from '../../MountedModal';
import { useMutation } from '@tanstack/react-query';
import { useId, useState } from 'react';
import { useText, type Texts } from '../../language';
import { describeError, type ApiClient, type CreateMappingRequest } from '../api/client';

// A workspace path segment as the service accepts it: the service checks again and refuses reserved names.
const SEGMENT = /^[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}$/;
/** An id has at most 16 segments: the name and at most 15 folders. */
const MAX_FOLDERS = 15;
/** A description of at most 4,000 code points. */
const MAX_DESCRIPTION = 4000;

function nameError(name: string, { t }: Texts): string | undefined {
  return SEGMENT.test(name) ? undefined : t('mapping.new.name.invalid');
}

function packageError(pkg: string, { t }: Texts): string | undefined {
  if (pkg === '') return undefined;
  const folders = pkg.split('/');
  if (!folders.every((folder) => SEGMENT.test(folder))) return t('mapping.new.package.invalid');
  return folders.length > MAX_FOLDERS ? t('mapping.new.package.tooDeep', { max: MAX_FOLDERS }) : undefined;
}

function descriptionError(description: string, { t }: Texts): string | undefined {
  const length = [...description].length;
  return length > MAX_DESCRIPTION ? t('mapping.new.description.tooLong', { max: MAX_DESCRIPTION, length }) : undefined;
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
  const texts = useText();
  const { t } = texts;

  const trimmedName = name.trim();
  const trimmedPkg = pkg.trim();
  // an empty name is asked for only once Create was chosen; any other error shows while typing
  const errors = {
    name: trimmedName === '' ? (submitted ? t('mapping.new.name.required') : undefined) : nameError(trimmedName, texts),
    pkg: packageError(trimmedPkg, texts),
    description: descriptionError(description, texts),
  };

  const submit = () => {
    setSubmitted(true);
    if (create.isPending || trimmedName === '' || errors.name || errors.pkg || errors.description) return;
    create.mutate({ id: trimmedPkg === '' ? trimmedName : `${trimmedPkg}/${trimmedName}`, ...(description === '' ? {} : { description }) });
  };

  const fields = [
    { id: ids.name, label: t('mapping.new.name'), value: name, change: setName, error: errors.name, hint: t('mapping.new.name.hint') },
    {
      id: ids.pkg,
      label: t('mapping.new.package'),
      value: pkg,
      change: setPkg,
      error: errors.pkg,
      hint: t('mapping.new.package.hint'),
    },
    {
      id: ids.text,
      label: t('mapping.new.description'),
      value: description,
      change: setDescription,
      error: errors.description,
      hint: t('mapping.new.description.hint', { max: MAX_DESCRIPTION }),
    },
  ];
  return (
    <MountedModal
      title={t('mapping.list.new')}
      onCancel={onClose}
      okText={t('mapping.new.create')}
      cancelText={t('frame.cancel')}
      confirmLoading={create.isPending}
      okButtonProps={{ 'aria-label': t('mapping.new.create'), form: ids.form, htmlType: 'submit', disabled: create.isPending }}
    >
      <p>{t('mapping.new.intro')}</p>
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
