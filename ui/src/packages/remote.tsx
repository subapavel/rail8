import { Alert, App, Button, Typography } from 'antd';
import { useState, type ReactNode } from 'react';
import { msg, say, useText, type Message, type Texts } from '../language';
import { useWorkingFiles } from '../workingFiles';
import { PackagesError, type PackagesApi, type PackageState } from './api';
import { inside } from './data';
import { BUSY, short } from './parts';

/** What the last fetch, pull or push gave that the page shows: nothing, a refusal with git's lines, or files a pull is kept from. */
type Shown = { kind: 'none' } | { kind: 'refused'; title: Message; error: string; output?: string } | { kind: 'unsaved'; paths: string[]; show: () => void };

/**
 * Why Fetch, Pull or Push is disabled for the package `state` on a server that takes writes when `writable`, in
 * `texts`' language; null when it is not.
 */
function remoteRefused(texts: Texts, action: 'fetch' | 'pull' | 'push', writable: boolean, state: PackageState | null): string | null {
  const { t } = texts;
  if (!writable) return t('packages.readOnly');
  if (state === null) return t('packages.notRead');
  if (state.busy !== null) return t('packages.busy', { operation: say(texts, BUSY[state.busy]) });
  if (state.branch === null) return t('packages.detached');
  if (state.upstream === null) return t('packages.remote.noUpstream', { command: 'git push -u <remote> <branch>' }); // untranslated: a git command
  if (action === 'push' && state.upstream.ahead === 0) return t('packages.push.nothing', { upstream: state.upstream.name });
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
  const texts = useText();
  const { t } = texts;
  const [running, setRunning] = useState<'fetch' | 'pull' | 'push' | null>(null);
  const [shown, setShown] = useState<Shown>({ kind: 'none' });

  const run = async (action: 'fetch' | 'pull' | 'push', title: Message, work: () => Promise<{ package: PackageState | null; done: string }>) => {
    setShown({ kind: 'none' });
    setRunning(action);
    try {
      const { package: next, done } = await work();
      onState(next);
      const dismiss = { fetch: t('packages.fetch.dismiss'), pull: t('packages.pull.dismiss'), push: t('packages.push.dismiss') }[action];
      notification.success({ key: `${action}:${repository}`, title: done, duration: 8, role: 'status', closable: { 'aria-label': dismiss } });
    } catch (error) {
      const refused = error instanceof PackagesError ? error : new PackagesError(0, String(error));
      setShown({ kind: 'refused', title, error: refused.message, ...(refused.output === undefined || refused.output === '' ? {} : { output: refused.output }) });
      if (refused.status === 412 || refused.status === 409) onState(null);
    } finally {
      setRunning(null);
    }
  };

  const fetch = () =>
    run('fetch', msg('packages.fetch.failed'), async () => {
      const answer = await api.fetch(repository);
      const upstream = answer.package?.upstream;
      return {
        package: answer.package,
        done: upstream ? t('packages.fetch.done', { upstream: upstream.name, behind: upstream.behind, ahead: upstream.ahead }) : t('packages.fetch.doneAlone'),
      };
    });

  const pull = () => {
    if (state === null || state.head === null) return;
    const unsaved = [...working.files().values()].filter((f) => f.dirty && f.path.startsWith(`${repository}/`));
    if (unsaved.length > 0) {
      setShown({ kind: 'unsaved', paths: unsaved.map((f) => f.path), show: () => unsaved[0]!.show() });
      return;
    }
    const body = { branch: state.branch!, head: state.head.id, upstream: state.upstream?.name ?? null };
    void run('pull', msg('packages.pull.refused'), async () => {
      const answer = await api.pull(repository, body);
      const all = [...working.files().keys()].filter((path) => path.startsWith(`${repository}/`));
      working.reload(answer.changed ?? all);
      const count = answer.changed?.length;
      return {
        package: answer.package,
        done:
          answer.head === body.head
            ? t('packages.pull.nothing')
            : count === undefined
              ? t('packages.pull.done', { commit: short(answer.head) })
              : t('packages.pull.doneFiles', { commit: short(answer.head), count }),
      };
    });
  };

  const push = () => {
    if (state === null || state.head === null) return;
    const body = { branch: state.branch!, head: state.head.id, upstream: state.upstream?.name ?? null };
    void run('push', msg('packages.push.refused'), async () => {
      const answer = await api.push(repository, body);
      return { package: answer.package, done: t('packages.push.done', { commit: short(answer.pushed.to), upstream: state.upstream?.name ?? answer.pushed.ref }) };
    });
  };

  const button = (action: 'fetch' | 'pull' | 'push', label: string, onClick: () => void) => {
    const refused = remoteRefused(texts, action, writable, state);
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
        title={say(texts, shown.title)}
        closable={{ 'aria-label': t('packages.refusal.dismiss'), onClose: () => setShown({ kind: 'none' }) }}
        description={
          <>
            <Typography.Paragraph style={{ marginBottom: 0 }}>{shown.error}</Typography.Paragraph>
            {shown.output !== undefined && (
              <pre aria-label={t('packages.gitSaid')} style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
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
        title={t('packages.pull.unsaved')}
        description={t('packages.pull.unsaved.description', { paths: shown.paths.map(inside).join(', '), count: shown.paths.length })}
        closable={{ 'aria-label': t('packages.warning.dismiss'), onClose: () => setShown({ kind: 'none' }) }}
        action={<Button onClick={shown.show}>{t('packages.showTab')}</Button>}
      />
    );
  }

  return {
    buttons: [button('fetch', t('packages.fetch'), () => void fetch()), button('pull', t('packages.pull'), pull), button('push', t('packages.push'), push)],
    alert,
  };
}
