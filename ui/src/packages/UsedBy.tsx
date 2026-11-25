import { ProCard, ProTable } from '@ant-design/pro-components';
import { Alert, Badge, Empty, Flex, Tooltip, Typography } from 'antd';
import { useText, type Texts } from '../language';
import type { Consumer, PinRelation, UsedBy } from './api';
import { Age, short } from './parts';

/** The commit a pin is related to: the clone's `HEAD`, or the commit the node runs of the package. */
type Base = { head: true } | { runs: string };

/**
 * How a pin relates to the commit `base`, as a status dot with its word and a hint for hover, in `texts`' language. The
 * word when the pin is that commit is "At HEAD" or "Same as running".
 */
function relationStatus({ t }: Texts, found: PinRelation, base: Base): { status: 'success' | 'warning' | 'processing' | 'default'; text: string; hint: string } {
  const count = found.commits;
  if ('head' in base) {
    if (found.relation === 'same') return { status: 'success', text: t('packages.usedBy.atHead'), hint: t('packages.usedBy.same.head') };
    if (found.relation === 'behind') return { status: 'warning', text: t('packages.run.behind', { count }), hint: t('packages.usedBy.behind.head', { count }) };
    if (found.relation === 'ahead') return { status: 'processing', text: t('packages.run.ahead', { count }), hint: t('packages.usedBy.ahead.head', { count }) };
    return { status: 'default', text: t('packages.usedBy.otherLine'), hint: t('packages.usedBy.other.head') };
  }
  const commit = base.runs;
  if (found.relation === 'same') return { status: 'success', text: t('packages.usedBy.sameAsRunning'), hint: t('packages.usedBy.same.runs', { commit }) };
  if (found.relation === 'behind') return { status: 'warning', text: t('packages.run.behind', { count }), hint: t('packages.usedBy.behind.runs', { count, commit }) };
  if (found.relation === 'ahead') return { status: 'processing', text: t('packages.run.ahead', { count }), hint: t('packages.usedBy.ahead.runs', { count, commit }) };
  return { status: 'default', text: t('packages.usedBy.otherLine'), hint: t('packages.usedBy.other.runs', { commit }) };
}

/** How a pin relates to the commit `base`, or `-` when there is none to relate it to. */
function Relation({ found, base }: { found: PinRelation | undefined; base: Base | null }) {
  const texts = useText();
  if (found === undefined || base === null) return '-';
  const { status, text, hint } = relationStatus(texts, found, base);
  return (
    <Tooltip title={hint}>
      <Badge status={status} text={text} />
    </Tooltip>
  );
}

/**
 * Where the package is used as a dependency, in a titled card: one row per package the node runs whose package.lock
 * pins this one, with the version and commit it pins, that commit's subject and age when the clone holds it, and how
 * the pin relates to the commit the node runs of this package and to `HEAD`. When the node does not answer, the card
 * says so and lists nothing.
 */
export function UsedByCard({ usedBy, now }: { usedBy: UsedBy; now: Date }) {
  const { t } = useText();
  let content;
  if (usedBy.node === 'unreachable') {
    content = <Alert type="warning" showIcon title={t('packages.node.silent', { node: usedBy.host })} description={usedBy.error} />;
  } else if (usedBy.consumers.length === 0) {
    content = <Empty description={t('packages.usedBy.none', { node: usedBy.host })} />;
  } else {
    const runs = usedBy.runs;
    content = (
      <Flex vertical gap="small">
        {runs === null && <Typography.Text type="secondary">{t('packages.usedBy.notRunning', { node: usedBy.host })}</Typography.Text>}
        <ProTable<Consumer>
          aria-label={t('packages.usedBy')}
          rowKey={(c) => `${c.name} ${c.repository}`}
          size="small"
          dataSource={usedBy.consumers}
          search={false}
          options={false}
          toolBarRender={false}
          pagination={false}
          cardProps={false}
          columns={[
            { title: t('frame.package'), dataIndex: 'name' },
            { title: t('packages.version'), dataIndex: 'version', width: 112 },
            {
              title: t('packages.pin'),
              key: 'pin',
              render: (_, c) => (
                <Typography.Text>
                  {c.pin.version}{' '}
                  <Typography.Text code title={`${c.pin.commit}\n${c.pin.content}`}>
                    {short(c.pin.commit)}
                  </Typography.Text>
                </Typography.Text>
              ),
            },
            {
              title: t('packages.usedBy.commit'),
              key: 'commit',
              render: (_, c) =>
                c.commit === null ? (
                  <Typography.Text type="secondary">{t('packages.usedBy.notInClone')}</Typography.Text>
                ) : (
                  <Flex vertical>
                    <Typography.Text>{c.commit.subject}</Typography.Text>
                    <Typography.Text type="secondary">
                      <Age at={c.commit.at} now={now} />
                    </Typography.Text>
                  </Flex>
                ),
            },
            {
              title: t('packages.usedBy.againstRuns'),
              key: 'runs',
              width: 160,
              render: (_, c) => <Relation found={c.runs} base={runs === null ? null : { runs: short(runs) }} />,
            },
            { title: t('packages.usedBy.againstHead'), key: 'head', width: 144, render: (_, c) => <Relation found={c.head} base={{ head: true }} /> },
          ]}
        />
      </Flex>
    );
  }
  return (
    <ProCard
      title={<h2>{t('packages.usedBy')}</h2>}
      headerBordered
      extra={usedBy.node === 'answered' && <Typography.Text type="secondary">{t('packages.usedBy.extra', { node: usedBy.host })}</Typography.Text>}
    >
      {content}
    </ProCard>
  );
}
