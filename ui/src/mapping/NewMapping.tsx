import { PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Form, Input, Space } from 'antd';
import { useId, useState } from 'react';
import type { NewArtifactProps } from '../artifacts';
import { useText, type Texts } from '../language';
import { MountedModal } from '../MountedModal';
import { describeError, type ApiClient } from './api/client';

// A workspace path segment as the service accepts it: the service checks again and refuses reserved names.
const SEGMENT = /^[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}$/;
/** An id has at most 16 segments: the name and at most 15 folders. */
const MAX_FOLDERS = 15;
/** A description of at most 4,000 code points. */
const MAX_DESCRIPTION = 4000;

function nameError(name: string, { t }: Texts): string | undefined {
  return SEGMENT.test(name) ? undefined : t('mapping.new.name.invalid');
}

/** Why the folders typed below the top folder cannot hold a mapping; the top folder counts as one of the folders. */
function foldersError(typed: string, { t }: Texts): string | undefined {
  if (typed === '') return undefined;
  const folders = typed.split('/');
  if (!folders.every((folder) => SEGMENT.test(folder))) return t('mapping.new.package.invalid');
  return folders.length + 1 > MAX_FOLDERS ? t('mapping.new.package.tooDeep', { max: MAX_FOLDERS - 1 }) : undefined;
}

function descriptionError(description: string, { t }: Texts): string | undefined {
  const length = [...description].length;
  return length > MAX_DESCRIPTION ? t('mapping.new.description.tooLong', { max: MAX_DESCRIPTION, length }) : undefined;
}

/**
 * New mapping, and the modal it opens: Name, Folder and Description, and no message choice; the messages are chosen
 * on the Signature tab. The mapping goes in `folder`, in the folders typed below it, which start as those of the
 * mapping `beside`, else `mappings`; its id is `folder`, those folders and the name. Each field says what it allows;
 * the service checks again, and its refusal (400, 409 `mapping-exists`) shows above the fields. Once the service
 * created it, `onCreated` is told, then `openMapping` is given its id.
 */
export function NewMapping({ api, folder, beside, onCreated, openMapping }: Pick<NewArtifactProps, 'folder' | 'beside' | 'onCreated'> & {
  api: ApiClient;
  openMapping: (id: string) => void;
}) {
  const within = beside === undefined ? 'mappings' : beside.slice(folder.length + 1, Math.max(beside.lastIndexOf('/'), folder.length + 1));
  const [creating, setCreating] = useState(false);
  const { t } = useText();
  return (
    <>
      <Button icon={<PlusOutlined aria-hidden />} onClick={() => setCreating(true)}>
        {t('mapping.list.new')}
      </Button>
      {creating && (
        <NewMappingModal
          api={api}
          folder={folder}
          within={within}
          onClose={() => setCreating(false)}
          onCreated={(id) => {
            setCreating(false);
            onCreated();
            openMapping(id);
          }}
        />
      )}
    </>
  );
}

/** The modal of {@link NewMapping}, its folders below `folder` starting as `within`. */
function NewMappingModal({ api, folder, within, onClose, onCreated }: { api: ApiClient; folder: string; within: string; onClose: () => void; onCreated: (id: string) => void }) {
  const [name, setName] = useState('');
  const [pkg, setPkg] = useState(within);
  const [description, setDescription] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(undefined);
  const failure = error === undefined ? undefined : describeError(error);
  const ids = { form: useId(), name: useId(), pkg: useId(), text: useId() };
  const texts = useText();
  const { t } = texts;

  const trimmedName = name.trim();
  const trimmedPkg = pkg.trim();
  // an empty name is asked for only once Create was chosen; any other error shows while typing
  const errors = {
    name: trimmedName === '' ? (submitted ? t('mapping.new.name.required') : undefined) : nameError(trimmedName, texts),
    pkg: foldersError(trimmedPkg, texts),
    description: descriptionError(description, texts),
  };

  const submit = async () => {
    setSubmitted(true);
    if (pending || trimmedName === '' || errors.name || errors.pkg || errors.description) return;
    const id = [folder, ...(trimmedPkg === '' ? [] : [trimmedPkg]), trimmedName].join('/');
    setPending(true);
    setError(undefined);
    try {
      await api.createMapping({ id, ...(description === '' ? {} : { description }) });
      onCreated(id);
    } catch (refused) {
      setError(refused);
      setPending(false);
    }
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
      confirmLoading={pending}
      okButtonProps={{ 'aria-label': t('mapping.new.create'), form: ids.form, htmlType: 'submit', disabled: pending }}
    >
      <p>{t('mapping.new.intro')}</p>
      {failure && <Alert type="error" showIcon title={<h3>{failure.title}</h3>} description={failure.detail} />}
      <Form colon={false} id={ids.form} layout="vertical" onFinish={() => void submit()}>
        {fields.map(({ id, label, value, change, error, hint }) => {
          const Control = id === ids.text ? Input.TextArea : Input;
          const control = (
            <Control
              aria-label={label}
              id={id}
              value={value}
              aria-required={id === ids.name}
              aria-invalid={!!error}
              aria-describedby={id + '-help'}
              onChange={(event) => change(event.target.value)}
            />
          );
          return (
            <Form.Item
              key={id}
              label={label}
              htmlFor={id}
              required={id === ids.name}
              validateStatus={error ? 'error' : undefined}
              help={<span id={id + '-help'}>{error ?? hint}</span>}
            >
              {id === ids.pkg ? (
                <Space.Compact block>
                  <Space.Addon>{`${folder}/`}</Space.Addon>
                  {control}
                </Space.Compact>
              ) : (
                control
              )}
            </Form.Item>
          );
        })}
      </Form>
    </MountedModal>
  );
}
