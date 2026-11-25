import { Alert, Form, Input, Space } from 'antd';
import { useEffect, useId, useState } from 'react';
import { useText } from '../language';
import { MountedModal } from '../MountedModal';
import { ReadSpinner } from '../ReadSpinner';
import type { PackagesApi, PackageState } from './api';
import { refusedIn } from './refusals';
import { folderFieldsOf, folderPathRefused, namespaceRefused, namespaceTaken, type Refused } from './rules';

/** The folder the dialog changes, by its path within the package, with its `folder.yaml` when it has one; or, for a new folder, the folder it goes in (`''`: the package's root). */
export type FolderDialogFor = { edit: string; settings?: string } | { under: string };

/**
 * The dialog of a folder of the package `repository`: New folder, with the new folder's name below the folder `under`,
 * or the folder `edit`; for either, its namespace and its description, which Create or Save writes to the folder's
 * `folder.yaml` through `api`, making the folder when it is new. An existing `folder.yaml`, at the path `settings`, is
 * read first, so the write states the version the author saw. Each field says what the package's rules refuse as the
 * author types: a name that is no folder's name, a folder deeper than four, a namespace that is empty, too long or the
 * namespace of another folder of `namespaces` (each folder's path within the package and its namespace); the git half
 * checks again, and its refusal shows above the fields. `onState` is told the package's state after the write, and
 * `onWritten` the folder's path within the package.
 */
export function FolderDialog({ api, repository, of, namespaces, onState, onWritten, onClose }: {
  api: PackagesApi;
  repository: string;
  of: FolderDialogFor;
  namespaces: readonly { inside: string; namespace?: string }[];
  onState: (state: PackageState | null) => void;
  onWritten: (inside: string) => void;
  onClose: () => void;
}) {
  const texts = useText();
  const { t } = texts;
  const editing = 'edit' in of;
  // the tag of the folder.yaml the write replaces (null: none), once it is read
  const [read, setRead] = useState<{ tag: string | null } | 'reading' | { failed: string }>(editing && of.settings !== undefined ? 'reading' : { tag: null });
  const [name, setName] = useState('');
  const [namespace, setNamespace] = useState('');
  const [description, setDescription] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const ids = { form: useId(), name: useId(), namespace: useId(), description: useId() };
  const settings = editing ? of.settings : undefined;

  useEffect(() => {
    if (settings === undefined) return;
    let current = true;
    api.file(repository, 'work', settings).then(
      (version) => {
        if (!current) return;
        const fields = folderFieldsOf('text' in version ? version.text : '');
        setNamespace(fields.namespace ?? '');
        setDescription(fields.description ?? '');
        setRead({ tag: version.etag });
      },
      (error: unknown) => current && setRead({ failed: error instanceof Error ? error.message : String(error) }),
    );
    return () => {
      current = false;
    };
  }, [api, repository, settings]);

  const under = 'under' in of ? of.under : '';
  const inside = editing ? of.edit : [under, name.trim()].filter((part) => part !== '').join('/');
  const refusalText = (refused: Refused | null) => (refused === null ? undefined : refusedIn(texts, refused));
  const errors = {
    name: editing ? undefined : name.trim() === '' ? (submitted ? t('packages.folderDialog.name.required') : undefined) : refusalText(folderPathRefused(inside)),
    namespace: namespace === '' ? undefined : refusalText(namespaceRefused(namespace) ?? namespaceTaken(namespace, inside, namespaces)),
  };

  const submit = async () => {
    setSubmitted(true);
    if (pending || typeof read !== 'object' || 'failed' in read || (!editing && name.trim() === '') || errors.name || errors.namespace) return;
    setPending(true);
    setFailure(null);
    try {
      const answer = await api.folder(repository, {
        path: `${repository}/${inside}`,
        tag: read.tag,
        namespace: namespace === '' ? null : namespace,
        description: description === '' ? null : description,
      });
      onState(answer.package);
      onWritten(inside);
    } catch (refused) {
      setFailure(refused instanceof Error ? refused.message : String(refused));
      setPending(false);
    }
  };

  const field = (id: string, label: string, value: string, change: (value: string) => void, error: string | undefined, hint: string, area = false) => {
    const Control = area ? Input.TextArea : Input;
    return (
      <Form.Item label={label} htmlFor={id} required={id === ids.name} validateStatus={error ? 'error' : undefined} help={<span id={`${id}-help`}>{error ?? hint}</span>}>
        {id === ids.name ? (
          <Space.Compact block>
            <Space.Addon>{`${[repository, under].filter((part) => part !== '').join('/')}/`}</Space.Addon>
            <Input id={id} aria-label={label} aria-required aria-invalid={!!error} aria-describedby={`${id}-help`} value={value} onChange={(event) => change(event.target.value)} />
          </Space.Compact>
        ) : (
          <Control id={id} aria-label={label} aria-invalid={!!error} aria-describedby={`${id}-help`} value={value} onChange={(event) => change(event.target.value)} />
        )}
      </Form.Item>
    );
  };

  return (
    <MountedModal
      title={editing ? t('packages.folderDialog.edit', { folder: of.edit }) : t('packages.folderDialog.new')}
      onCancel={() => !pending && onClose()}
      okText={editing ? t('packages.folderDialog.save') : t('packages.folderDialog.create')}
      cancelText={t('frame.cancel')}
      confirmLoading={pending}
      okButtonProps={{ 'aria-label': editing ? t('packages.folderDialog.save') : t('packages.folderDialog.create'), form: ids.form, htmlType: 'submit', disabled: pending || typeof read !== 'object' || 'failed' in read }}
      cancelButtonProps={{ disabled: pending }}
    >
      <p>{t('packages.folderDialog.intro')}</p>
      {failure !== null && <Alert type="error" showIcon title={t('packages.folderDialog.failed')} description={failure} />}
      {read === 'reading' ? (
        <ReadSpinner label={t('packages.folderDialog.reading')} />
      ) : 'failed' in read ? (
        <Alert type="error" showIcon title={t('packages.folderDialog.unread')} description={read.failed} />
      ) : (
        <Form colon={false} id={ids.form} layout="vertical" onFinish={() => void submit()}>
          {!editing && field(ids.name, t('packages.folderDialog.name'), name, setName, errors.name, t('packages.folderDialog.name.hint'))}
          {field(ids.namespace, t('packages.folderDialog.namespace'), namespace, setNamespace, errors.namespace, t('packages.folderDialog.namespace.hint'))}
          {field(ids.description, t('packages.folderDialog.description'), description, setDescription, undefined, t('packages.folderDialog.description.hint'), true)}
        </Form>
      )}
    </MountedModal>
  );
}
