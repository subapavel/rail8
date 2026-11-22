import { ReloadOutlined } from '@ant-design/icons';
import { ProTable } from '@ant-design/pro-components';
import { Alert, Button, Empty, Flex, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { ReadSpinner } from '../ReadSpinner';
import type { EndpointList as Listed, EndpointsApi } from './api';

export interface EndpointListProps {
  api: EndpointsApi;
  /** Opens the endpoint file at this path, whose id is `id`, in a tab of its own. */
  onOpen: (path: string, id: string) => void;
}

type Result = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; list: Listed };

/**
 * The endpoint files of the working folder: each one's id, scheme, direction and path, the id opening its settings form
 * in a tab of its own; a file that cannot be read shows why. Without a working folder it says how to set one.
 */
export function EndpointList({ api, onOpen }: EndpointListProps) {
  const [result, setResult] = useState<Result>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const refresh = useCallback(() => setAsked((n) => n + 1), []);

  useEffect(() => {
    let current = true;
    api.list().then(
      (list) => current && setResult({ kind: 'read', list }),
      (error: unknown) => current && setResult({ kind: 'failed', message: error instanceof Error ? error.message : String(error) }),
    );
    return () => {
      current = false;
    };
  }, [api, asked]);

  let body;
  if (result.kind === 'reading') {
    body = <ReadSpinner label="Reading the working folder" />;
  } else if (result.kind === 'failed') {
    body = <Alert showIcon type="error" title="The working folder could not be read" description={result.message} />;
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
  } else if (result.list.items.length === 0) {
    body = (
      <Empty
        description={
          <>
            <Typography.Title level={2}>No endpoint files</Typography.Title>
            The working folder holds no YAML file of the kind endpoint.
          </>
        }
      />
    );
  } else {
    body = (
      <Flex vertical gap="middle">
        {result.list.truncated && (
          <Alert
            type="warning"
            showIcon
            title="Not every file of the working folder was looked at"
            description="The working folder holds more YAML files than are read; only the first ones are listed."
          />
        )}
        <ProTable<Listed['items'][number]>
          aria-label="Endpoint files"
          rowKey="path"
          size="small"
          dataSource={result.list.items}
          search={false}
          options={false}
          toolBarRender={false}
          pagination={false}
          columns={[
            {
              title: 'Endpoint',
              key: 'endpoint',
              render: (_, item) =>
                'problem' in item ? (
                  <Typography.Text type="danger">{item.problem}</Typography.Text>
                ) : (
                  <Button type="link" onClick={() => onOpen(item.path, item.id)}>
                    {item.id}
                  </Button>
                ),
            },
            { title: 'Scheme', dataIndex: 'scheme' },
            { title: 'Direction', dataIndex: 'direction' },
            { title: 'File', dataIndex: 'path' },
          ]}
        />
      </Flex>
    );
  }

  return (
    <Flex vertical gap="middle">
      <Flex align="center" gap="small" wrap>
        <Typography.Title level={1} style={{ margin: 0 }}>
          Endpoints
        </Typography.Title>
        {result.kind === 'read' && result.list.configured && !result.list.editable && <Tag>Read-only</Tag>}
        <Flex gap="small" style={{ marginInlineStart: 'auto' }}>
          <Button icon={<ReloadOutlined aria-hidden />} onClick={refresh}>
            Refresh
          </Button>
        </Flex>
      </Flex>
      <Typography.Text type="secondary">The endpoint files of the working folder. Each one opens in a tab of its own, read-only until you choose Edit.</Typography.Text>
      {body}
    </Flex>
  );
}
