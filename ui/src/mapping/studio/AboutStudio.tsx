import { Button, Modal } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useText } from '../../language';

/** The studio's help button, which opens About Mapping studio: how to edit, connect, lay out, save and test a mapping. */
export function AboutStudio() {
  const [open, setOpen] = useState(false);
  const { t } = useText();
  const about = t('studio.about');
  return (
    <>
      <Button type="text" aria-label={about} title={about} icon={<QuestionCircleOutlined />} onClick={() => setOpen(true)} />
      <Modal title={about} open={open} onCancel={() => setOpen(false)} footer={null}>
        <p>{t('studio.about.open')}</p>
        <p>{t('studio.about.connect')}</p>
        <p>{t('studio.about.canvas')}</p>
        <p>{t('studio.about.parameters')}</p>
      </Modal>
    </>
  );
}
