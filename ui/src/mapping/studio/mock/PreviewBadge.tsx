import { Tag, Tooltip } from 'antd';
import { useState } from 'react';
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
  const { t } = useText();
  return (
    <Tooltip
      destroyOnHidden
      trigger="hover"
      open={hovered || focused}
      onOpenChange={setHovered}
      title={t('studio.preview.tip', { feature, issue })}
    >
      <Tag tabIndex={0} color="purple" onFocus={() => setFocused(true)} onBlur={() => setFocused(false)}>
        {t('studio.preview')}
      </Tag>
    </Tooltip>
  );
}
