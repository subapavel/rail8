import { Spin } from 'antd';

/** Give fast reads time to finish before showing the workspace loading indicator. */
export function ReadSpinner({ label, size }: { label?: string; size?: 'small' }) {
  return <Spin aria-label={label} size={size} delay={200} />;
}
