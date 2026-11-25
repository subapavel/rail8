import { Button } from 'antd';
import { useText } from './language';
import { MountedModal } from './MountedModal';

export interface UnsavedDialogProps {
  /** The titles of the tabs about to close whose screens have unsaved changes, one at least. */
  titles: readonly string[];
  /** Whether the saves asked for are running: the buttons wait for them. */
  saving: boolean;
  onSave: () => void;
  onDiscard: () => void;
  onCancel: () => void;
}

/**
 * The question asked before tabs with unsaved changes close. For one tab: Save and close, Close without saving or
 * Cancel. For several, which it lists: Save all and close, Close without saving or Cancel. Its parent removes it once
 * it is answered.
 */
export function UnsavedDialog({ titles, saving, onSave, onDiscard, onCancel }: UnsavedDialogProps) {
  const { t, rich } = useText();
  const one = titles.length === 1 ? titles[0] : undefined;
  return (
    <MountedModal
      title={one === undefined ? t('frame.unsaved.titleMany', { count: titles.length }) : t('frame.unsaved.title', { title: one })}
      onCancel={saving ? undefined : onCancel}
      footer={[
        <Button key="cancel" disabled={saving} onClick={onCancel}>
          {t('frame.cancel')}
        </Button>,
        <Button key="discard" danger disabled={saving} onClick={onDiscard}>
          {t('frame.unsaved.discard')}
        </Button>,
        <Button key="save" type="primary" loading={saving} onClick={onSave}>
          {t(one === undefined ? 'frame.unsaved.saveAll' : 'frame.unsaved.save')}
        </Button>,
      ]}
    >
      {one === undefined ? (
        <>
          {t('frame.unsaved.many', { count: titles.length })}
          <ul aria-label={t('frame.unsaved.list')}>
            {titles.map((title, i) => (
              <li key={i}>{title}</li>
            ))}
          </ul>
        </>
      ) : (
        rich('frame.unsaved.one', { title: one, b: (chunks) => <strong>{chunks}</strong> })
      )}
    </MountedModal>
  );
}
