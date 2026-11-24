import { Alert, App, Button, Typography } from 'antd';
import { useState, type ReactNode } from 'react';
import { useWorkingFiles } from '../workingFiles';
import { PackagesError, type PackagesApi, type PackageState } from './api';
import { inside } from './data';
import { BUSY, short } from './parts';

/** What the last fetch, pull or push gave that the page shows: nothing, a refusal with git's lines, or files a pull is kept from. */
type Shown = { kind: 'none' } | { kind: 'refused'; title: string; error: string; output?: string } | { kind: 'unsaved'; paths: string[]; show: () => void };


/** Why Fetch, Pull or Push is disabled for the package `state` on a server that takes writes when `writable`; null when it is not. */
function remoteRefused(action: 'fetch' | 'pull' | 'push', writable: boolean, state: PackageState | null): string | null {
  if (!writable) return 'The server shows the working folder read-only (RAIL8_READ_ONLY is true).';
  if (state === null) return 'The package is not read.';
  if (state.busy !== null) return `The clone is busy (${BUSY[state.busy]}): finish it in a terminal.`;
  if (state.branch === null) return 'HEAD is detached: switch to a branch in a terminal.';
  if (state.upstream === null) return 'The branch has no upstream: push it once in a terminal with git push -u <remote> <branch>.';
  if (action === 'push' && state.upstream.ahead === 0) return `Nothing to push: the branch has no commit that ${state.upstream.name} lacks.`;
  return null;
}

/**
 * Fetch, Pull and Push of the package `repository`, whose state is `state`, and what the page shows of their outcome.
 * Each button is disabled, with the reason as its title, when its route would refuse it; one runs at a time. Pull is
 * refused while an open tab holds a file of the package with unsaved changes, which the page then names with Show tab;
 * after a pull, the tabs of the files it changed that have no unsaved changes read them again, every file of the
 * package when the pull could not name them. A success is a notification; a refusal stays above the page's sections,
 * with what git said, until it is dismissed or another action runs. Each answer's state is given to `onState`.
 */
export function useRemote(api: PackagesApi, repository: string, state: PackageState | null, writable: boolean, onState: (next: PackageState | null) => void) {
  const { notification } = App.useApp();
  const working = useWorkingFiles();
  const [running, setRunning] = useState<'fetch' | 'pull' | 'push' | null>(null);
  const [shown, setShown] = useState<Shown>({ kind: 'none' });

  const run = async (action: 'fetch' | 'pull' | 'push', title: string, work: () => Promise<{ package: PackageState | null; done: string }>) => {
    setShown({ kind: 'none' });
    setRunning(action);
    try {
      const { package: next, done } = await work();
      onState(next);
      notification.success({ key: `${action}:${repository}`, title: done, duration: 8, role: 'status', closable: { 'aria-label': `Dismiss ${action} notification` } });
    } catch (error) {
      const refused = error instanceof PackagesError ? error : new PackagesError(0, String(error));
      setShown({ kind: 'refused', title, error: refused.message, ...(refused.output === undefined || refused.output === '' ? {} : { output: refused.output }) });
      if (refused.status === 412 || refused.status === 409) onState(null);
    } finally {
      setRunning(null);
    }
  };

  const fetch = () =>
    run('fetch', 'The fetch failed', async () => {
      const answer = await api.fetch(repository);
      const upstream = answer.package?.upstream;
      return { package: answer.package, done: upstream ? `Fetched ${upstream.name}: ${upstream.behind} behind, ${upstream.ahead} ahead` : 'Fetched' };
    });

  const pull = () => {
    if (state === null || state.head === null) return;
    const unsaved = [...working.files().values()].filter((f) => f.dirty && f.path.startsWith(`${repository}/`));
    if (unsaved.length > 0) {
      setShown({ kind: 'unsaved', paths: unsaved.map((f) => f.path), show: () => unsaved[0]!.show() });
      return;
    }
    const body = { branch: state.branch!, head: state.head.id, upstream: state.upstream?.name ?? null };
    void run('pull', 'The pull was refused', async () => {
      const answer = await api.pull(repository, body);
      const all = [...working.files().keys()].filter((path) => path.startsWith(`${repository}/`));
      working.reload(answer.changed ?? all);
      const count = answer.changed?.length;
      return {
        package: answer.package,
        done: answer.head === body.head ? 'Nothing to pull' : `Pulled to ${short(answer.head)}${count === undefined ? '' : `: ${count} ${count === 1 ? 'file' : 'files'} changed`}`,
      };
    });
  };

  const push = () => {
    if (state === null || state.head === null) return;
    const body = { branch: state.branch!, head: state.head.id, upstream: state.upstream?.name ?? null };
    void run('push', 'The push was refused', async () => {
      const answer = await api.push(repository, body);
      return { package: answer.package, done: `Pushed ${short(answer.pushed.to)} to ${state.upstream?.name ?? answer.pushed.ref}` };
    });
  };

  const button = (action: 'fetch' | 'pull' | 'push', label: string, onClick: () => void) => {
    const refused = remoteRefused(action, writable, state);
    return (
      <Button key={action} loading={running === action} disabled={refused !== null || (running !== null && running !== action)} title={refused ?? undefined} onClick={onClick}>
        {label}
      </Button>
    );
  };

  let alert: ReactNode = null;
  if (shown.kind === 'refused') {
    alert = (
      <Alert
        type="error"
        showIcon
        title={shown.title}
        closable={{ 'aria-label': 'Dismiss the refusal', onClose: () => setShown({ kind: 'none' }) }}
        description={
          <>
            <Typography.Paragraph style={{ marginBottom: 0 }}>{shown.error}</Typography.Paragraph>
            {shown.output !== undefined && (
              <pre aria-label="What git said" style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
                {shown.output}
              </pre>
            )}
          </>
        }
      />
    );
  } else if (shown.kind === 'unsaved') {
    alert = (
      <Alert
        type="warning"
        showIcon
        title="Save or discard the unsaved changes first"
        description={`A pull may change ${shown.paths.map(inside).join(', ')}, which ${shown.paths.length === 1 ? 'an open tab holds' : 'open tabs hold'} with unsaved changes; nothing was pulled.`}
        closable={{ 'aria-label': 'Dismiss the warning', onClose: () => setShown({ kind: 'none' }) }}
        action={<Button onClick={shown.show}>Show tab</Button>}
      />
    );
  }

  return { buttons: [button('fetch', 'Fetch', () => void fetch()), button('pull', 'Pull', pull), button('push', 'Push', push)], alert };
}
