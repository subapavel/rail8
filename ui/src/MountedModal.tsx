import { Modal, type ModalProps } from 'antd';
import { useEffect, useState } from 'react';

/** A dialog its parent removes on close. Return focus to the opener if removal leaves the page without focus. */
export function MountedModal(props: Omit<ModalProps, 'open'>) {
  const [opener] = useState(() => document.activeElement);

  useEffect(
    () => () => {
      queueMicrotask(() => {
        if (
          opener instanceof HTMLElement &&
          opener !== document.body &&
          document.activeElement === document.body &&
          opener.isConnected &&
          opener.checkVisibility({ visibilityProperty: true }) &&
          !opener.closest('[inert], [aria-hidden="true"]')
        ) {
          opener.focus({ preventScroll: true });
        }
      });
    },
    [opener],
  );

  return <Modal open {...props} />;
}
