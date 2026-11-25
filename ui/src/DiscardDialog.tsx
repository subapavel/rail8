import { Button, Modal } from 'antd';
import { useRef } from 'react';
import { useText } from './language';
export interface DiscardDialogProps {
  isOpen: boolean;
  /** The name of what is being edited, such as a mapping's. */
  name: string;
  onDiscard: () => void;
  onKeepEditing: () => void;
}

/** "Discard changes?", and return to view mode: Discard changes, or Keep editing, which has the focus. */
export function DiscardDialog({ isOpen, name, onDiscard, onKeepEditing }: DiscardDialogProps) {
  const { t, rich } = useText();
  const keep = useRef<HTMLButtonElement>(null);
  return (
    <Modal
      open={isOpen}
      title={t('frame.discard.title')}
      footer={[
        <Button key="discard" danger onClick={onDiscard}>
          {t('frame.discard.discard')}
        </Button>,
        <Button key="keep" type="primary" ref={keep} onClick={onKeepEditing}>
          {t('frame.discard.keep')}
        </Button>,
      ]}
      afterOpenChange={(open) => {
        if (open) keep.current?.focus();
      }}
      onOk={onDiscard}
      onCancel={onKeepEditing}
    >
      {rich('frame.discard.question', { name, b: (chunks) => <strong>{chunks}</strong> })}
    </Modal>
  );
}
