import { Alert, Button } from 'antd';
import { describeError } from '../api/client';

export interface RefusedMappingProps {
  /** Why the mapping did not open: a 422 `model-refused` with its diagnostics, or any other failure. */
  error: unknown;
  retrying: boolean;
  onRetry: () => void;
}

/** A mapping that does not open, read-only: the problem, its diagnostics and Retry, and no edit control. */
export function RefusedMapping({ error, retrying, onRetry }: RefusedMappingProps) {
  const failure = describeError(error);
  return (
    <Alert
      type="error"
      showIcon
      title={<h2>{failure.title}</h2>}
      action={
        <Button type="link" onClick={onRetry} loading={retrying} disabled={retrying}>
          Retry
        </Button>
      }
      description={
        <>
          <p>{failure.detail}</p>
          <ul aria-label="Load diagnostics">
            {failure.diagnostics.map((d, i) => (
              <li key={i}>
                {d.severity} <code>{d.code}</code>: {d.message}
              </li>
            ))}
          </ul>
        </>
      }
    />
  );
}
