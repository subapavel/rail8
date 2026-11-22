import { Button, Tooltip } from 'antd';
import { CheckOutlined, CopyOutlined, ExclamationCircleOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';

const COPIED = 'Copied!';
/** How long the receipt shows. */
const RECEIPT_MS = 2400;

/**
 * Copies `text` to the clipboard: its icon, its tooltip and a screen-reader status
 * say whether it did, for 2.4 s. The tooltip shows while the button is hovered or focused, or a receipt shows; Escape
 * hides it and the receipt. There is no `execCommand('copy')` fallback: the browsers the studio
 * supports all have the asynchronous clipboard.
 */
export function CopyTextButton({ label, text }: { label: string; text: string }) {
  const [hover, setHover] = useState(false);
  const [focus, setFocus] = useState(false);
  const [receipt, setReceipt] = useState<string | null>(null);
  useEffect(() => {
    if (receipt === null) return undefined;
    const timer = setTimeout(() => setReceipt(null), RECEIPT_MS);
    return () => clearTimeout(timer);
  }, [receipt]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setReceipt(COPIED);
    } catch {
      setReceipt('Could not copy. Select the text and copy it manually.');
    }
  };
  const icon = receipt === null ? <CopyOutlined aria-hidden /> : receipt === COPIED ? <CheckOutlined aria-hidden /> : <ExclamationCircleOutlined aria-hidden />;
  return (
    <>
      <Tooltip trigger={[]} open={hover || focus || receipt !== null} placement="topRight" title={receipt ?? 'Copy to clipboard'}>
        <Button
          type="text"
          aria-label={`Copy ${label} to clipboard`}
          onClick={() => void copy()}
          icon={icon}
          onMouseEnter={() => setHover(true)}
          onMouseLeave={() => setHover(false)}
          onFocus={() => setFocus(true)}
          onBlur={() => setFocus(false)}
          onKeyDown={(event) => {
            if (event.key !== 'Escape') return;
            setFocus(false);
            setHover(false);
            setReceipt(null);
          }}
        />
      </Tooltip>
      <span style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clipPath: 'inset(50%)' }} role="status">
        {receipt}
      </span>
    </>
  );
}
