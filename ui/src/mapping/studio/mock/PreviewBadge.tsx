import { Tag, Tooltip } from 'antd';
export interface PreviewBadgeProps {
  /** The preview feature's name. */
  feature: string;
  /** Its follow-up issue. */
  issue: number;
}

/**
 * The "Preview" badge of a preview feature, one the studio shows but does not store in the mapping: its tooltip names
 * the feature and its follow-up issue, on hover and on keyboard focus.
 */
export function PreviewBadge({ feature, issue }: PreviewBadgeProps) {
  return (
    <Tooltip destroyOnHidden trigger={['hover', 'focus']} title={`${feature}: a preview kept in this browser only, never in the mapping file (#${issue}).`}>
      <Tag tabIndex={0} color="purple">
        Preview
      </Tag>
    </Tooltip>
  );
}
