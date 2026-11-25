import { ProTable } from '@ant-design/pro-components';
import { Alert, Badge, Button, Flex, Tag, Tooltip, Typography, type DescriptionsProps } from 'antd';
import { useCallback, useContext, type ReactNode } from 'react';
import { ArtifactKinds } from '../artifacts';
import { msg, say, useText, type Message, type Texts } from '../language';
import { ReadSpinner } from '../ReadSpinner';
import type { Screen } from '../screens';
import { useMay } from '../SessionGate';
import { ageOf } from './age';
import type { Artifact, Busy, Change, Commit, Deployed, PackageRow, PackagesApi, PackageState, PoolNode } from './api';
import { CHANGE, inside, KIND, nameOf, useCommitChanges } from './data';
import { FileChange } from './FileChange';
import { MAX_LISTED } from './rules';

/** The operation git left open in a clone, in words. */
export const BUSY: Record<Exclude<Busy, null>, Message> = {
  merge: msg('packages.busy.merge'),
  rebase: msg('packages.busy.rebase'),
  'cherry-pick': msg('packages.busy.cherryPick'),
  revert: msg('packages.busy.revert'),
  bisect: msg('packages.busy.bisect'),
  conflict: msg('packages.busy.conflict'),
};

/** The `<code>` tag of a text: what it encloses, shown as code. */
export const code = (chunks: ReactNode[]) => <Typography.Text code>{chunks}</Typography.Text>;

/** The short form of a commit's id. */
export const short = (id: string) => id.slice(0, 7);

/** When `at` was, as its age with the time itself on hover. */
export function Age({ at, now }: { at: string; now: Date }) {
  const texts = useText();
  return <span title={at}>{ageOf(texts, at, now)}</span>;
}

/** How a file differs: a coloured dot and its word. */
export function ChangeBadge({ status }: { status: Change['status'] }) {
  const texts = useText();
  return <Badge status={CHANGE[status].status} text={say(texts, CHANGE[status].label)} />;
}

/** How the index holds a changed file, when it is not simply unstaged, and whether it is too large to compare. */
export function StagedNote({ change }: { change: Change }) {
  const { t } = useText();
  const notes = [
    ...(change.index === 'staged' ? [t('packages.note.staged')] : []),
    ...(change.index === 'differs' ? [t('packages.note.differs')] : []),
    ...(change.large ? [t('packages.note.large')] : []),
  ];
  return notes.length === 0 ? null : <Typography.Text type="secondary">{notes.join(', ')}</Typography.Text>;
}

/** The path of a changed file within its package, with the path it was renamed from below it. */
export function ChangedPath({ change }: { change: Change }) {
  const { t } = useText();
  return (
    <Flex vertical>
      <Typography.Text>{inside(change.path)}</Typography.Text>
      {change.renamedFrom !== undefined && <Typography.Text type="secondary">{t('packages.renamedFrom', { path: inside(change.renamedFrom) })}</Typography.Text>}
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

type Status = { status: 'success' | 'warning' | 'error' | 'processing' | 'default'; text: string; hint?: string };

/**
 * What one node runs of a package, as one status with its words in `texts`' language: `idle` when it runs nothing of it,
 * `changed` the files that differ from `HEAD`.
 */
function runStatus({ t }: Texts, runs: PoolNode | Exclude<Deployed, { node: 'unreachable' }>, changed: number, idle: string): Status {
  if (runs.relation === 'none') return { status: 'default', text: idle };
  if (runs.state === 'FAILED') return { status: 'error', text: t('packages.run.failed') };
  if (runs.relation === 'deployed') return changed > 0 ? { status: 'warning', text: t('packages.run.deployedChanged') } : { status: 'success', text: t('packages.run.deployed') };
  if (runs.relation === 'behind') return { status: 'warning', text: t('packages.run.behind', { count: runs.commits }) };
  if (runs.relation === 'ahead') return { status: 'processing', text: t('packages.run.ahead', { count: runs.commits }), hint: t('packages.run.ahead.hint') };
  return { status: 'default', text: t('packages.run.other') };
}

/**
 * What the node runs of the package, as one status with its words in `texts`' language, and what to do about it where
 * that is not plain: the vocabulary of the deployed state.
 */
export function deployedStatus(texts: Texts, state: PackageRow): Status {
  const deployed = state.deployed;
  if (deployed.node === 'unreachable') return { status: 'default', text: texts.t('packages.run.unknown') };
  return runStatus(texts, deployed, state.changed, texts.t('packages.run.notRunningOn', { host: deployed.host }));
}

/**
 * What every node of the node's pool runs of the package, when the node answers its pool route: one line per commit
 * with its status and how many of the nodes run it, then the nodes that do not run the pool's record of it, each with
 * why once a tick failed; or why the pool could not be read. Nothing for a session that may not read deployments.
 */
export function PoolFact({ state }: { state: PackageRow }) {
  const deployed = state.deployed;
  const readable = useMay('deployment.read');
  const texts = useText();
  const { t, rich } = texts;
  if (readable !== true || deployed.node === 'unreachable' || deployed.pool === undefined) return null;
  const pool = deployed.pool;
  if ('error' in pool) return <Typography.Text type="secondary">{t('packages.pool.unknown', { error: pool.error })}</Typography.Text>;
  const groups = new Map<string, PoolNode[]>();
  for (const node of pool.nodes) {
    const key = node.relation === 'none' ? 'none' : `${node.commit} ${node.state}`;
    groups.set(key, [...(groups.get(key) ?? []), node]);
  }
  const of = pool.nodes.length;
  const behind = pool.nodes.filter((n) => !n.follows);
  return (
    <Flex vertical>
      {[...groups.values()].map((nodes) => {
        const first = nodes[0]!;
        const { status, text, hint } = runStatus(texts, first, state.changed, t('packages.run.notRunning'));
        return (
          <Flex key={first.node} gap="small" wrap align="center">
            <Tooltip title={hint}>
              <Badge status={status} text={text} />
            </Tooltip>
            <Typography.Text type="secondary">
              {first.relation === 'none'
                ? t('packages.pool.nodes', { count: nodes.length, total: of })
                : rich('packages.pool.nodesAt', { code, commit: short(first.commit), count: nodes.length, total: of })}
            </Typography.Text>
          </Flex>
        );
      })}
      {behind.length > 0 && (
        <Typography.Text type="warning">
          {pool.record === null
            ? t('packages.pool.unrecorded', { nodes: behind.map((n) => n.node).join(', ') })
            : rich('packages.pool.behind', { code, commit: short(pool.record), nodes: behind.map((n) => n.node).join(', ') })}
        </Typography.Text>
      )}
      {behind.flatMap((n) =>
        n.behind.map((why, i) => (
          <Typography.Text key={`${n.node} ${i}`} type="secondary">
            {n.node}: {why}
          </Typography.Text>
        )),
      )}
    </Flex>
  );
}

/** What the node runs: its status, with its hint on hover, unless `withStatus` is false, the commit and when it was deployed, and the problems of a failed deploy. */
export function DeployedFact({ state, at, withStatus = true }: { state: PackageRow; at: Date; withStatus?: boolean }) {
  const deployed = state.deployed;
  const texts = useText();
  const { status, text, hint } = deployedStatus(texts, state);
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
            {deployed.deployedAt === ''
              ? texts.rich('packages.deployed.unknownTime', { code, commit: short(deployed.commit) })
              : texts.rich('packages.deployed.at', { code, commit: short(deployed.commit), age: <Age at={deployed.deployedAt} now={at} /> })}
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
  const texts = useText();
  return (
    <Flex gap="small" wrap align="center">
      {state.branch ?? <Tag>{texts.t('packages.branch.detached')}</Tag>}
      {state.busy !== null && <Badge status="warning" text={say(texts, BUSY[state.busy])} />}
    </Flex>
  );
}

/** The last commit: its subject, cut to one line with the whole of it on hover, then its id, author and age. */
export function CommitFact({ state, at }: { state: PackageRow; at: Date }) {
  const { t, rich } = useText();
  if (state.head === null) return <Typography.Text type="secondary">{t('packages.commit.none')}</Typography.Text>;
  return (
    <Flex vertical>
      <Typography.Text ellipsis={{ tooltip: state.head.subject }} style={{ maxWidth: 320 }}>
        {state.head.subject}
      </Typography.Text>
      <Typography.Text type="secondary">
        {rich('packages.commit.by', { code, id: short(state.head.id), author: state.head.author, age: <Age at={state.head.at} now={at} /> })}
      </Typography.Text>
    </Flex>
  );
}

/** The commits ahead of the upstream and behind it, as of the last fetch. */
export function UpstreamFact({ state, at }: { state: PackageRow; at: Date }) {
  const { t, rich } = useText();
  const upstream = state.upstream;
  if (upstream === null) return <Typography.Text type="secondary">{t('packages.upstream.none')}</Typography.Text>;
  return (
    <Flex vertical>
      <Typography.Text>
        {upstream.ahead === 0 && upstream.behind === 0
          ? t('packages.upstream.same', { name: upstream.name })
          : t('packages.upstream.aheadBehind', { ahead: upstream.ahead, behind: upstream.behind, name: upstream.name })}
      </Typography.Text>
      <Typography.Text type="secondary">
        {upstream.fetchedAt === null ? t('packages.upstream.neverFetched') : rich('packages.upstream.fetched', { age: <Age at={upstream.fetchedAt} now={at} /> })}
      </Typography.Text>
    </Flex>
  );
}

/**
 * The facts of a package, as the items of a description list labelled in `texts`' language; what the node runs without
 * its status, which the page shows apart, and what each node of its pool runs.
 */
export function factsOf({ t }: Texts, state: PackageState, at: Date): NonNullable<DescriptionsProps['items']> {
  return [
    { key: 'repository', label: t('packages.repository'), children: state.repository },
    { key: 'branch', label: t('packages.branch'), children: <BranchFact state={state} /> },
    { key: 'head', label: t('packages.lastCommit'), children: <CommitFact state={state} at={at} /> },
    { key: 'upstream', label: t('packages.upstream'), children: <UpstreamFact state={state} at={at} /> },
    { key: 'deployed', label: t('packages.deployedOn', { host: state.deployed.host }), children: <DeployedFact state={state} at={at} withStatus={false} /> },
    ...(state.deployed.node === 'answered' && state.deployed.pool !== undefined ? [{ key: 'pool', label: t('packages.pool'), children: <PoolFact state={state} /> }] : []),
  ];
}

/** The warnings a package's state carries: an unreadable package.yaml and lists cut at their limit. */
export function PackageAlerts({ state }: { state: PackageState }) {
  const { t } = useText();
  return (
    <>
      {state.package === null && <Alert type="error" showIcon title={t('packages.manifest.unread')} description={state.problem} />}
      {state.truncated && <Alert type="warning" showIcon title={t('packages.truncated', { max: MAX_LISTED })} description={t('packages.truncated.description')} />}
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
  const texts = useText();
  const { t } = texts;
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
          getCheckboxProps: (change) => ({ disabled: change.index === 'differs', 'aria-label': t('packages.choose', { path: inside(change.path) }) }),
          onChange: (keys) => choice.onPick(keys.map(String)),
        }
      }
      expandable={{ expandedRowRender: (change) => <FileChange api={api} repository={repository} change={change} before={before} after={after} onStale={onStale} /> }}
      columns={[
        { title: t('packages.file'), dataIndex: 'path', render: (_, c) => <ChangedPath change={c} /> },
        { title: t('packages.kind'), dataIndex: 'kind', width: 128, render: (_, c) => say(texts, KIND[c.kind]) },
        {
          title: t('packages.change'),
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
  const { t } = useText();
  if (parent === undefined) return <Typography.Text type="secondary">{t('packages.commit.first')}</Typography.Text>;
  if (read.kind === 'reading') return <ReadSpinner label={t('packages.commit.changes.reading', { commit: short(commit.id) })} />;
  if (read.kind === 'failed') return <Alert type="error" showIcon title={t('packages.commit.changes.unread')} description={read.message} />;
  return (
    <Flex vertical gap="small">
      {read.truncated && <Alert type="warning" showIcon title={t('packages.commit.changes.truncated', { max: MAX_LISTED })} />}
      <ChangesTable api={api} repository={repository} changes={read.changes} before={parent} after={commit.id} onStale={ignore} label={t('packages.commit.changes', { commit: short(commit.id) })} />
    </Flex>
  );
}

/** The subject of a commit, marked when the node runs it. */
export function CommitSubject({ commit, host }: { commit: Commit; host: string }) {
  const { t } = useText();
  return (
    <Flex gap="small" wrap align="center">
      <Typography.Text>{commit.subject}</Typography.Text>
      {commit.deployed && <Badge status="success" text={t('packages.commit.runsOn', { host })} />}
    </Flex>
  );
}

/** Artifacts, dense: each one's name, its file, what uses it when they are files, and how it changed. */
export function ArtifactsTable({ artifacts, open, label }: { artifacts: Artifact[]; open: (screen: Screen) => void; label: string }) {
  const used = artifacts.some((a) => a.kind === 'file');
  const { t } = useText();
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
        { title: t('packages.name'), key: 'name', render: (_, a) => <ArtifactName artifact={a} open={open} /> },
        { title: t('packages.file'), key: 'file', render: (_, a) => inside(a.path) },
        ...(used ? [{ title: t('packages.usedBy'), key: 'usedBy', render: (_: unknown, a: Artifact) => (a.usedBy ?? []).map(inside).join(', ') || '-' }] : []),
        { title: t('packages.change'), key: 'change', width: 128, render: (_, a) => (a.change === undefined ? '-' : <ChangeBadge status={a.change} />) },
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
  const { t } = useText();
  return (
    <ProTable<Commit>
      aria-label={t('packages.history')}
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
        { title: t('packages.commit'), key: 'subject', render: (_, c) => <CommitSubject commit={c} host={host} /> },
        { title: t('packages.id'), key: 'id', width: 96, render: (_, c) => <Typography.Text code>{short(c.id)}</Typography.Text> },
        { title: t('packages.author'), dataIndex: 'author', width: 160, ellipsis: true },
        { title: t('packages.date'), key: 'at', width: 128, render: (_, c) => <Age at={c.at} now={now} /> },
        {
          title: t('packages.action'),
          key: 'deploy',
          width: 176,
          render: (_, c) => (
            <Button type="link" size="small" className="rail8-link" onClick={() => onDeploy(c)}>
              {t('packages.deploy.thisCommit')}
            </Button>
          ),
        },
      ]}
    />
  );
}
