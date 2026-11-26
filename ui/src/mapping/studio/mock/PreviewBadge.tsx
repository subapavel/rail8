import { Tag, Tooltip } from 'antd';
import { useRef, useState } from 'react';
import { useText } from '../../../language';

export interface PreviewBadgeProps {
  /** The preview feature's name, in the language the UI shows. */
  feature: string;
  /** Its follow-up issue. */
  issue: number;
}

/**
 * The "Preview" badge of a preview feature, one the studio shows but does not store in the mapping: its tooltip names
 * the feature and its follow-up issue, on hover and on keyboard focus.
 */
export function PreviewBadge({ feature, issue }: PreviewBadgeProps) {
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  // a press of the pointer on the badge, until the focus it gives arrives or the badge loses the focus
  const pressed = useRef(false);
  const { t } = useText();
  return (
    <Tooltip
      destroyOnHidden
      trigger="hover"
      open={hovered || focused}
      onOpenChange={setHovered}
      title={t('studio.preview.tip', { feature, issue })}
    >
      {/* focus a click gave does not keep the tooltip open: it would stay over what is above the badge once the pointer left */}
      <Tag
        tabIndex={0}
        color="purple"
        onPointerDown={() => (pressed.current = true)}
        onFocus={() => {
          setFocused(!pressed.current);
          pressed.current = false;
        }}
        onBlur={() => {
          setFocused(false);
          pressed.current = false;
        }}
      >
        {t('studio.preview')}
      </Tag>
    </Tooltip>
  );
}
