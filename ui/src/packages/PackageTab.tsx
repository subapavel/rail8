import { ReloadOutlined } from '@ant-design/icons';
import { ProCard } from '@ant-design/pro-components';
import { Alert, Badge, Button, Descriptions, Empty, Flex, Statistic, Tag, theme, Tooltip, Typography } from 'antd';
import { useId, useState, type ReactNode } from 'react';
import { say, useText, type Texts } from '../language';
import { Page } from '../Page';
import { ReadSpinner } from '../ReadSpinner';
import type { Screen } from '../screens';
import { useMay } from '../SessionGate';
import type { Commit, PackagesApi, PackageState } from './api';
import { CommitDialog } from './CommitDialog';
import { DependenciesCard } from './Dependencies';
import { DeployDialog } from './DeployDialog';
import { DiscardChangesDialog } from './DiscardChangesDialog';
import { useHistory, usePackage } from './data';
import { useRemote } from './remote';
import { UsedByCard } from './UsedBy';
import { ArtifactTree } from './ArtifactTree';
import { BUSY, ChangesTable, deployedStatus, factsOf, HistoryTable, PackageAlerts } from './parts';

export interface PackageTabProps {
  api: PackagesApi;
  repository: string;
  /** The clock the ages of commits are told against. */
  now: () => Date;
  /** Opens a screen in a tab of its own. */
  open: (screen: Screen) => void;
  /** Whether the server takes writes; false under RAIL8_READ_ONLY=true. */
  writable: boolean;
  /** The page's own tab shown first: `changes` (the default), `artifacts`, `history`, `dependencies` or `used-by`. */
  pane?: string;
  /** The path of an artifact the Artifacts tab chooses and shows when it is first drawn. */
  select?: string;
}

/**
 * Why Commit… is disabled for the package `state`, read or not, on a server that takes writes when `writable`, in
 * `texts`' language; null when it is not.
 */
function commitRefused(texts: Texts, writable: boolean, state: PackageState | null): string | null {
  const { t } = texts;
  if (!writable) return t('packages.readOnly');
  if (state === null) return t('packages.notRead');
  if (state.busy !== null) return t('packages.busy', { operation: say(texts, BUSY[state.busy]) });
  if (state.branch === null) return t('packages.detached');
  if (state.identity === null) return t('packages.commit.noIdentity');
  if (state.changes.length === 0) return t('packages.commit.nothing');
  return null;
}

/** Why Discard changes… is disabled for the package `state` with `chosen` changes ticked, in `texts`' language; null when it is not. */
function discardRefused(texts: Texts, writable: boolean, state: PackageState, chosen: number): string | null {
  const { t } = texts;
  if (!writable) return t('packages.readOnly');
  if (state.busy !== null) return t('packages.busy', { operation: say(texts, BUSY[state.busy]) });
  if (chosen === 0) return t('packages.discard.noneTicked');
  return null;
}

/** Why Deploy… is disabled for the package `state`, in `texts`' language; null when it is not. */
function deployRefused({ t }: Texts, state: PackageState | null): string | null {
  if (state === null) return t('packages.notRead');
  if (state.head === null) return t('packages.deploy.nothing');
  return null;
}

/** Discard changes…, disabled with `refused`, its reason, when that is not null. */
function DiscardButton({ refused, onClick }: { refused: string | null; onClick: () => void }) {
  const reasonId = useId();
  const { t } = useText();
  return (
    <Flex gap="small" align="center" wrap justify="flex-end">
      {refused !== null && (
        <Typography.Text type="secondary" id={reasonId}>
          {refused}
        </Typography.Text>
      )}
      <Button danger disabled={refused !== null} aria-describedby={refused === null ? undefined : reasonId} onClick={onClick}>
        {t('packages.discard.open')}
      </Button>
    </Flex>
  );
}

/** The commits of the branch in a titled card, with the button that reads older ones in the card's corner; each commit offers its deploy. */
function HistoryCard({ api, repository, state, now, onDeploy }: { api: PackagesApi; repository: string; state: PackageState; now: Date; onDeploy: (commit: Commit) => void }) {
  const { commits, next, failure, read, more, older, retry } = useHistory(api, repository, state.head?.id ?? null);
  const { t } = useText();
  if (failure !== null) return <Alert type="error" showIcon title={t('packages.history.unread')} description={failure} action={<Button onClick={retry}>{t('frame.tryAgain')}</Button>} />;
  return (
    <ProCard
      title={<h2>{state.branch === null ? t('packages.history.detached') : t('packages.history.on', { branch: state.branch })}</h2>}
      headerBordered
      extra={next !== null && (
        <Button loading={more} onClick={() => older(next)}>
          {t('packages.history.older')}
        </Button>
      )}
    >
      {read && commits.length === 0 ? <Empty description={t('packages.history.none')} /> : <HistoryTable api={api} repository={repository} commits={commits} loading={!read} host={state.deployed.host} now={now} onDeploy={onDeploy} />}
    </ProCard>
  );
}

/**
 * The tab of one package, as a detail page: its header names the package and its version, holds Refresh, the
 * package's facts as a small description list (repository, branch, last commit, upstream, and the commit the node
 * runs), what the node runs and the count of changed files at its right, and five tabs of its own. Below it, the
 * chosen tab's sections, each in a titled card: Changes lists every file that differs from the last commit, each with
 * its kind and its readable change and a tick box, and Discard changes… in its corner opens the discard dialog of the
 * ticked files (see {@link DiscardChangesDialog}); Artifacts shows the package's folders as one tree holding its flows, endpoints, mappings and tests and the
 * files they use, each opening in its editor's tab when a module owns its kind, with New folder, New flow and New mapping
 * writing into the chosen folder, and each folder's namespace (see {@link ArtifactTree}); `select` chooses an artifact in
 * it; History lists the commits of the
 * branch, each opening the files it changed, and each with Deploy this commit…, which is how the author goes back;
 * Dependencies lists what `package.yaml` declares, with its pin, its candidate and Lock (see {@link DependenciesCard});
 * Used by lists the packages the node runs that pin this one, and how each pin relates to what runs (see {@link UsedByCard}).
 * Each of them keeps its open rows and the commits it read while another is shown. Commit…, the page's main action,
 * opens the commit dialog, and says why when the commit route would refuse it; its Commit and deploy goes on to the
 * deploy dialog of the new commit. Deploy… opens the deploy dialog of `HEAD`. Fetch, Pull and Push share the branch
 * with its upstream (see {@link useRemote}). The package is read again when the tab is shown again, when the window gets
 * the focus, on Refresh, when a file changed after its change was listed, and once a New action wrote a file; a
 * commit's answer redraws it. `pane` names the page's own tab shown first.
 */
export function PackageTab({ api, repository, now, open, writable, pane: first = 'changes', select }: PackageTabProps) {
  const { read, refresh, show, root } = usePackage(api, repository, now);
  const [pane, setPane] = useState(first);
  // the page's own tabs shown so far: each stays mounted once shown, so it keeps its open rows and the pages it read
  const [shown, setShown] = useState<readonly string[]>([first]);
  const [committing, setCommitting] = useState(false);
  const [deploying, setDeploying] = useState<Pick<Commit, 'id' | 'subject'> | null>(null);
  // the paths ticked in the Changes table; a path no longer among the changes, or staged with other content, is not chosen
  const [picked, setPicked] = useState<readonly string[]>([]);
  const [discarding, setDiscarding] = useState(false);
  const refusedId = useId();
  const { token } = theme.useToken();
  const texts = useText();
  const { t } = texts;
  const state = read.kind === 'read' ? read.state : null;
  const at = read.kind === 'read' ? read.at : now();
  const deployed = state === null ? null : deployedStatus(texts, state);
  const readable = useMay('deployment.read');
  const refused = commitRefused(texts, writable, state);
  const undeployable = deployRefused(texts, state);
  const onState = (next: PackageState | null) => (next === null ? refresh() : show(next));
  const remote = useRemote(api, repository, state, writable, onState);
  const chosen = state === null ? [] : state.changes.filter((c) => c.index !== 'differs' && picked.includes(c.path));

  const choose = (key: string) => {
    setPane(key);
    setShown((keys) => (keys.includes(key) ? keys : [...keys, key]));
  };
  /** The page's own tab `key` once it was shown, hidden while another one is chosen. */
  const paneOf = (key: string, content: ReactNode) => shown.includes(key) && <div hidden={pane !== key}>{content}</div>;

  let body;
  if (read.kind === 'reading') {
    body = <ReadSpinner label={t('packages.package.reading', { name: repository })} />;
  } else if (read.kind === 'failed') {
    body = <Alert type="error" showIcon title={t('packages.package.unread')} description={read.message} action={<Button onClick={refresh}>{t('frame.tryAgain')}</Button>} />;
  } else {
    const { state } = read;
    body = (
      <Flex vertical gap="large">
        {remote.alert}
        <PackageAlerts state={state} />
        {paneOf(
          'changes',
          <ProCard
            title={<h2>{t('packages.changes.title')}</h2>}
            headerBordered
            extra={
              state.changes.length > 0 && <DiscardButton refused={discardRefused(texts, writable, state, chosen.length)} onClick={() => setDiscarding(true)} />
            }
          >
            {state.changes.length === 0 ? (
              <Empty description={t('packages.changes.none')} />
            ) : (
              <Flex vertical gap="small">
                {state.deployed.node === 'answered' && state.deployed.relation !== 'none' && <Typography.Text type="secondary">{t('packages.changes.neverDeployed')}</Typography.Text>}
                <ChangesTable
                  api={api}
                  repository={repository}
                  changes={state.changes}
                  before={state.head?.id ?? null}
                  after="work"
                  onStale={refresh}
                  label={t('packages.changes')}
                  choice={{ picked: chosen.map((c) => c.path), onPick: setPicked }}
                />
              </Flex>
            )}
          </ProCard>,
        )}
        {paneOf('artifacts', <ArtifactTree artifacts={state.artifacts} folder={repository} writable={writable} open={open} onCreated={refresh} folders={{ api, onState }} select={select} />)}
        {paneOf('history', <HistoryCard api={api} repository={repository} state={state} now={at} onDeploy={setDeploying} />)}
        {paneOf('dependencies', <DependenciesCard api={api} repository={repository} state={state} writable={writable} onState={onState} />)}
        {paneOf('used-by', <UsedByCard usedBy={state.usedBy} now={at} />)}
      </Flex>
    );
  }

  return (
    <div ref={root}>
      <Page
        title={state?.package?.name ?? repository}
        tags={state?.package ? <Tag variant="outlined">{state.package.version}</Tag> : undefined}
        extra={
          <Flex gap="small" align="center" wrap justify="flex-end">
            {refused !== null && state !== null && (
              <Typography.Text type="secondary" id={refusedId}>
                {refused}
              </Typography.Text>
            )}
            <Button icon={<ReloadOutlined aria-hidden />} onClick={refresh}>
              {t('packages.refresh')}
            </Button>
            {remote.buttons}
            <Button disabled={undeployable !== null} title={undeployable ?? undefined} onClick={() => state?.head && setDeploying(state.head)}>
              {t('packages.deploy.open')}
            </Button>
            <Button type="primary" disabled={refused !== null} aria-describedby={refused !== null && state !== null ? refusedId : undefined} onClick={() => setCommitting(true)}>
              {t('packages.commit.open')}
            </Button>
          </Flex>
        }
        content={state !== null && <Descriptions size="small" column={2} items={factsOf(texts, state, at)} />}
        extraContent={
          state !== null &&
          deployed !== null && (
            <Flex gap="large">
              {readable === true ? (
                <Statistic title={t('packages.deployed')} value={deployed.text} prefix={<Badge status={deployed.status} />} styles={{ content: { fontSize: token.fontSizeHeading4 } }} />
              ) : (
                <Tooltip title={readable}>
                  <Statistic title={t('packages.deployed')} value="-" styles={{ content: { fontSize: token.fontSizeHeading4 } }} />
                </Tooltip>
              )}
              <Statistic title={t('packages.changed')} value={state.changes.length} styles={{ content: { fontSize: token.fontSizeHeading4 } }} />
            </Flex>
          )
        }
        tabList={
          state === null
            ? undefined
            : [
                { key: 'changes', tab: t('packages.tab.changes', { count: state.changes.length }) },
                { key: 'artifacts', tab: t('packages.tab.artifacts', { count: state.artifacts.length }) },
                { key: 'history', tab: t('packages.history') },
                { key: 'dependencies', tab: t('packages.tab.dependencies', { count: state.dependencies.length }) },
                { key: 'used-by', tab: state.usedBy.node === 'answered' ? t('packages.tab.usedBy', { count: state.usedBy.consumers.length }) : t('packages.usedBy') },
              ]
        }
        tabActiveKey={pane}
        onTabChange={choose}
      >
        {body}
      </Page>
      {committing && state !== null && (
        <CommitDialog api={api} repository={repository} state={state} onState={onState} onClose={() => setCommitting(false)} onDeploy={setDeploying} />
      )}
      {discarding && state !== null && (
        <DiscardChangesDialog
          api={api}
          repository={repository}
          state={state}
          picked={chosen}
          onState={onState}
          onDiscarded={() => setPicked([])}
          onClose={() => setDiscarding(false)}
        />
      )}
      {deploying !== null && state !== null && (
        <DeployDialog api={api} repository={repository} commit={deploying} state={state} writable={writable} onState={onState} onClose={() => setDeploying(null)} />
      )}
    </div>
  );
}
