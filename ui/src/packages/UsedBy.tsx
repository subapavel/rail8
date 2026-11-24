import { ProCard, ProTable } from '@ant-design/pro-components';
import { Alert, Badge, Empty, Flex, Tooltip, Typography } from 'antd';
import type { Consumer, PinRelation, UsedBy } from './api';
import { Age, short } from './parts';

/**
 * How a pin relates to a commit, as a status dot with its word and a hint for hover. `same` is the word when the pin is
 * that commit, and `base` names that commit in the hint, such as `HEAD`.
 */
function relationStatus(found: PinRelation, same: string, base: string): { status: 'success' | 'warning' | 'processing' | 'default'; text: string; hint: string } {
  const commits = found.commits === 1 ? '1 commit' : `${found.commits} commits`;
  if (found.relation === 'same') return { status: 'success', text: same, hint: `The pin is ${base}.` };
  if (found.relation === 'behind') return { status: 'warning', text: `Behind ${found.commits}`, hint: `The pin lacks ${commits} of ${base}: lock and deploy the package again to take them.` };
  if (found.relation === 'ahead') return { status: 'processing', text: `Ahead ${found.commits}`, hint: `The pin holds ${commits} that ${base} lacks.` };
  return { status: 'default', text: 'Other line', hint: `The pin is not in the history of ${base}, or the clone does not hold it.` };
}

/** How a pin relates to a commit, or `-` when there is none to relate it to. */
function Relation({ found, same, base }: { found: PinRelation | undefined; same: string; base: string }) {
  if (found === undefined) return '-';
  const { status, text, hint } = relationStatus(found, same, base);
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
  let content;
  if (usedBy.node === 'unreachable') {
    content = <Alert type="warning" showIcon title={`The node at ${usedBy.host} does not answer`} description={usedBy.error} />;
  } else if (usedBy.consumers.length === 0) {
    content = <Empty description={`No package the node at ${usedBy.host} runs pins this one.`} />;
  } else {
    const runs = usedBy.runs;
    content = (
      <Flex vertical gap="small">
        {runs === null && <Typography.Text type="secondary">The node at {usedBy.host} runs no commit of this package: each pin is compared with HEAD only.</Typography.Text>}
        <ProTable<Consumer>
          aria-label="Used by"
          rowKey={(c) => `${c.name} ${c.repository}`}
          size="small"
          dataSource={usedBy.consumers}
          search={false}
          options={false}
          toolBarRender={false}
          pagination={false}
          cardProps={false}
          columns={[
            { title: 'Package', dataIndex: 'name' },
            { title: 'Version', dataIndex: 'version', width: 112 },
            {
              title: 'Pin',
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
              title: 'Pinned commit',
              key: 'commit',
              render: (_, c) =>
                c.commit === null ? (
                  <Typography.Text type="secondary">Not in the clone</Typography.Text>
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
              title: 'Against what runs',
              key: 'runs',
              width: 160,
              render: (_, c) => <Relation found={c.runs} same="Same as running" base={runs === null ? 'what runs' : `the commit ${short(runs)} the node runs`} />,
            },
            { title: 'Against HEAD', key: 'head', width: 144, render: (_, c) => <Relation found={c.head} same="At HEAD" base="HEAD" /> },
          ]}
        />
      </Flex>
    );
  }
  return (
    <ProCard title={<h2>Used by</h2>} headerBordered extra={usedBy.node === 'answered' && <Typography.Text type="secondary">Packages the node at {usedBy.host} runs that pin this one</Typography.Text>}>
      {content}
    </ProCard>
  );
}
