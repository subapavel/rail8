import { Button, Card, Typography } from 'antd';
import { useText } from '../../../language';
import { MountedModal } from '../../../MountedModal';
import { CopyTextButton } from './CopyTextButton';

/** The processing log of a tab's last action, with a copy button. */
export function ProcessingLog({ log }: { log: string }) {
  const { t } = useText();
  return (
    <Card title={<h3>{t('studio.log')}</h3>} extra={<CopyTextButton label={t('studio.log.copy')} text={log} />}>
      <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{log}</pre>
    </Card>
  );
}

/** The Processing Log dialog an action opens with its log. */
export function LogDialog({ log, onClose }: { log: string; onClose: () => void }) {
  const { t } = useText();
  return (
    <MountedModal
      title={t('studio.log')}
      onCancel={onClose}
      footer={
        <Button type="primary" onClick={onClose}>
          {t('frame.tab.close')}
        </Button>
      }
    >
      <Typography.Paragraph>{log}</Typography.Paragraph>
    </MountedModal>
  );
}
