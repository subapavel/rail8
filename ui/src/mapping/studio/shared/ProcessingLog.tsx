import { Button, Card, Modal, Typography } from 'antd';
import { CopyTextButton } from './CopyTextButton';

/** The processing log of a tab's last action, with a copy button. */
export function ProcessingLog({ log }: { log: string }) {
  return (
    <Card title={<h3>Processing Log</h3>} extra={<CopyTextButton label="processing log" text={log} />}>
      <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{log}</pre>
    </Card>
  );
}

/** The Processing Log dialog an action opens with its log. */
export function LogDialog({ log, onClose }: { log: string; onClose: () => void }) {
  return (
    <Modal
      open
      title="Processing Log"
      onCancel={onClose}
      footer={
        <Button type="primary" onClick={onClose}>
          Close
        </Button>
      }
    >
      <Typography.Paragraph>{log}</Typography.Paragraph>
    </Modal>
  );
}
