import { Typography } from 'antd';

/** The readable change of a kind whose comparison is not built yet: it says only that the file changed. */
export function ChangedOnly() {
  return <Typography.Text type="secondary">changed</Typography.Text>;
}
