import { Alert, Modal } from 'antd';
import { useMutation } from '@tanstack/react-query';
import { useStore } from 'zustand';
import { useText } from '../../language';
import { describeError } from '../api/client';
import type { WorkingCopyStore } from './store/working-copy';

/**
 * The save conflict: the file changed on disk since it was opened. Reload drops the working copy and its
 * undo history and loads the file; Keep editing keeps the working copy, and the next Save meets the conflict again.
 */
export function SaveConflict({ store }: { store: WorkingCopyStore }) {
  const open = useStore(store, (s) => s.conflict);
  const reload = useMutation({ mutationFn: () => store.getState().reload() });
  const failure = reload.isError ? describeError(reload.error) : undefined;
  const { t } = useText();
  const keepEditing = () => {
    reload.reset();
    store.getState().keepEditing();
  };

  return (
    <Modal
      title={t('studio.conflict')}
      open={open}
      onCancel={keepEditing}
      cancelText={t('frame.discard.keep')}
      okText={t('studio.conflict.reload')}
      onOk={() => reload.mutate()}
      confirmLoading={reload.isPending}
      okButtonProps={{ disabled: reload.isPending }}
    >
      <p>{t('studio.conflict.why')}</p>
      <p>{t('studio.conflict.reload.what')}</p>
      {failure && <Alert type="error" showIcon title={<h3>{failure.title}</h3>} description={failure.detail} />}
    </Modal>
  );
}
