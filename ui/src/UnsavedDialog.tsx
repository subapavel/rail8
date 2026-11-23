import { Button } from 'antd';
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
  const one = titles.length === 1 ? titles[0] : undefined;
  return (
    <MountedModal
      title={one === undefined ? `Save changes to ${titles.length} tabs?` : `Save changes to ${one}?`}
      onCancel={saving ? undefined : onCancel}
      footer={[
        <Button key="cancel" disabled={saving} onClick={onCancel}>
          Cancel
        </Button>,
        <Button key="discard" danger disabled={saving} onClick={onDiscard}>
          Close without saving
        </Button>,
        <Button key="save" type="primary" loading={saving} onClick={onSave}>
          {one === undefined ? 'Save all and close' : 'Save and close'}
        </Button>,
      ]}
    >
      {one === undefined ? (
        <>
          These tabs have unsaved changes:
          <ul aria-label="Tabs with unsaved changes">
            {titles.map((title, i) => (
              <li key={i}>{title}</li>
            ))}
          </ul>
        </>
      ) : (
        <>
          <strong>{one}</strong> has unsaved changes.
        </>
      )}
    </MountedModal>
  );
}
