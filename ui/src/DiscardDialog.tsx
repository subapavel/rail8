import { Button, Modal } from 'antd';
import { useRef } from 'react';
export interface DiscardDialogProps {
  isOpen: boolean;
  /** The name of what is being edited, such as a mapping's. */
  name: string;
  /** What Discard changes does next, ending the question, such as "return to view mode". */
  then: string;
  onDiscard: () => void;
  onKeepEditing: () => void;
}

/** "Discard changes?": Discard changes, or Keep editing, which has the focus. */
export function DiscardDialog({ isOpen, name, then, onDiscard, onKeepEditing }: DiscardDialogProps) {
  const keep = useRef<HTMLButtonElement>(null);
  return (
    <Modal
      open={isOpen}
      title="Discard changes?"
      footer={[
        <Button key="discard" danger onClick={onDiscard}>
          Discard changes
        </Button>,
        <Button key="keep" type="primary" ref={keep} onClick={onKeepEditing}>
          Keep editing
        </Button>,
      ]}
      afterOpenChange={(open) => {
        if (open) keep.current?.focus();
      }}
      onOk={onDiscard}
      onCancel={onKeepEditing}
    >
      Discard your unsaved changes to <strong>{name}</strong> and {then}?
    </Modal>
  );
}
