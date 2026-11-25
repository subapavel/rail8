import { CloudDownloadOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { ProCard, ProTable } from '@ant-design/pro-components';
import { Alert, Badge, Button, Empty, Flex, Tag, Tooltip, Typography } from 'antd';
import { useCallback, useContext, useEffect, useState } from 'react';
import { ArtifactKinds } from '../artifacts';
import { GitCredentialsAlert } from '../GitCredentialsAlert';
import { useText } from '../language';
import { Page } from '../Page';
import { ReadSpinner } from '../ReadSpinner';
import type { PackageList as Listed, PackageRow, PackagesApi } from './api';
import { GetPackageDialog } from './GetPackageDialog';
import { listedArtifacts } from './FolderTab';
import { NewPackageDialog } from './NewPackageDialog';
import { BranchFact, code, CommitFact, DeployedFact, PoolFact, UpstreamFact } from './parts';
import { MAX_PACKAGES } from './rules';

export interface PackageListProps {
  api: PackagesApi;
  /** The clock the ages of commits and fetches are told against. */
  now: () => Date;
  /** Opens the package whose clone is the folder `repository` in a tab of its own, on a server that takes writes when `writable`. */
  onOpen: (repository: string, writable: boolean) => void;
  /** Opens the folder `folder`, which is no package but holds artifacts, in a tab of its own. */
  onOpenFolder: (folder: string, writable: boolean) => void;
}

type Result = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; list: Listed; at: Date };

/** The folders of the working folder that hold an artifact a kind lists, or why they could not be read; null while they are read. */
type Holding = { folders: ReadonlySet<string> } | { failed: string } | null;

/**
 * The packages of the working folder, as a list page: a card with a table of one row per package, with Refresh, New
 * package and Get a package… in the card's corner; New package and Get a package… are off on a server that takes no writes. Each row holds the repository, which opens the package's tab, the package's name and version, the
 * branch, the last commit, ahead and behind the upstream as of the last fetch, the changed files, and what the node it
 * names runs of it, marked when packages the node runs pin a commit of it behind that one, with their number. Above it, a warning while the node finds its git credentials refused. The folders that are no package are
 * listed in a card below, each with the reason; a folder that holds artifacts is a link that opens its tab, and when
 * the artifacts cannot be listed a warning says why. Without a working folder it says how to set one; a refusal, such
 * as git missing, shows why.
 */
export function PackageList({ api, now, onOpen, onOpenFolder }: PackageListProps) {
  const kinds = useContext(ArtifactKinds);
  const [result, setResult] = useState<Result>({ kind: 'reading' });
  const [holding, setHolding] = useState<Holding>(null);
  const [asked, setAsked] = useState(0);
  const refresh = useCallback(() => setAsked((n) => n + 1), []);
  const [getting, setGetting] = useState(false);
  const [making, setMaking] = useState(false);
  const { t, rich } = useText();

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

  useEffect(() => {
    const controller = new AbortController();
    listedArtifacts(kinds, controller.signal).then(
      (artifacts) => !controller.signal.aborted && setHolding({ folders: new Set(artifacts.map(({ path }) => path.slice(0, path.indexOf('/')))) }),
      (error: unknown) => !controller.signal.aborted && setHolding({ failed: error instanceof Error ? error.message : String(error) }),
    );
    return () => controller.abort();
  }, [kinds, asked]);

  const refreshButton = (
    <Button key="refresh" icon={<ReloadOutlined aria-hidden />} onClick={refresh}>
      {t('packages.refresh')}
    </Button>
  );

  const newButton = (writable: boolean, key = 'new') => (
    <Button key={key} icon={<PlusOutlined aria-hidden />} disabled={!writable} onClick={() => setMaking(true)}>
      {t('packages.new')}
    </Button>
  );

  const getButton = (writable: boolean, key = 'get') => (
    <Button key={key} type="primary" icon={<CloudDownloadOutlined aria-hidden />} disabled={!writable} onClick={() => setGetting(true)}>
      {t('packages.get.open')}
    </Button>
  );

  let body;
  if (result.kind === 'reading') {
    body = <ReadSpinner label={t('packages.reading')} />;
  } else if (result.kind === 'failed') {
    body = <Alert showIcon type="error" title={t('packages.unread')} description={result.message} action={<Button onClick={refresh}>{t('frame.tryAgain')}</Button>} />;
  } else if (!result.list.configured) {
    body = (
      <ProCard>
        <Empty
          description={
            <>
              <Typography.Title level={2}>{t('packages.workspace.none')}</Typography.Title>
              {rich('packages.workspace.none.hint', { code: (chunks) => <code>{chunks}</code> })}
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
            title={t('packages.list.truncated')}
            description={t('packages.list.truncated.description', { max: MAX_PACKAGES })}
          />
        )}
        {silent !== undefined && <Alert type="warning" showIcon title={t('packages.node.silent', { node: list.node })} description={silent.error} />}
        <GitCredentialsAlert />
        <ProTable<PackageRow>
          aria-label={t('packages.screen.title')}
          rowKey="repository"
          size="small"
          dataSource={list.packages}
          search={false}
          options={false}
          pagination={false}
          headerTitle={<h2>{t('packages.list.title')}</h2>}
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
                    <Typography.Title level={2}>{t('packages.list.none')}</Typography.Title>
                    {rich('packages.list.none.hint', { code: (chunks) => <code>{chunks}</code> })}
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
              title: t('packages.repository'),
              dataIndex: 'repository',
              render: (_, row) => (
                <Button type="link" className="rail8-link" onClick={() => onOpen(row.repository, list.writable)}>
                  {row.repository}
                </Button>
              ),
            },
            {
              title: t('frame.package'),
              key: 'package',
              render: (_, row) =>
                row.package === null ? <Typography.Text type="danger">{row.problem}</Typography.Text> : `${row.package.name} ${row.package.version}`,
            },
            { title: t('packages.branch'), key: 'branch', render: (_, row) => <BranchFact state={row} /> },
            { title: t('packages.lastCommit'), key: 'head', render: (_, row) => <CommitFact state={row} at={at} /> },
            { title: t('packages.upstream'), key: 'upstream', render: (_, row) => <UpstreamFact state={row} at={at} /> },
            { title: t('packages.changed'), dataIndex: 'changed', align: 'right', width: 120 },
            {
              title: t('packages.deployed'),
              key: 'deployed',
              render: (_, row) => (
                <Flex vertical gap="small">
                  <DeployedFact state={row} at={at} />
                  <PoolFact state={row} />
                  {row.pinnedBehind !== undefined && (
                    <Tooltip title={t('packages.pinnedBehind.hint', { count: row.pinnedBehind })}>
                      <Badge status="warning" text={t('packages.pinnedBehind', { count: row.pinnedBehind })} />
                    </Tooltip>
                  )}
                </Flex>
              ),
            },
          ]}
        />
        {list.others.length > 0 && (
          <ProTable<Listed['others'][number]>
            aria-label={t('packages.others')}
            rowKey="folder"
            size="small"
            dataSource={list.others}
            search={false}
            options={false}
            pagination={false}
            headerTitle={<h2>{t('packages.others')}</h2>}
            toolbar={{ subTitle: t('packages.others.hint') }}
            columns={[
              {
                title: t('packages.folder'),
                dataIndex: 'folder',
                render: (_, row) =>
                  holding !== null && 'folders' in holding && holding.folders.has(row.folder) ? (
                    <Button type="link" className="rail8-link" onClick={() => onOpenFolder(row.folder, list.writable)}>
                      {row.folder}
                    </Button>
                  ) : (
                    row.folder
                  ),
              },
              { title: t('packages.others.why'), dataIndex: 'reason' },
            ]}
          />
        )}
        {list.others.length > 0 && holding !== null && 'failed' in holding && (
          <Alert type="warning" showIcon title={t('packages.others.unread')} description={holding.failed} />
        )}
      </Flex>
    );
  }

  return (
    <Page
      title={t('packages.screen.title')}
      tags={result.kind === 'read' && result.list.configured && !result.list.writable ? <Tag>{t('packages.readOnly.tag')}</Tag> : undefined}
      content={
        <Flex vertical>
          <span>{t('packages.list.content')}</span>
          {result.kind === 'read' && result.list.configured && (
            <span>
              {result.list.packages.some((p) => p.deployed.node === 'answered' && p.deployed.pool !== undefined && !('error' in p.deployed.pool))
                ? rich('packages.list.node.pool', { code, node: result.list.node })
                : rich('packages.list.node', { code, node: result.list.node })}
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
