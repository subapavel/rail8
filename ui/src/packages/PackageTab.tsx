import { ReloadOutlined } from '@ant-design/icons';
import { ProTable } from '@ant-design/pro-components';
import { Alert, Button, Empty, Flex, Tabs, Tag, Typography } from 'antd';
import { useCallback, useContext, useEffect, useRef, useState, type RefObject } from 'react';
import { ArtifactKinds } from '../artifacts';
import { ReadSpinner } from '../ReadSpinner';
import type { Screen } from '../screens';
import type { Artifact, Change, Commit, Kind, PackagesApi, PackageState } from './api';
import { FileChange } from './FileChange';
import { Age, BUSY, DeployedState, short } from './PackageList';
import { MAX_LISTED } from './rules';

export interface PackageTabProps {
  api: PackagesApi;
  repository: string;
  /** The clock the ages of commits are told against. */
  now: () => Date;
  /** Opens a screen in a tab of its own. */
  open: (screen: Screen) => void;
}

type Result = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; state: PackageState; at: Date };

const STATUS: Record<Change['status'], { label: string; color: string }> = {
  added: { label: 'Added', color: 'success' },
  modified: { label: 'Modified', color: 'warning' },
  deleted: { label: 'Deleted', color: 'error' },
  renamed: { label: 'Renamed', color: 'processing' },
};

/** The kinds the Artifacts tab lists, in its order, each under its heading. */
const GROUPS: { title: string; kinds: Kind[] }[] = [
  { title: 'Flows', kinds: ['flow'] },
  { title: 'Endpoints', kinds: ['endpoint'] },
  { title: 'Mappings', kinds: ['mapping'] },
  { title: 'Tests', kinds: ['test'] },
  { title: 'Package files', kinds: ['package', 'lock', 'folder'] },
  { title: 'Files they use', kinds: ['file'] },
];

const KIND: Record<Kind, string> = {
  flow: 'Flow',
  endpoint: 'Endpoint',
  mapping: 'Mapping',
  test: 'Test',
  package: 'package.yaml',
  lock: 'package.lock',
  folder: 'folder.yaml',
  file: 'File',
};

/** The path of `path` within its package: the working folder's path without its first segment. */
const inside = (path: string) => path.slice(path.indexOf('/') + 1);

/** Calls `shown` each time the element of `ref` comes into view again after it was hidden, as a tab that is chosen again. */
function useShownAgain(ref: RefObject<HTMLElement | null>, shown: () => void) {
  useEffect(() => {
    const element = ref.current;
    if (element === null || typeof IntersectionObserver === 'undefined') return;
    let visible: boolean | undefined;
    const observer = new IntersectionObserver(([entry]) => {
      const now = entry?.isIntersecting ?? false;
      if (visible === false && now) shown();
      visible = now;
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [ref, shown]);
}

/** The files `changes` lists, each row opening its readable change between the revisions `before` and `after`. */
function ChangeTable({ api, repository, changes, before, after, onStale, label }: {
  api: PackagesApi;
  repository: string;
  changes: Change[];
  before: string | null;
  after: string;
  onStale: () => void;
  label: string;
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
      expandable={{
        expandedRowRender: (change) => <FileChange api={api} repository={repository} change={change} before={before} after={after} onStale={onStale} />,
      }}
      columns={[
        {
          title: 'File',
          dataIndex: 'path',
          render: (_, c) => (
            <Flex vertical>
              <Typography.Text>{inside(c.path)}</Typography.Text>
              {c.renamedFrom !== undefined && <Typography.Text type="secondary">from {inside(c.renamedFrom)}</Typography.Text>}
            </Flex>
          ),
        },
        { title: 'Kind', dataIndex: 'kind', render: (_, c) => KIND[c.kind] },
        {
          title: 'Change',
          dataIndex: 'status',
          render: (_, c) => (
            <Flex gap="small" wrap align="center">
              <Tag color={STATUS[c.status].color}>{STATUS[c.status].label}</Tag>
              {c.index === 'staged' && <Typography.Text type="secondary">staged</Typography.Text>}
              {c.index === 'differs' && <Typography.Text type="secondary">staged in a terminal with other content</Typography.Text>}
              {c.large && <Typography.Text type="secondary">too large to compare</Typography.Text>}
            </Flex>
          ),
        },
      ]}
    />
  );
}

/** The package's artifacts, under a heading for each kind, each opening in its own screen's tab when a module owns its kind. */
function ArtifactsPane({ artifacts, open }: { artifacts: Artifact[]; open: (screen: Screen) => void }) {
  const kinds = useContext(ArtifactKinds);
  const groups = GROUPS.map((g) => ({ ...g, listed: artifacts.filter((a) => g.kinds.includes(a.kind)) })).filter((g) => g.listed.length > 0);
  if (groups.length === 0) return <Empty description="The package holds no flow, endpoint, mapping or test." />;
  return (
    <Flex vertical gap="middle">
      {groups.map((group) => (
        <Flex vertical gap="small" key={group.title}>
          <Typography.Title level={3} style={{ margin: 0 }}>
            {group.title}
          </Typography.Title>
          <ProTable<Artifact>
            aria-label={group.title}
            rowKey="path"
            size="small"
            dataSource={group.listed}
            search={false}
            options={false}
            toolBarRender={false}
            pagination={false}
            columns={[
              {
                title: 'Artifact',
                key: 'artifact',
                render: (_, a) => {
                  const name = a.id ?? a.path.slice(a.path.lastIndexOf('/') + 1);
                  const kind = kinds.get(a.kind);
                  return kind === undefined ? (
                    name
                  ) : (
                    <Button type="link" style={{ padding: 0 }} onClick={() => open(kind.open(a.path))}>
                      {name}
                    </Button>
                  );
                },
              },
              { title: 'File', dataIndex: 'path', render: (_, a) => inside(a.path) },
              { title: 'Changed', key: 'change', render: (_, a) => (a.change === undefined ? '—' : <Tag color={STATUS[a.change].color}>{STATUS[a.change].label}</Tag>) },
              ...(group.kinds.includes('file')
                ? [{ title: 'Used by', key: 'usedBy', render: (_: unknown, a: Artifact) => (a.usedBy ?? []).map(inside).join(', ') }]
                : []),
            ]}
          />
        </Flex>
      ))}
    </Flex>
  );
}

/** The files the commit `commit` changed against its first parent, each with its readable change. */
function CommitChanges({ api, repository, commit }: { api: PackagesApi; repository: string; commit: Commit }) {
  const parent = commit.parents[0];
  const [read, setRead] = useState<{ kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; changes: Change[]; truncated: boolean }>({ kind: 'reading' });
  useEffect(() => {
    if (parent === undefined) return;
    let current = true;
    api.changes(repository, parent, commit.id).then(
      (listed) => current && setRead({ kind: 'read', ...listed }),
      (error: unknown) => current && setRead({ kind: 'failed', message: error instanceof Error ? error.message : String(error) }),
    );
    return () => {
      current = false;
    };
  }, [api, repository, commit.id, parent]);
  const ignore = useCallback(() => {}, []);
  if (parent === undefined) return <Typography.Text type="secondary">The first commit of the branch: it adds every file of its tree.</Typography.Text>;
  if (read.kind === 'reading') return <ReadSpinner label={`Reading the changes of ${short(commit.id)}`} />;
  if (read.kind === 'failed') return <Alert type="error" showIcon title="The changes of the commit could not be read" description={read.message} />;
  return (
    <Flex vertical gap="small">
      {read.truncated && <Alert type="warning" showIcon title={`The commit changes more than ${MAX_LISTED} files; only the first ones are listed.`} />}
      <ChangeTable api={api} repository={repository} changes={read.changes} before={parent} after={commit.id} onStale={ignore} label={`Files changed by ${short(commit.id)}`} />
    </Flex>
  );
}

/** The commits of the branch, newest first, a page at a time; the commit the node runs is marked, and each commit opens the files it changed. */
function HistoryPane({ api, repository, head, host, now }: { api: PackagesApi; repository: string; head: string | null; host: string; now: Date }) {
  const [shown, setShown] = useState<{ commits: Commit[]; next: string | null; failure: string | null; read: boolean }>({ commits: [], next: null, failure: null, read: false });
  const [more, setMore] = useState(false);
  const failed = (error: unknown) => (error instanceof Error ? error.message : String(error));

  useEffect(() => {
    let current = true;
    api.log(repository).then(
      (page) => current && setShown({ commits: page.items, next: page.next, failure: null, read: true }),
      (error: unknown) => current && setShown((s) => ({ ...s, failure: failed(error), read: true })),
    );
    return () => {
      current = false;
    };
  }, [api, repository, head]);
  const older = (before: string) => {
    setMore(true);
    api.log(repository, { before }).then(
      (page) => setShown((s) => ({ ...s, commits: [...s.commits, ...page.items], next: page.next })),
      (error: unknown) => setShown((s) => ({ ...s, failure: failed(error) })),
    ).finally(() => setMore(false));
  };
  const { commits, next, failure, read } = shown;

  if (failure !== null) return <Alert type="error" showIcon title="The history could not be read" description={failure} />;
  if (read && commits.length === 0) return <Empty description="No commit yet." />;
  return (
    <Flex vertical gap="small">
      <ProTable<Commit>
        aria-label="History"
        rowKey="id"
        size="small"
        loading={!read}
        dataSource={commits}
        search={false}
        options={false}
        toolBarRender={false}
        pagination={false}
        expandable={{ expandedRowRender: (commit) => <CommitChanges api={api} repository={repository} commit={commit} /> }}
        columns={[
          {
            title: 'Commit',
            key: 'subject',
            render: (_, c) => (
              <Flex gap="small" wrap align="center">
                <Typography.Text>{c.subject}</Typography.Text>
                {c.deployed && <Tag color="success">Runs on {host}</Tag>}
              </Flex>
            ),
          },
          { title: 'Id', key: 'id', render: (_, c) => <Typography.Text code>{short(c.id)}</Typography.Text> },
          { title: 'Author', dataIndex: 'author' },
          { title: 'Date', key: 'at', render: (_, c) => <Age at={c.at} now={now} /> },
        ]}
      />
      {next !== null && (
        <Button style={{ alignSelf: 'start' }} loading={more} onClick={() => older(next)}>
          Show older commits
        </Button>
      )}
    </Flex>
  );
}

/**
 * The tab of one package: a header with its name and version, branch, last commit, ahead and behind its upstream and
 * what the node runs of it, and three tabs of its own. Changes lists every file that differs from the last commit, each
 * with its kind and its readable change; Artifacts lists the package's flows, endpoints, mappings and tests and the
 * files they use; History lists the commits of the branch, each opening the files it changed. The package is read
 * again when the tab is shown again, when the window gets the focus, on Refresh, and when a file changed after its
 * change was listed.
 */
export function PackageTab({ api, repository, now, open }: PackageTabProps) {
  const [result, setResult] = useState<Result>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const refresh = useCallback(() => setAsked((n) => n + 1), []);
  const root = useRef<HTMLDivElement>(null);
  useShownAgain(root, refresh);

  useEffect(() => {
    window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, [refresh]);

  useEffect(() => {
    let current = true;
    api.get(repository).then(
      (state) => current && setResult({ kind: 'read', state, at: now() }),
      (error: unknown) => current && setResult({ kind: 'failed', message: error instanceof Error ? error.message : String(error) }),
    );
    return () => {
      current = false;
    };
  }, [api, repository, asked, now]);

  let body;
  let title = repository;
  if (result.kind === 'reading') {
    body = <ReadSpinner label={`Reading the package ${repository}`} />;
  } else if (result.kind === 'failed') {
    body = <Alert showIcon type="error" title="The package could not be read" description={result.message} />;
  } else {
    const { state, at } = result;
    if (state.package !== null) title = `${state.package.name} ${state.package.version}`;
    body = (
      <Flex vertical gap="middle">
        <Flex gap="large" wrap>
          <Flex vertical>
            <Typography.Text type="secondary">Branch</Typography.Text>
            <Flex gap="small" wrap align="center">
              {state.branch ?? <Tag>Detached HEAD</Tag>}
              {state.busy !== null && <Tag color="warning">{BUSY[state.busy]}</Tag>}
            </Flex>
          </Flex>
          <Flex vertical>
            <Typography.Text type="secondary">Last commit</Typography.Text>
            {state.head === null ? (
              <Typography.Text type="secondary">No commit yet</Typography.Text>
            ) : (
              <span>
                {state.head.subject} <Typography.Text code>{short(state.head.id)}</Typography.Text> {state.head.author}, <Age at={state.head.at} now={at} />
              </span>
            )}
          </Flex>
          <Flex vertical>
            <Typography.Text type="secondary">Upstream</Typography.Text>
            {state.upstream === null ? (
              <Typography.Text type="secondary">No upstream</Typography.Text>
            ) : (
              <span>
                {state.upstream.ahead} ahead, {state.upstream.behind} behind {state.upstream.name}
              </span>
            )}
          </Flex>
          <Flex vertical>
            <Typography.Text type="secondary">Deployed on {state.deployed.host}</Typography.Text>
            <DeployedState row={state} now={at} />
          </Flex>
        </Flex>
        {state.package === null && <Alert type="error" showIcon title="package.yaml cannot be read" description={state.problem} />}
        {state.truncated && <Alert type="warning" showIcon title={`The package's lists are cut at ${MAX_LISTED} files`} description="It has more changed files or artifacts than are listed; only the first ones are shown." />}
        <Tabs
          items={[
            {
              key: 'changes',
              label: `Changes (${state.changes.length})`,
              children:
                state.changes.length === 0 ? (
                  <Empty description="No file differs from the last commit." />
                ) : (
                  <Flex vertical gap="small">
                    {state.deployed.node === 'answered' && state.deployed.relation !== 'none' && (
                      <Typography.Text type="secondary">Uncommitted changes are never deployed.</Typography.Text>
                    )}
                    <ChangeTable api={api} repository={repository} changes={state.changes} before={state.head?.id ?? null} after="work" onStale={refresh} label="Changes" />
                  </Flex>
                ),
            },
            { key: 'artifacts', label: 'Artifacts', children: <ArtifactsPane artifacts={state.artifacts} open={open} /> },
            { key: 'history', label: 'History', children: <HistoryPane api={api} repository={repository} head={state.head?.id ?? null} host={state.deployed.host} now={at} /> },
          ]}
        />
      </Flex>
    );
  }

  return (
    <Flex vertical gap="middle" ref={root}>
      <Flex align="center" gap="small" wrap>
        <Typography.Title level={1} style={{ margin: 0 }}>
          {title}
        </Typography.Title>
        <Typography.Text type="secondary">{repository}</Typography.Text>
        <Flex gap="small" style={{ marginInlineStart: 'auto' }}>
          <Button icon={<ReloadOutlined aria-hidden />} onClick={refresh}>
            Refresh
          </Button>
        </Flex>
      </Flex>
      {body}
    </Flex>
  );
}
