import { ProTable } from '@ant-design/pro-components';
import { Alert, Badge, Button, Flex, Tag, Tooltip, Typography, type DescriptionsProps } from 'antd';
import { useCallback, useContext } from 'react';
import { ArtifactKinds } from '../artifacts';
import { ReadSpinner } from '../ReadSpinner';
import type { Screen } from '../screens';
import { useMay } from '../SessionGate';
import { ageOf } from './age';
import type { Artifact, Busy, Change, Commit, PackageRow, PackagesApi, PackageState } from './api';
import { CHANGE, inside, KIND, nameOf, useCommitChanges } from './data';
import { FileChange } from './FileChange';
import { MAX_LISTED } from './rules';

/** The operation git left open in a clone, in words. */
export const BUSY: Record<Exclude<Busy, null>, string> = {
  merge: 'Merge in progress',
  rebase: 'Rebase in progress',
  'cherry-pick': 'Cherry-pick in progress',
  revert: 'Revert in progress',
  bisect: 'Bisect in progress',
  conflict: 'Conflict',
};

/** The short form of a commit's id. */
export const short = (id: string) => id.slice(0, 7);

/** When `at` was, as its age with the time itself on hover. */
export function Age({ at, now }: { at: string; now: Date }) {
  return <span title={at}>{ageOf(at, now)}</span>;
}

/** How a file differs: a coloured dot and its word. */
export function ChangeBadge({ status }: { status: Change['status'] }) {
  return <Badge status={CHANGE[status].status} text={CHANGE[status].label} />;
}

/** How the index holds a changed file, when it is not simply unstaged, and whether it is too large to compare. */
export function StagedNote({ change }: { change: Change }) {
  const notes = [
    ...(change.index === 'staged' ? ['staged'] : []),
    ...(change.index === 'differs' ? ['staged with other content'] : []),
    ...(change.large ? ['too large to compare'] : []),
  ];
  return notes.length === 0 ? null : <Typography.Text type="secondary">{notes.join(', ')}</Typography.Text>;
}

/** The path of a changed file within its package, with the path it was renamed from below it. */
export function ChangedPath({ change }: { change: Change }) {
  return (
    <Flex vertical>
      <Typography.Text>{inside(change.path)}</Typography.Text>
      {change.renamedFrom !== undefined && <Typography.Text type="secondary">from {inside(change.renamedFrom)}</Typography.Text>}
    </Flex>
  );
}

/** The name of an artifact: its id, or its file's name; a link to its own screen's tab when a module owns its kind. */
export function ArtifactName({ artifact, open }: { artifact: Artifact; open: (screen: Screen) => void }) {
  const kind = useContext(ArtifactKinds).get(artifact.kind);
  const name = artifact.id ?? nameOf(artifact.path);
  if (kind === undefined) return <Typography.Text>{name}</Typography.Text>;
  return (
    <Button type="link" className="rail8-link" onClick={() => open(kind.open(artifact.path))}>
      {name}
    </Button>
  );
}

/** What the node runs of the package, as one status with its words, and what to do about it where that is not plain: the vocabulary of the deployed state. */
export function deployedStatus(state: PackageRow): { status: 'success' | 'warning' | 'error' | 'processing' | 'default'; text: string; hint?: string } {
  const deployed = state.deployed;
  if (deployed.node === 'unreachable') return { status: 'default', text: 'Unknown' };
  if (deployed.relation === 'none') return { status: 'default', text: `Not running on ${deployed.host}` };
  if (deployed.state === 'FAILED') return { status: 'error', text: 'Failed' };
  if (deployed.relation === 'deployed') return state.changed > 0 ? { status: 'warning', text: 'Deployed, with changes since' } : { status: 'success', text: 'Deployed' };
  if (deployed.relation === 'behind') return { status: 'warning', text: `Behind ${deployed.commits}` };
  if (deployed.relation === 'ahead') return { status: 'processing', text: `Ahead ${deployed.commits}`, hint: 'The node runs commits that HEAD lacks: pull them.' };
  return { status: 'default', text: 'Other commit' };
}

/** What the node runs: its status, with its hint on hover, unless `withStatus` is false, the commit and when it was deployed, and the problems of a failed deploy. */
export function DeployedFact({ state, at, withStatus = true }: { state: PackageRow; at: Date; withStatus?: boolean }) {
  const deployed = state.deployed;
  const { status, text, hint } = deployedStatus(state);
  const readable = useMay('deployment.read');
  if (readable !== true) return <Typography.Text type="secondary">{readable}</Typography.Text>;
  return (
    <Flex vertical>
      <Flex gap="small" wrap align="center">
        {(withStatus || deployed.node === 'unreachable' || deployed.relation === 'none') && (
          <Tooltip title={hint}>
            <Badge status={status} text={text} />
          </Tooltip>
        )}
        {deployed.node === 'answered' && deployed.relation !== 'none' && (
          <Typography.Text type="secondary">
            <Typography.Text code>{short(deployed.commit)}</Typography.Text> deployed {deployed.deployedAt === '' ? 'at an unknown time' : <Age at={deployed.deployedAt} now={at} />}
          </Typography.Text>
        )}
      </Flex>
      {deployed.node === 'unreachable' && <Typography.Text type="secondary">{deployed.error}</Typography.Text>}
      {deployed.node === 'answered' && deployed.relation !== 'none' && deployed.problems.map((problem, n) => (
        <Typography.Text key={n} type="danger">
          {problem}
        </Typography.Text>
      ))}
    </Flex>
  );
}

/** The branch, with the operation git left open in the clone. */
export function BranchFact({ state }: { state: PackageRow }) {
  return (
    <Flex gap="small" wrap align="center">
      {state.branch ?? <Tag>Detached HEAD</Tag>}
      {state.busy !== null && <Badge status="warning" text={BUSY[state.busy]} />}
    </Flex>
  );
}

/** The last commit: its subject, cut to one line with the whole of it on hover, then its id, author and age. */
export function CommitFact({ state, at }: { state: PackageRow; at: Date }) {
  if (state.head === null) return <Typography.Text type="secondary">No commit yet</Typography.Text>;
  return (
    <Flex vertical>
      <Typography.Text ellipsis={{ tooltip: state.head.subject }} style={{ maxWidth: 320 }}>
        {state.head.subject}
      </Typography.Text>
      <Typography.Text type="secondary">
        <Typography.Text code>{short(state.head.id)}</Typography.Text> {state.head.author}, <Age at={state.head.at} now={at} />
      </Typography.Text>
    </Flex>
  );
}

/** The commits ahead of the upstream and behind it, as of the last fetch. */
export function UpstreamFact({ state, at }: { state: PackageRow; at: Date }) {
  const upstream = state.upstream;
  if (upstream === null) return <Typography.Text type="secondary">No upstream</Typography.Text>;
  return (
    <Flex vertical>
      <Typography.Text>
        {upstream.ahead === 0 && upstream.behind === 0 ? `Up to date with ${upstream.name}` : `${upstream.ahead} ahead, ${upstream.behind} behind ${upstream.name}`}
      </Typography.Text>
      <Typography.Text type="secondary">
        {upstream.fetchedAt === null ? 'never fetched' : (
          <>
            as of the fetch <Age at={upstream.fetchedAt} now={at} />
          </>
        )}
      </Typography.Text>
    </Flex>
  );
}

/** The facts of a package, as the items of a description list; what the node runs without its status, which the page shows apart. */
export function factsOf(state: PackageState, at: Date): NonNullable<DescriptionsProps['items']> {
  return [
    { key: 'repository', label: 'Repository', children: state.repository },
    { key: 'branch', label: 'Branch', children: <BranchFact state={state} /> },
    { key: 'head', label: 'Last commit', children: <CommitFact state={state} at={at} /> },
    { key: 'upstream', label: 'Upstream', children: <UpstreamFact state={state} at={at} /> },
    { key: 'deployed', label: `Deployed on ${state.deployed.host}`, children: <DeployedFact state={state} at={at} withStatus={false} /> },
  ];
}

/** The warnings a package's state carries: an unreadable package.yaml and lists cut at their limit. */
export function PackageAlerts({ state }: { state: PackageState }) {
  return (
    <>
      {state.package === null && <Alert type="error" showIcon title="package.yaml cannot be read" description={state.problem} />}
      {state.truncated && <Alert type="warning" showIcon title={`The package's lists are cut at ${MAX_LISTED} files`} description="It has more changed files or artifacts than are listed; only the first ones are shown." />}
    </>
  );
}

/**
 * The files `changes` lists, dense, each row opening its readable change between the revisions `before` and `after`.
 * With `choice`, each row has a tick box, ticked when `choice.picked` holds its path, except a row whose index `differs`,
 * which cannot be ticked; `choice.onPick` gets the ticked paths after each tick.
 */
export function ChangesTable({ api, repository, changes, before, after, onStale, label, choice }: {
  api: PackagesApi;
  repository: string;
  changes: Change[];
  before: string | null;
  after: string;
  onStale: () => void;
  label: string;
  choice?: { picked: readonly string[]; onPick: (paths: string[]) => void };
}) {
  return (
    <ProTable<Change>
      aria-label={label}
      rowKey="path"
      size="small"
      dataSource={changes}
      search={false}
      options={false}
      toolBarRender={false}
      pagination={false}
      cardProps={false}
      tableAlertRender={false}
      rowSelection={
        choice && {
          selectedRowKeys: [...choice.picked],
          getCheckboxProps: (change) => ({ disabled: change.index === 'differs', 'aria-label': `Choose ${change.path.slice(change.path.indexOf('/') + 1)}` }),
          onChange: (keys) => choice.onPick(keys.map(String)),
        }
      }
      expandable={{ expandedRowRender: (change) => <FileChange api={api} repository={repository} change={change} before={before} after={after} onStale={onStale} /> }}
      columns={[
        { title: 'File', dataIndex: 'path', render: (_, c) => <ChangedPath change={c} /> },
        { title: 'Kind', dataIndex: 'kind', width: 128, render: (_, c) => KIND[c.kind] },
        {
          title: 'Change',
          dataIndex: 'status',
          width: 224,
          render: (_, c) => (
            <Flex gap="small" wrap align="center">
              <ChangeBadge status={c.status} />
              <StagedNote change={c} />
            </Flex>
          ),
        },
      ]}
    />
  );
}

/** The files a commit changed against its first parent, each with its readable change. */
export function CommitChanges({ api, repository, commit }: { api: PackagesApi; repository: string; commit: Commit }) {
  const { parent, read } = useCommitChanges(api, repository, commit);
  const ignore = useCallback(() => {}, []);
  if (parent === undefined) return <Typography.Text type="secondary">The first commit of the branch: it adds every file of its tree.</Typography.Text>;
  if (read.kind === 'reading') return <ReadSpinner label={`Reading the changes of ${short(commit.id)}`} />;
  if (read.kind === 'failed') return <Alert type="error" showIcon title="The changes of the commit could not be read" description={read.message} />;
  return (
    <Flex vertical gap="small">
      {read.truncated && <Alert type="warning" showIcon title={`The commit changes more than ${MAX_LISTED} files; only the first ones are listed.`} />}
      <ChangesTable api={api} repository={repository} changes={read.changes} before={parent} after={commit.id} onStale={ignore} label={`Files changed by ${short(commit.id)}`} />
    </Flex>
  );
}

/** The subject of a commit, marked when the node runs it. */
export function CommitSubject({ commit, host }: { commit: Commit; host: string }) {
  return (
    <Flex gap="small" wrap align="center">
      <Typography.Text>{commit.subject}</Typography.Text>
      {commit.deployed && <Badge status="success" text={`Runs on ${host}`} />}
    </Flex>
  );
}

/** Artifacts, dense: each one's name, its file, what uses it when they are files, and how it changed. */
export function ArtifactsTable({ artifacts, open, label }: { artifacts: Artifact[]; open: (screen: Screen) => void; label: string }) {
  const used = artifacts.some((a) => a.kind === 'file');
  return (
    <ProTable<Artifact>
      aria-label={label}
      rowKey="path"
      size="small"
      dataSource={artifacts}
      search={false}
      options={false}
      toolBarRender={false}
      pagination={false}
      cardProps={false}
      columns={[
        { title: 'Name', key: 'name', render: (_, a) => <ArtifactName artifact={a} open={open} /> },
        { title: 'File', key: 'file', render: (_, a) => inside(a.path) },
        ...(used ? [{ title: 'Used by', key: 'usedBy', render: (_: unknown, a: Artifact) => (a.usedBy ?? []).map(inside).join(', ') || '-' }] : []),
        { title: 'Change', key: 'change', width: 128, render: (_, a) => (a.change === undefined ? '-' : <ChangeBadge status={a.change} />) },
      ]}
    />
  );
}

/** The commits of the branch, dense, each opening the files it changed, and each with Deploy this commit…, which calls `onDeploy`; the commit the node runs is marked. */
export function HistoryTable({ api, repository, commits, loading, host, now, onDeploy }: {
  api: PackagesApi;
  repository: string;
  commits: Commit[];
  loading: boolean;
  host: string;
  now: Date;
  onDeploy: (commit: Commit) => void;
}) {
  return (
    <ProTable<Commit>
      aria-label="History"
      rowKey="id"
      size="small"
      loading={loading}
      dataSource={commits}
      search={false}
      options={false}
      toolBarRender={false}
      pagination={false}
      cardProps={false}
      expandable={{ expandedRowRender: (commit) => <CommitChanges api={api} repository={repository} commit={commit} /> }}
      columns={[
        { title: 'Commit', key: 'subject', render: (_, c) => <CommitSubject commit={c} host={host} /> },
        { title: 'Id', key: 'id', width: 96, render: (_, c) => <Typography.Text code>{short(c.id)}</Typography.Text> },
        { title: 'Author', dataIndex: 'author', width: 160, ellipsis: true },
        { title: 'Date', key: 'at', width: 128, render: (_, c) => <Age at={c.at} now={now} /> },
        {
          title: 'Action',
          key: 'deploy',
          width: 176,
          render: (_, c) => (
            <Button type="link" size="small" className="rail8-link" onClick={() => onDeploy(c)}>
              Deploy this commit…
            </Button>
          ),
        },
      ]}
    />
  );
}
