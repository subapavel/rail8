import { CloudDownloadOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { ProCard, ProTable } from '@ant-design/pro-components';
import { Alert, Button, Empty, Flex, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { GitCredentialsAlert } from '../GitCredentialsAlert';
import { Page } from '../Page';
import { ReadSpinner } from '../ReadSpinner';
import type { PackageList as Listed, PackageRow, PackagesApi } from './api';
import { GetPackageDialog } from './GetPackageDialog';
import { NewPackageDialog } from './NewPackageDialog';
import { BranchFact, CommitFact, DeployedFact, PoolFact, UpstreamFact } from './parts';
import { MAX_PACKAGES } from './rules';

export interface PackageListProps {
  api: PackagesApi;
  /** The clock the ages of commits and fetches are told against. */
  now: () => Date;
  /** Opens the package whose clone is the folder `repository` in a tab of its own, on a server that takes writes when `writable`. */
  onOpen: (repository: string, writable: boolean) => void;
}

type Result = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; list: Listed; at: Date };

/**
 * The packages of the working folder, as a list page: a card with a table of one row per package, with Refresh, New
 * package and Get a package… in the card's corner; New package and Get a package… are off on a server that takes no writes. Each row holds the repository, which opens the package's tab, the package's name and version, the
 * branch, the last commit, ahead and behind the upstream as of the last fetch, the changed files, and what the node it
 * names runs of it. Above it, a warning while the node finds its git credentials refused. The folders that are no package are
 * listed in a card below, each with the reason. Without a working folder it says how to set one; a refusal, such as git
 * missing, shows why.
 */
export function PackageList({ api, now, onOpen }: PackageListProps) {
  const [result, setResult] = useState<Result>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const refresh = useCallback(() => setAsked((n) => n + 1), []);
  const [getting, setGetting] = useState(false);
  const [making, setMaking] = useState(false);

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

  const refreshButton = (
    <Button key="refresh" icon={<ReloadOutlined aria-hidden />} onClick={refresh}>
      Refresh
    </Button>
  );

  const newButton = (writable: boolean, key = 'new') => (
    <Button key={key} icon={<PlusOutlined aria-hidden />} disabled={!writable} onClick={() => setMaking(true)}>
      New package
    </Button>
  );

  const getButton = (writable: boolean, key = 'get') => (
    <Button key={key} type="primary" icon={<CloudDownloadOutlined aria-hidden />} disabled={!writable} onClick={() => setGetting(true)}>
      Get a package…
    </Button>
  );

  let body;
  if (result.kind === 'reading') {
    body = <ReadSpinner label="Reading the packages" />;
  } else if (result.kind === 'failed') {
    body = <Alert showIcon type="error" title="The packages could not be read" description={result.message} action={<Button onClick={refresh}>Try again</Button>} />;
  } else if (!result.list.configured) {
    body = (
      <ProCard>
        <Empty
          description={
            <>
              <Typography.Title level={2}>No working folder</Typography.Title>
              Start the UI with <code>RAIL8_WORKSPACE</code> set to the folder that holds your packages, such as <code>RAIL8_WORKSPACE=$HOME/rail8-workspace npm run dev</code>.
            </>
          }
        />
      </ProCard>
    );
  } else {
    const { list, at } = result;
    const silent = list.packages.map((p) => p.deployed).find((d) => d.node === 'unreachable');
    body = (
      <Flex vertical gap="large">
        {list.truncated && (
          <Alert
            type="warning"
            showIcon
            title="Not every folder of the working folder is listed"
            description={`The working folder holds more than ${MAX_PACKAGES} packages, or more than ${MAX_PACKAGES} folders that are no package; only the first ones are listed.`}
          />
        )}
        {silent !== undefined && <Alert type="warning" showIcon title={`The node at ${list.node} does not answer`} description={silent.error} />}
        <GitCredentialsAlert />
        <ProTable<PackageRow>
          aria-label="Packages"
          rowKey="repository"
          size="small"
          dataSource={list.packages}
          search={false}
          options={false}
          pagination={false}
          headerTitle={<h2>Packages of the working folder</h2>}
          toolBarRender={() => [
            refreshButton,
            newButton(list.writable),
            getButton(list.writable),
          ]}
          locale={{
            emptyText: (
              <Empty
                description={
                  <>
                    <Typography.Title level={2}>No packages</Typography.Title>
                    The working folder holds no clone with <code>package.yaml</code> at its root. Make one with New package, or get one from its git host with Get a package….
                  </>
                }
              >
                {list.writable && (
                  <Flex gap="small" justify="center">
                    {newButton(true, 'new-empty')}
                    {getButton(true, 'get-empty')}
                  </Flex>
                )}
              </Empty>
            ),
          }}
          columns={[
            {
              title: 'Repository',
              dataIndex: 'repository',
              render: (_, row) => (
                <Button type="link" className="rail8-link" onClick={() => onOpen(row.repository, list.writable)}>
                  {row.repository}
                </Button>
              ),
            },
            {
              title: 'Package',
              key: 'package',
              render: (_, row) =>
                row.package === null ? <Typography.Text type="danger">{row.problem}</Typography.Text> : `${row.package.name} ${row.package.version}`,
            },
            { title: 'Branch', key: 'branch', render: (_, row) => <BranchFact state={row} /> },
            { title: 'Last commit', key: 'head', render: (_, row) => <CommitFact state={row} at={at} /> },
            { title: 'Upstream', key: 'upstream', render: (_, row) => <UpstreamFact state={row} at={at} /> },
            { title: 'Changed files', dataIndex: 'changed', align: 'right', width: 120 },
            {
              title: 'Deployed',
              key: 'deployed',
              render: (_, row) => (
                <Flex vertical gap="small">
                  <DeployedFact state={row} at={at} />
                  <PoolFact state={row} />
                </Flex>
              ),
            },
          ]}
        />
        {list.others.length > 0 && (
          <ProTable<Listed['others'][number]>
            aria-label="Folders that are no package"
            rowKey="folder"
            size="small"
            dataSource={list.others}
            search={false}
            options={false}
            pagination={false}
            headerTitle={<h2>Folders that are no package</h2>}
            toolbar={{ subTitle: 'Their files stay in the lists of flows, endpoints and mappings.' }}
            columns={[
              { title: 'Folder', dataIndex: 'folder' },
              { title: 'Why it is no package', dataIndex: 'reason' },
            ]}
          />
        )}
      </Flex>
    );
  }

  return (
    <Page
      title="Packages"
      tags={result.kind === 'read' && result.list.configured && !result.list.writable ? <Tag>Read-only</Tag> : undefined}
      content={
        <Flex vertical>
          <span>The packages of the working folder: each one a clone of its own, with its branch, its last commit and what the node runs of it.</span>
          {result.kind === 'read' && result.list.configured && (
            <span>
              Deployed state on the node at <Typography.Text code>{result.list.node}</Typography.Text>
              {result.list.packages.some((p) => p.deployed.node === 'answered' && p.deployed.pool !== undefined) && ', and on every node of its pool'}
            </span>
          )}
        </Flex>
      }
    >
      {body}
      {making && <NewPackageDialog api={api} onMade={refresh} onPackage={(repository) => onOpen(repository, true)} onClose={() => setMaking(false)} />}
      {getting && <GetPackageDialog api={api} onCloned={refresh} onPackage={(repository) => onOpen(repository, true)} onClose={() => setGetting(false)} />}
    </Page>
  );
}
