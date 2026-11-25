import { ProCard, ProTable } from '@ant-design/pro-components';
import { Alert, App, Badge, Button, Empty, Flex, Tooltip, Typography } from 'antd';
import { useState } from 'react';
import { msg, say, useText, type Message, type Texts } from '../language';
import { PackagesError, type Dependency, type PackagesApi, type PackageState } from './api';
import { short } from './parts';

/** A dependency's state as a status with its word, and what it means on hover: the status vocabulary. */
const STATE: Record<Dependency['state'], { status: 'success' | 'warning' | 'error'; text: Message; hint: Message }> = {
  pinned: { status: 'success', text: msg('packages.dependency.pinned'), hint: msg('packages.dependency.pinned.hint') },
  unpinned: { status: 'warning', text: msg('packages.dependency.unpinned'), hint: msg('packages.dependency.unpinned.hint') },
  moved: { status: 'warning', text: msg('packages.dependency.moved'), hint: msg('packages.dependency.moved.hint') },
  changed: { status: 'warning', text: msg('packages.dependency.changed'), hint: msg('packages.dependency.changed.hint') },
  missing: { status: 'error', text: msg('packages.dependency.missing'), hint: msg('packages.dependency.missing.hint') },
};

/**
 * Why Lock is disabled on a server that takes writes when `writable`; null when it is not. A lock with every dependency
 * pinned, or none declared, still writes package.lock anew, which drops the pins of dependencies package.yaml no longer
 * declares.
 */
function lockRefused({ t }: Texts, writable: boolean): string | null {
  if (!writable) return t('packages.readOnly');
  return null;
}

/** A folder or repository at a commit, the commit short, with the whole id, and the content digest when given, on hover. */
function At({ name, commit, content }: { name: string; commit: string | null; content?: string }) {
  const { t } = useText();
  return (
    <Typography.Text>
      {name}{' '}
      {commit === null ? (
        <Typography.Text type="secondary">{t('packages.dependency.noCommit')}</Typography.Text>
      ) : (
        <Typography.Text code title={content === undefined ? commit : `${commit}\n${content}`}>
          {short(commit)}
        </Typography.Text>
      )}
    </Typography.Text>
  );
}

/**
 * The dependencies of the package `repository` in a titled card: each one's name and version from `package.yaml`, its
 * pin from `package.lock`, its candidate, the folder of the working folder that Lock would pin, at its `HEAD`, its
 * state, and the commit the node resolved for it when it runs the package. Lock, the card's action, is disabled with
 * the reason as its title when the server takes no writes. Its pins are a notification and
 * its answer's state goes to `onState`; a refusal stays above the table, with the problems `rail8 lock` named, until it
 * is dismissed or Lock runs again. A refusal because the package or a candidate changed reads the package again.
 */
export function DependenciesCard({ api, repository, state, writable, onState }: {
  api: PackagesApi;
  repository: string;
  state: PackageState;
  writable: boolean;
  onState: (next: PackageState | null) => void;
}) {
  const { notification } = App.useApp();
  const [locking, setLocking] = useState(false);
  const [refusal, setRefusal] = useState<PackagesError | null>(null);
  const texts = useText();
  const { t } = texts;
  const refused = lockRefused(texts, writable);

  const lock = async () => {
    setRefusal(null);
    setLocking(true);
    try {
      const answer = await api.lock(repository, {
        manifestTag: state.manifestTag,
        lockTag: state.lockTag,
        dependencies: state.dependencies.map((d) => ({ name: d.name, folder: d.candidate?.folder ?? null, head: d.candidate?.head ?? null })),
      });
      onState(answer.package);
      notification.success({
        key: `lock:${repository}`,
        title: t('packages.lock.done', { count: answer.pins.length }),
        description: answer.pins.map((p) => t('packages.lock.pin', { name: p.name, version: p.version, repository: p.repository, commit: short(p.commit) })).join('; '),
        duration: 8,
        role: 'status',
        closable: { 'aria-label': t('packages.lock.dismiss') },
      });
    } catch (error) {
      const found = error instanceof PackagesError ? error : new PackagesError(0, String(error));
      setRefusal(found);
      if (found.status === 409 || found.status === 412) onState(null);
    } finally {
      setLocking(false);
    }
  };

  return (
    <ProCard
      title={<h2>{t('packages.dependencies')}</h2>}
      headerBordered
      extra={
        <Button loading={locking} disabled={refused !== null} title={refused ?? undefined} onClick={() => void lock()}>
          {t('packages.lock')}
        </Button>
      }
    >
      <Flex vertical gap="middle">
        {refusal !== null && (
          <Alert
            type="error"
            showIcon
            title={t('packages.lock.refused')}
            closable={{ 'aria-label': t('packages.refusal.dismiss'), onClose: () => setRefusal(null) }}
            description={
              <>
                <Typography.Paragraph style={{ marginBottom: 0 }}>{refusal.message}</Typography.Paragraph>
                {refusal.problems !== undefined && (
                  <ul aria-label={t('packages.lock.problems')} style={{ margin: 0 }}>
                    {refusal.problems.map((problem, n) => (
                      <li key={n}>{problem}</li>
                    ))}
                  </ul>
                )}
                {refusal.output !== undefined && refusal.output !== '' && (
                  <pre aria-label={t('packages.lock.output')} style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
                    {refusal.output}
                  </pre>
                )}
              </>
            }
          />
        )}
        {state.dependencies.length === 0 ? (
          <Empty description={t('packages.dependencies.none')} />
        ) : (
          <ProTable<Dependency>
            aria-label={t('packages.dependencies')}
            rowKey="name"
            size="small"
            dataSource={state.dependencies}
            search={false}
            options={false}
            toolBarRender={false}
            pagination={false}
            cardProps={false}
            columns={[
              { title: t('packages.dependency'), dataIndex: 'name' },
              { title: t('packages.version'), dataIndex: 'version', width: 112 },
              { title: t('packages.pin'), key: 'pin', render: (_, d) => (d.pin === null ? '-' : <At name={d.pin.repository} commit={d.pin.commit} content={d.pin.content} />) },
              { title: t('packages.dependency.candidate'), key: 'candidate', render: (_, d) => (d.candidate === null ? '-' : <At name={d.candidate.folder} commit={d.candidate.head} />) },
              {
                title: t('packages.dependency.state'),
                key: 'state',
                width: 128,
                render: (_, d) => (
                  <Tooltip title={say(texts, STATE[d.state].hint)}>
                    <Badge status={STATE[d.state].status} text={say(texts, STATE[d.state].text)} />
                  </Tooltip>
                ),
              },
              {
                title: t('packages.dependency.running'),
                key: 'running',
                width: 112,
                render: (_, d) =>
                  d.running === undefined ? (
                    '-'
                  ) : (
                    <Typography.Text code title={`${d.running.commit}\n${d.running.content}`}>
                      {short(d.running.commit)}
                    </Typography.Text>
                  ),
              },
            ]}
          />
        )}
      </Flex>
    </ProCard>
  );
}
