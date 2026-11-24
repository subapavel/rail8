import { ReloadOutlined } from '@ant-design/icons';
import { ProCard } from '@ant-design/pro-components';
import { Alert, Badge, Button, Col, Descriptions, Empty, Flex, Row, Statistic, Tag, theme, Typography } from 'antd';
import { useId, useState, type ReactNode } from 'react';
import { Page } from '../Page';
import { ReadSpinner } from '../ReadSpinner';
import type { Screen } from '../screens';
import type { Commit, PackagesApi, PackageState } from './api';
import { CommitDialog } from './CommitDialog';
import { DeployDialog } from './DeployDialog';
import { GROUPS, useHistory, usePackage } from './data';
import { ArtifactsTable, BUSY, ChangesTable, deployedStatus, factsOf, HistoryTable, PackageAlerts } from './parts';

export interface PackageTabProps {
  api: PackagesApi;
  repository: string;
  /** The clock the ages of commits are told against. */
  now: () => Date;
  /** Opens a screen in a tab of its own. */
  open: (screen: Screen) => void;
  /** Whether the server takes writes; false under RAIL8_READ_ONLY=true. */
  writable: boolean;
}

/** Why Commit… is disabled for the package `state`, read or not, on a server that takes writes when `writable`; null when it is not. */
function commitRefused(writable: boolean, state: PackageState | null): string | null {
  if (!writable) return 'The server shows the working folder read-only (RAIL8_READ_ONLY is true).';
  if (state === null) return 'The package is not read.';
  if (state.busy !== null) return `The clone is busy (${BUSY[state.busy]}): finish it in a terminal.`;
  if (state.branch === null) return 'HEAD is detached: switch to a branch in a terminal.';
  if (state.identity === null) return 'git gives no author identity here: set git config user.name and git config user.email in a terminal.';
  if (state.changes.length === 0) return 'Nothing to commit: no file differs from the last commit.';
  return null;
}

/** Why Deploy… is disabled for the package `state`; null when it is not. */
function deployRefused(state: PackageState | null): string | null {
  if (state === null) return 'The package is not read.';
  if (state.head === null) return 'Nothing to deploy: the branch has no commit yet.';
  return null;
}

/** The commits of the branch in a titled card, with the button that reads older ones in the card's corner; each commit offers its deploy. */
function HistoryCard({ api, repository, state, now, onDeploy }: { api: PackagesApi; repository: string; state: PackageState; now: Date; onDeploy: (commit: Commit) => void }) {
  const { commits, next, failure, read, more, older, retry } = useHistory(api, repository, state.head?.id ?? null);
  if (failure !== null) return <Alert type="error" showIcon title="The history could not be read" description={failure} action={<Button onClick={retry}>Try again</Button>} />;
  return (
    <ProCard
      title={<h2>Commits on {state.branch ?? 'the detached HEAD'}</h2>}
      headerBordered
      extra={next !== null && (
        <Button loading={more} onClick={() => older(next)}>
          Show older commits
        </Button>
      )}
    >
      {read && commits.length === 0 ? <Empty description="No commit yet." /> : <HistoryTable api={api} repository={repository} commits={commits} loading={!read} host={state.deployed.host} now={now} onDeploy={onDeploy} />}
    </ProCard>
  );
}

/** The artifacts, one titled card for each kind that has some, two cards side by side on a wide screen. */
function ArtifactCards({ state, open }: { state: PackageState; open: (screen: Screen) => void }) {
  const { token } = theme.useToken();
  const groups = GROUPS.map((g) => ({ ...g, listed: state.artifacts.filter((a) => g.kinds.includes(a.kind)) })).filter((g) => g.listed.length > 0);
  if (groups.length === 0) return <ProCard><Empty description="The package holds no flow, endpoint, mapping or test." /></ProCard>;
  return (
    <Row gutter={[token.marginLG, token.marginLG]}>
      {groups.map((group) => (
        <Col key={group.key} xs={24} xl={12}>
          <ProCard title={<h2>{group.title}</h2>} subTitle={group.listed.length} headerBordered style={{ height: '100%' }}>
            <ArtifactsTable artifacts={group.listed} open={open} label={group.title} />
          </ProCard>
        </Col>
      ))}
    </Row>
  );
}

/**
 * The tab of one package, as a detail page: its header names the package and its version, holds Refresh, the
 * package's facts as a small description list (repository, branch, last commit, upstream, and the commit the node
 * runs), what the node runs and the count of changed files at its right, and three tabs of its own. Below it, the
 * chosen tab's sections, each in a titled card: Changes lists every file that differs from the last commit, each with
 * its kind and its readable change; Artifacts lists the package's flows, endpoints, mappings and tests and the files
 * they use, each opening in its own screen's tab when a module owns its kind; History lists the commits of the
 * branch, each opening the files it changed, and each with Deploy this commit…, which is how the author goes back. Each
 * of the three keeps its open rows and the commits it read while another is shown. Commit…, the page's main action,
 * opens the commit dialog, and says why when the commit route would refuse it; its Commit and deploy goes on to the
 * deploy dialog of the new commit. Deploy… opens the deploy dialog of `HEAD`. The package is read again when the tab is shown again, when the window gets
 * the focus, on Refresh, and when a file changed after its change was listed; a commit's answer redraws it.
 */
export function PackageTab({ api, repository, now, open, writable }: PackageTabProps) {
  const { read, refresh, show, root } = usePackage(api, repository, now);
  const [pane, setPane] = useState('changes');
  // the page's own tabs shown so far: each stays mounted once shown, so it keeps its open rows and the pages it read
  const [shown, setShown] = useState<readonly string[]>(['changes']);
  const [committing, setCommitting] = useState(false);
  const [deploying, setDeploying] = useState<Pick<Commit, 'id' | 'subject'> | null>(null);
  const refusedId = useId();
  const { token } = theme.useToken();
  const state = read.kind === 'read' ? read.state : null;
  const at = read.kind === 'read' ? read.at : now();
  const deployed = state === null ? null : deployedStatus(state);
  const refused = commitRefused(writable, state);
  const undeployable = deployRefused(state);
  const onState = (next: PackageState | null) => (next === null ? refresh() : show(next));

  const choose = (key: string) => {
    setPane(key);
    setShown((keys) => (keys.includes(key) ? keys : [...keys, key]));
  };
  /** The page's own tab `key` once it was shown, hidden while another one is chosen. */
  const paneOf = (key: string, content: ReactNode) => shown.includes(key) && <div hidden={pane !== key}>{content}</div>;

  let body;
  if (read.kind === 'reading') {
    body = <ReadSpinner label={`Reading the package ${repository}`} />;
  } else if (read.kind === 'failed') {
    body = <Alert type="error" showIcon title="The package could not be read" description={read.message} action={<Button onClick={refresh}>Try again</Button>} />;
  } else {
    const { state } = read;
    body = (
      <Flex vertical gap="large">
        <PackageAlerts state={state} />
        {paneOf(
          'changes',
          <ProCard
            title={<h2>Uncommitted changes</h2>}
            headerBordered
            extra={
              state.changes.length > 0 &&
              state.deployed.node === 'answered' &&
              state.deployed.relation !== 'none' && <Typography.Text type="secondary">Uncommitted changes are never deployed.</Typography.Text>
            }
          >
            {state.changes.length === 0 ? (
              <Empty description="No file differs from the last commit." />
            ) : (
              <ChangesTable api={api} repository={repository} changes={state.changes} before={state.head?.id ?? null} after="work" onStale={refresh} label="Changes" />
            )}
          </ProCard>,
        )}
        {paneOf('artifacts', <ArtifactCards state={state} open={open} />)}
        {paneOf('history', <HistoryCard api={api} repository={repository} state={state} now={at} onDeploy={setDeploying} />)}
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
              Refresh
            </Button>
            <Button disabled={undeployable !== null} title={undeployable ?? undefined} onClick={() => state?.head && setDeploying(state.head)}>
              Deploy…
            </Button>
            <Button type="primary" disabled={refused !== null} aria-describedby={refused !== null && state !== null ? refusedId : undefined} onClick={() => setCommitting(true)}>
              Commit…
            </Button>
          </Flex>
        }
        content={state !== null && <Descriptions size="small" column={2} items={factsOf(state, at)} />}
        extraContent={
          state !== null &&
          deployed !== null && (
            <Flex gap="large">
              <Statistic title="Deployed" value={deployed.text} prefix={<Badge status={deployed.status} />} styles={{ content: { fontSize: token.fontSizeHeading4 } }} />
              <Statistic title="Changed files" value={state.changes.length} styles={{ content: { fontSize: token.fontSizeHeading4 } }} />
            </Flex>
          )
        }
        tabList={
          state === null
            ? undefined
            : [
                { key: 'changes', tab: `Changes (${state.changes.length})` },
                { key: 'artifacts', tab: `Artifacts (${state.artifacts.length})` },
                { key: 'history', tab: 'History' },
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
      {deploying !== null && state !== null && (
        <DeployDialog api={api} repository={repository} commit={deploying} state={state} writable={writable} onState={onState} onClose={() => setDeploying(null)} />
      )}
    </div>
  );
}
