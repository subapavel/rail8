import { CloudDownloadOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { ProCard, ProTable } from '@ant-design/pro-components';
import { Alert, Badge, Button, Empty, Flex, Tag, Tooltip, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { GitCredentialsAlert } from '../GitCredentialsAlert';
import { useText } from '../language';
import { Page } from '../Page';
import { ReadSpinner } from '../ReadSpinner';
import { useMay } from '../SessionGate';
import type { PackageList as Listed, PackageRow, PackagesApi } from './api';
import { GetPackageDialog } from './GetPackageDialog';
import { NewPackageDialog } from './NewPackageDialog';
import { Age, BranchFact, code, deployedStatus, short } from './parts';
import { MAX_PACKAGES } from './rules';

export interface PackageListProps {
  api: PackagesApi;
  /** The clock the ages of commits and deploys are told against. */
  now: () => Date;
  /** Opens the package whose clone is the folder `repository` in a tab of its own, on a server that takes writes when `writable`. */
  onOpen: (repository: string, writable: boolean) => void;
}

type Result = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; list: Listed; at: Date };

/** The name a row goes by: its package's name, or its folder's when `package.yaml` cannot be read. */
const nameOf = (row: PackageRow) => row.package?.name ?? row.repository;

/** Whether the row's folder, package name or description holds `term`, which is lower case; every row holds the empty term. */
const holds = (row: PackageRow, term: string) =>
  term === '' || [row.repository, row.package?.name, row.package?.description].some((text) => text?.toLowerCase().includes(term));

/** Orders two texts as the language orders them. */
const byText = (a: string, b: string) => a.localeCompare(b);

/** The package's name, a link that opens its tab, with its version; below it the folder when it is named otherwise, or why `package.yaml` cannot be read. */
function PackageName({ row, onOpen }: { row: PackageRow; onOpen: () => void }) {
  const { t } = useText();
  return (
    <Flex vertical>
      <Flex gap="small" align="baseline">
        <Button type="link" className="rail8-link" onClick={onOpen}>
          {nameOf(row)}
        </Button>
        {row.package !== null && <Typography.Text type="secondary">{row.package.version}</Typography.Text>}
      </Flex>
      {row.package !== null && row.package.name !== row.repository && (
        <Typography.Text type="secondary">{t('packages.list.inFolder', { folder: row.repository })}</Typography.Text>
      )}
      {row.package === null && <Typography.Text type="danger">{row.problem}</Typography.Text>}
    </Flex>
  );
}

/** Who made the last commit and when, in one line, with its id and subject on hover. */
function LastCommit({ row, at }: { row: PackageRow; at: Date }) {
  const { t, rich } = useText();
  if (row.head === null) return <Typography.Text type="secondary">{t('packages.commit.none')}</Typography.Text>;
  return (
    <Tooltip title={`${short(row.head.id)} ${row.head.subject}`}>
      <Typography.Text>{rich('packages.list.commit', { author: row.head.author, age: <Age at={row.head.at} now={at} /> })}</Typography.Text>
    </Tooltip>
  );
}

/**
 * What the node runs of the package, in one line: its status, with what to do or the problems of a failed deploy on
 * hover, then the commit, on how many nodes of the pool it runs when the node answers its pool route, and when it was
 * deployed. A session that may not read deployments sees why instead.
 */
function DeployedLine({ row, at }: { row: PackageRow; at: Date }) {
  const texts = useText();
  const { t, rich } = texts;
  const readable = useMay('deployment.read');
  if (readable !== true) return <Typography.Text type="secondary">{readable}</Typography.Text>;
  const deployed = row.deployed;
  if (deployed.node === 'answered' && deployed.relation === 'none') return <Badge status="default" text={t('packages.run.notRunning')} />;
  const { status, text, hint } = deployedStatus(texts, row);
  if (deployed.node === 'unreachable') return <Badge status={status} text={text} />;
  const problems = deployed.problems.length === 0 ? undefined : (
    <Flex vertical>
      {deployed.problems.map((problem, n) => (
        <span key={n}>{problem}</span>
      ))}
    </Flex>
  );
  const pool = deployed.pool !== undefined && !('error' in deployed.pool) ? deployed.pool : undefined;
  const commit = short(deployed.commit);
  const age = <Age at={deployed.deployedAt} now={at} />;
  let line;
  if (pool === undefined) {
    line = deployed.deployedAt === '' ? rich('packages.deployed.unknownTime', { code, commit }) : rich('packages.list.deployed', { code, commit, age });
  } else {
    const count = pool.nodes.filter((n) => n.relation !== 'none' && n.commit === deployed.commit && n.state === 'RUNNING').length;
    const total = pool.nodes.length;
    line = deployed.deployedAt === '' ? rich('packages.pool.nodesAt', { code, commit, count, total }) : rich('packages.list.deployedOn', { code, commit, count, total, age });
  }
  return (
    <Flex gap="small" wrap align="center">
      <Tooltip title={hint ?? problems}>
        <Badge status={status} text={text} />
      </Tooltip>
      <Typography.Text type="secondary">{line}</Typography.Text>
    </Flex>
  );
}

/**
 * The packages of the working folder, as a list page: Refresh, Get a package… and New package in the page's corner,
 * the last two off on a server that takes no writes; above the list, a warning while the node does not answer or finds
 * its git credentials refused; and one card with the table of the packages, one row each, which a search by name and
 * description narrows as it is typed, its heading counting the rows shown. A row holds the package's name, a link that
 * opens its tab, with its version and its folder when that is named otherwise; its description; its branch; its last
 * commit; how many of its files changed; and what the node runs of it in one line. Package and Last commit sort.
 * A folder of the working folder that is no package is not listed. Without a working folder it says how to set one; a
 * refusal, such as git missing, shows why.
 */
export function PackageList({ api, now, onOpen }: PackageListProps) {
  const [result, setResult] = useState<Result>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const refresh = useCallback(() => setAsked((n) => n + 1), []);
  const [getting, setGetting] = useState(false);
  const [making, setMaking] = useState(false);
  const [query, setQuery] = useState('');
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

  const refreshButton = (
    <Tooltip key="refresh" title={t('packages.refresh')}>
      <Button aria-label={t('packages.refresh')} icon={<ReloadOutlined aria-hidden />} onClick={refresh} />
    </Tooltip>
  );

  const newButton = (writable: boolean, key = 'new') => (
    <Button key={key} type="primary" icon={<PlusOutlined aria-hidden />} disabled={!writable} onClick={() => setMaking(true)}>
      {t('packages.new')}
    </Button>
  );

  const getButton = (writable: boolean, key = 'get') => (
    <Button key={key} icon={<CloudDownloadOutlined aria-hidden />} disabled={!writable} onClick={() => setGetting(true)}>
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
    const term = query.trim().toLowerCase();
    const shown = list.packages.filter((row) => holds(row, term));
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
          dataSource={shown}
          search={false}
          options={false}
          pagination={false}
          headerTitle={<h2>{t('packages.list.count', { count: shown.length })}</h2>}
          toolbar={{
            search: {
              'aria-label': t('packages.search'),
              placeholder: t('packages.search.placeholder'),
              allowClear: true,
              value: query,
              onChange: (event) => setQuery(event.target.value),
              onSearch: (value) => setQuery(value),
            },
          }}
          locale={{
            emptyText:
              list.packages.length > 0 ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('packages.search.none', { query: query.trim() })} />
              ) : (
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
                      {getButton(true, 'get-empty')}
                      {newButton(true, 'new-empty')}
                    </Flex>
                  )}
                </Empty>
              ),
          }}
          columns={[
            {
              title: t('frame.package'),
              key: 'package',
              defaultSortOrder: 'ascend',
              sorter: (a, b) => byText(nameOf(a), nameOf(b)) || byText(a.repository, b.repository),
              render: (_, row) => <PackageName row={row} onOpen={() => onOpen(row.repository, list.writable)} />,
            },
            {
              title: t('packages.description'),
              key: 'description',
              dataIndex: ['package', 'description'],
              ellipsis: { tooltip: true },
            },
            { title: t('packages.branch'), key: 'branch', width: 128, render: (_, row) => <BranchFact state={row} /> },
            {
              title: t('packages.lastCommit'),
              key: 'head',
              width: 208,
              sorter: (a, b) => (a.head === null ? 0 : Date.parse(a.head.at)) - (b.head === null ? 0 : Date.parse(b.head.at)),
              render: (_, row) => <LastCommit row={row} at={at} />,
            },
            {
              title: t('packages.changes'),
              key: 'changed',
              width: 112,
              render: (_, row) => (row.changed === 0 ? '-' : <Badge status="warning" text={t('packages.list.changed', { count: row.changed })} />),
            },
            { title: t('packages.deployed'), key: 'deployed', width: 352, render: (_, row) => <DeployedLine row={row} at={at} /> },
          ]}
        />
      </Flex>
    );
  }

  const writable = result.kind === 'read' && result.list.configured ? result.list.writable : undefined;
  return (
    <Page
      title={t('packages.screen.title')}
      tags={writable === false ? <Tag>{t('packages.readOnly.tag')}</Tag> : undefined}
      extra={writable === undefined ? [refreshButton] : [refreshButton, getButton(writable), newButton(writable)]}
    >
      {body}
      {making && <NewPackageDialog api={api} onMade={refresh} onPackage={(repository) => onOpen(repository, true)} onClose={() => setMaking(false)} />}
      {getting && <GetPackageDialog api={api} onCloned={refresh} onPackage={(repository) => onOpen(repository, true)} onClose={() => setGetting(false)} />}
    </Page>
  );
}
