import { ReloadOutlined } from '@ant-design/icons';
import { ProTable } from '@ant-design/pro-components';
import { Alert, Button, Empty, Flex, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { ReadSpinner } from '../ReadSpinner';
import { ageOf } from './age';
import type { Busy, PackageList as Listed, PackageRow, PackagesApi } from './api';
import { MAX_PACKAGES } from './rules';

export interface PackageListProps {
  api: PackagesApi;
  /** The clock the ages of commits and fetches are told against. */
  now: () => Date;
  /** Opens the package whose clone is the folder `repository` in a tab of its own. */
  onOpen: (repository: string) => void;
}

type Result = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; list: Listed; at: Date };

export const BUSY: Record<Exclude<Busy, null>, string> = {
  merge: 'Merge in progress',
  rebase: 'Rebase in progress',
  'cherry-pick': 'Cherry-pick in progress',
  revert: 'Revert in progress',
  bisect: 'Bisect in progress',
  conflict: 'Conflict',
};

export const short = (id: string) => id.slice(0, 7);

/** When `at` was, as its age with the time itself on hover. */
export function Age({ at, now }: { at: string; now: Date }) {
  return <span title={at}>{ageOf(at, now)}</span>;
}

/** What the node runs of the row's repository, in the words of the Packages screen. */
export function DeployedState({ row, now }: { row: PackageRow; now: Date }) {
  const deployed = row.deployed;
  if (deployed.node === 'unreachable') return <Typography.Text type="secondary">Unknown</Typography.Text>;
  if (deployed.relation === 'none') return <Typography.Text type="secondary">Not running on {deployed.host}</Typography.Text>;
  const since = (
    <Typography.Text type="secondary">
      {short(deployed.commit)}, deployed {deployed.deployedAt === '' ? 'at an unknown time' : <Age at={deployed.deployedAt} now={now} />}
    </Typography.Text>
  );
  if (deployed.state === 'FAILED') {
    return (
      <Flex vertical gap="small">
        <Flex gap="small" wrap align="center">
          <Tag color="error">Failed</Tag>
          {since}
        </Flex>
        {deployed.problems.map((problem, n) => (
          <Typography.Text key={n} type="danger">
            {problem}
          </Typography.Text>
        ))}
      </Flex>
    );
  }
  let tag: ReactNode;
  if (deployed.relation === 'deployed') tag = row.changed > 0 ? <Tag color="warning">Deployed, with changes since</Tag> : <Tag color="success">Deployed</Tag>;
  else if (deployed.relation === 'behind') tag = <Tag color="warning">Behind {deployed.commits}</Tag>;
  else if (deployed.relation === 'ahead') tag = (
      <Tag color="processing" title="The node runs commits that HEAD lacks: pull them.">
        Ahead {deployed.commits}
      </Tag>
    );
  else tag = <Tag>Other commit</Tag>;
  return (
    <Flex gap="small" wrap align="center">
      {tag}
      {since}
    </Flex>
  );
}

/**
 * The packages of the working folder, one row each: the repository, which opens the package's tab, the package's name
 * and version, the branch, the last commit, ahead and behind the upstream as of the last fetch, the changed files, and
 * what the node it names runs of it. The folders that are no package are listed below, each with the reason. Without a working folder it
 * says how to set one; a refusal, such as git missing, shows why.
 */
export function PackageList({ api, now, onOpen }: PackageListProps) {
  const [result, setResult] = useState<Result>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const refresh = useCallback(() => setAsked((n) => n + 1), []);

  useEffect(() => {
    let current = true;
    api.list().then(
      (list) => current && setResult({ kind: 'read', list, at: now() }),
      (error: unknown) => current && setResult({ kind: 'failed', message: error instanceof Error ? error.message : String(error) }),
    );
    return () => {
      current = false;
    };
  }, [api, asked, now]);

  let body;
  if (result.kind === 'reading') {
    body = <ReadSpinner label="Reading the packages" />;
  } else if (result.kind === 'failed') {
    body = <Alert showIcon type="error" title="The packages could not be read" description={result.message} />;
  } else if (!result.list.configured) {
    body = (
      <Empty
        description={
          <>
            <Typography.Title level={2}>No working folder</Typography.Title>
            Start the UI with <code>RAIL8_WORKSPACE</code> set to the folder that holds your packages, such as <code>RAIL8_WORKSPACE=$HOME/rail8-workspace npm run dev</code>.
          </>
        }
      />
    );
  } else {
    const { list, at } = result;
    const silent = list.packages.map((p) => p.deployed).find((d) => d.node === 'unreachable');
    body = (
      <Flex vertical gap="middle">
        {list.truncated && (
          <Alert
            type="warning"
            showIcon
            title="Not every folder of the working folder is listed"
            description={`The working folder holds more than ${MAX_PACKAGES} packages, or more than ${MAX_PACKAGES} folders that are no package; only the first ones are listed.`}
          />
        )}
        <Typography.Text>
          Deployed state on the node at <Typography.Text code>{list.node}</Typography.Text>
        </Typography.Text>
        {silent !== undefined && <Alert type="warning" showIcon title={`The node at ${list.node} does not answer`} description={silent.error} />}
        {list.packages.length === 0 ? (
          <Empty
            description={
              <>
                <Typography.Title level={2}>No packages</Typography.Title>
                The working folder holds no clone with <code>package.yaml</code> at its root.
              </>
            }
          />
        ) : (
          <ProTable<PackageRow>
            aria-label="Packages"
            rowKey="repository"
            size="small"
            dataSource={list.packages}
            search={false}
            options={false}
            toolBarRender={false}
            pagination={false}
            columns={[
              {
                title: 'Repository',
                dataIndex: 'repository',
                render: (_, row) => (
                  <Button type="link" style={{ padding: 0 }} onClick={() => onOpen(row.repository)}>
                    {row.repository}
                  </Button>
                ),
              },
              {
                title: 'Package',
                key: 'package',
                render: (_, row) =>
                  row.package === null ? (
                    <Typography.Text type="danger">{row.problem}</Typography.Text>
                  ) : (
                    `${row.package.name} ${row.package.version}`
                  ),
              },
              {
                title: 'Branch',
                key: 'branch',
                render: (_, row) => (
                  <Flex gap="small" wrap align="center">
                    {row.branch ?? <Tag>Detached HEAD</Tag>}
                    {row.busy !== null && <Tag color="warning">{BUSY[row.busy]}</Tag>}
                  </Flex>
                ),
              },
              {
                title: 'Last commit',
                key: 'head',
                render: (_, row) =>
                  row.head === null ? (
                    <Typography.Text type="secondary">No commit yet</Typography.Text>
                  ) : (
                    <Flex vertical>
                      <Typography.Text ellipsis={{ tooltip: row.head.subject }} style={{ maxWidth: 320 }}>
                        {row.head.subject}
                      </Typography.Text>
                      <Typography.Text type="secondary">
                        <Typography.Text code>{short(row.head.id)}</Typography.Text> {row.head.author}, <Age at={row.head.at} now={at} />
                      </Typography.Text>
                    </Flex>
                  ),
              },
              {
                title: 'Upstream',
                key: 'upstream',
                render: (_, row) =>
                  row.upstream === null ? (
                    <Typography.Text type="secondary">No upstream</Typography.Text>
                  ) : (
                    <Flex vertical>
                      <span>
                        {row.upstream.ahead === 0 && row.upstream.behind === 0
                          ? `Up to date with ${row.upstream.name}`
                          : `${row.upstream.ahead} ahead, ${row.upstream.behind} behind ${row.upstream.name}`}
                      </span>
                      <Typography.Text type="secondary">
                        {row.upstream.fetchedAt === null ? (
                          'never fetched'
                        ) : (
                          <>
                            as of the fetch <Age at={row.upstream.fetchedAt} now={at} />
                          </>
                        )}
                      </Typography.Text>
                    </Flex>
                  ),
              },
              { title: 'Changed files', dataIndex: 'changed', align: 'right' },
              { title: 'Deployed', key: 'deployed', render: (_, row) => <DeployedState row={row} now={at} /> },
            ]}
          />
        )}
        {list.others.length > 0 && (
          <Flex vertical gap="small">
            <Typography.Title level={2} style={{ margin: 0 }}>
              Folders that are no package
            </Typography.Title>
            <Typography.Text type="secondary">Their files stay in the lists of flows, endpoints and mappings.</Typography.Text>
            <ProTable<Listed['others'][number]>
              aria-label="Folders that are no package"
              rowKey="folder"
              size="small"
              dataSource={list.others}
              search={false}
              options={false}
              toolBarRender={false}
              pagination={false}
              columns={[
                { title: 'Folder', dataIndex: 'folder' },
                { title: 'Why it is no package', dataIndex: 'reason' },
              ]}
            />
          </Flex>
        )}
      </Flex>
    );
  }

  return (
    <Flex vertical gap="middle">
      <Flex align="center" gap="small" wrap>
        <Typography.Title level={1} style={{ margin: 0 }}>
          Packages
        </Typography.Title>
        {result.kind === 'read' && result.list.configured && !result.list.writable && <Tag>Read-only</Tag>}
        <Flex gap="small" style={{ marginInlineStart: 'auto' }}>
          <Button icon={<ReloadOutlined aria-hidden />} onClick={refresh}>
            Refresh
          </Button>
        </Flex>
      </Flex>
      <Typography.Text type="secondary">
        The packages of the working folder: each one a clone of its own, with its branch, its last commit and what the node runs of it.
      </Typography.Text>
      {body}
    </Flex>
  );
}
