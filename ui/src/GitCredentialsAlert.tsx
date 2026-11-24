import { Alert } from 'antd';
import { useEffect, useState } from 'react';
import { uiFetch } from './session';

/** What the node's `GET /admin/git` answers: its last check of the git credentials; every field null before the first. */
export interface GitCheck {
  credentials: 'WORKING' | 'REFUSED' | 'UNREACHABLE' | null;
  repository: string | null;
  reason: string | null;
  checkedAt: string | null;
}

/** The node's last check of its git credentials, through the UI server's `/admin` and `send` (the page's `uiFetch` by default); null when it gives no answer. */
export async function readGitCheck(send: typeof fetch = uiFetch): Promise<GitCheck | null> {
  try {
    const res = await send('/admin/git', { headers: { accept: 'application/json' } });
    return res.ok ? ((await res.json()) as GitCheck) : null;
  } catch {
    return null;
  }
}

/**
 * A warning while the node finds its git credentials refused, saying what stops working: a deploy, and a node that restarts
 * with an empty git cache. It reads the check once, through `read`; it shows nothing while the credentials work, before the
 * first check, and when the caller may not read the check or the node does not answer.
 */
export function GitCredentialsAlert({ read = readGitCheck }: { read?: () => Promise<GitCheck | null> }) {
  const [check, setCheck] = useState<GitCheck | null>(null);
  useEffect(() => {
    let live = true;
    void read().then((answer) => {
      if (live) setCheck(answer);
    });
    return () => {
      live = false;
    };
  }, [read]);
  if (check?.credentials !== 'REFUSED') return null;
  return (
    <Alert
      type="warning"
      showIcon
      title="The node's git credentials are refused, or the repository is no longer visible to the token"
      description={
        <>
          Until RAIL8_GIT_USER and RAIL8_GIT_TOKEN work again, a deploy fails, and a node that restarts with an empty git cache
          comes up without its packages. Messages keep flowing. The node's last check: {check.reason}
          {check.checkedAt && ` at ${check.checkedAt}`}.
        </>
      }
    />
  );
}
