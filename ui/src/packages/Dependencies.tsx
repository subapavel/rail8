import { ProCard, ProTable } from '@ant-design/pro-components';
import { Alert, App, Badge, Button, Empty, Flex, Tooltip, Typography } from 'antd';
import { useState } from 'react';
import { PackagesError, type Dependency, type PackagesApi, type PackageState } from './api';
import { short } from './parts';

/** A dependency's state as a status with its word, and what it means on hover: the status vocabulary. */
const STATE: Record<Dependency['state'], { status: 'success' | 'warning' | 'error'; text: string; hint: string }> = {
  pinned: { status: 'success', text: 'Pinned', hint: 'package.lock pins the candidate at its HEAD.' },
  unpinned: { status: 'warning', text: 'Unpinned', hint: 'package.lock pins nothing for it yet: Lock pins the candidate.' },
  moved: { status: 'warning', text: 'Moved', hint: 'The candidate is another folder, or at another commit, than the pin: Lock pins it.' },
  changed: { status: 'warning', text: 'Changed', hint: 'The candidate’s files differ from its HEAD: commit them first, or Lock refuses it.' },
  missing: { status: 'error', text: 'Missing', hint: 'No folder of the working folder holds this name and version, or several do.' },
};

/** Why Lock is disabled for the package `state` on a server that takes writes when `writable`; null when it is not. */
function lockRefused(writable: boolean, state: PackageState): string | null {
  if (!writable) return 'The server shows the working folder read-only (RAIL8_READ_ONLY is true).';
  if (state.dependencies.length === 0) return 'Nothing to lock: package.yaml declares no dependency.';
  if (state.dependencies.every((d) => d.state === 'pinned')) return 'Nothing to lock: every dependency is pinned at its candidate’s HEAD.';
  return null;
}

/** A folder or repository at a commit, the commit short, with the whole id, and the content digest when given, on hover. */
function At({ name, commit, content }: { name: string; commit: string | null; content?: string }) {
  return (
    <Typography.Text>
      {name}{' '}
      {commit === null ? (
        <Typography.Text type="secondary">with no commit</Typography.Text>
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
 * the reason as its title when there is nothing to lock or the server takes no writes. Its pins are a notification and
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
  const refused = lockRefused(writable, state);

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
        title: `Locked ${answer.pins.length} ${answer.pins.length === 1 ? 'dependency' : 'dependencies'}: commit package.lock to keep the pins`,
        description: answer.pins.map((p) => `${p.name} ${p.version}: ${p.repository} at ${short(p.commit)}`).join('; '),
        duration: 8,
        role: 'status',
        closable: { 'aria-label': 'Dismiss lock notification' },
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
      title={<h2>Dependencies</h2>}
      headerBordered
      extra={
        <Button loading={locking} disabled={refused !== null} title={refused ?? undefined} onClick={() => void lock()}>
          Lock
        </Button>
      }
    >
      <Flex vertical gap="middle">
        {refusal !== null && (
          <Alert
            type="error"
            showIcon
            title="The lock was refused"
            closable={{ 'aria-label': 'Dismiss the refusal', onClose: () => setRefusal(null) }}
            description={
              <>
                <Typography.Paragraph style={{ marginBottom: 0 }}>{refusal.message}</Typography.Paragraph>
                {refusal.problems !== undefined && (
                  <ul aria-label="The problems rail8 lock named" style={{ margin: 0 }}>
                    {refusal.problems.map((problem, n) => (
                      <li key={n}>{problem}</li>
                    ))}
                  </ul>
                )}
                {refusal.output !== undefined && refusal.output !== '' && (
                  <pre aria-label="What rail8 lock said" style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
                    {refusal.output}
                  </pre>
                )}
              </>
            }
          />
        )}
        {state.dependencies.length === 0 ? (
          <Empty description="package.yaml declares no dependency." />
        ) : (
          <ProTable<Dependency>
            aria-label="Dependencies"
            rowKey="name"
            size="small"
            dataSource={state.dependencies}
            search={false}
            options={false}
            toolBarRender={false}
            pagination={false}
            cardProps={false}
            columns={[
              { title: 'Dependency', dataIndex: 'name' },
              { title: 'Version', dataIndex: 'version', width: 112 },
              { title: 'Pin', key: 'pin', render: (_, d) => (d.pin === null ? '-' : <At name={d.pin.repository} commit={d.pin.commit} content={d.pin.content} />) },
              { title: 'Candidate', key: 'candidate', render: (_, d) => (d.candidate === null ? '-' : <At name={d.candidate.folder} commit={d.candidate.head} />) },
              {
                title: 'State',
                key: 'state',
                width: 128,
                render: (_, d) => (
                  <Tooltip title={STATE[d.state].hint}>
                    <Badge status={STATE[d.state].status} text={STATE[d.state].text} />
                  </Tooltip>
                ),
              },
              {
                title: 'Running',
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
