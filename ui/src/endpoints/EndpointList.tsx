import { ReloadOutlined } from '@ant-design/icons';
import { ProTable } from '@ant-design/pro-components';
import { Alert, Button, Empty, Flex, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useText } from '../language';
import { packageColumn } from '../packageColumn';
import { ReadSpinner } from '../ReadSpinner';
import type { EndpointList as Listed, EndpointsApi } from './api';

export interface EndpointListProps {
  api: EndpointsApi;
  /** Opens the endpoint file at this path, whose id is `id`, in a tab of its own. */
  onOpen: (path: string, id: string) => void;
}

type Result = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; list: Listed };

/**
 * The endpoint files of the working folder: each one's id, package, scheme, direction and path, the id opening its
 * settings form in a tab of its own, with a filter on the package; a file that cannot be read shows why. Without a working folder it says how to set one.
 */
export function EndpointList({ api, onOpen }: EndpointListProps) {
  const { t, rich } = useText();
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
    body = <ReadSpinner label={t('endpoints.folder.reading')} />;
  } else if (result.kind === 'failed') {
    body = <Alert showIcon type="error" title={t('endpoints.folder.unread')} description={result.message} />;
  } else if (!result.list.configured) {
    body = (
      <Empty
        description={
          <>
            <Typography.Title level={2}>{t('endpoints.folder.none')}</Typography.Title>
            {rich('endpoints.folder.none.hint', { code: (chunks) => <code>{chunks}</code> })}
          </>
        }
      />
    );
  } else if (result.list.items.length === 0) {
    body = (
      <Empty
        description={
          <>
            <Typography.Title level={2}>{t('endpoints.list.empty')}</Typography.Title>
            {t('endpoints.list.empty.hint')}
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
            title={t('endpoints.folder.truncated')}
            description={t('endpoints.folder.truncated.hint')}
          />
        )}
        <ProTable<Listed['items'][number]>
          aria-label={t('endpoints.list.files')}
          rowKey="path"
          size="small"
          dataSource={result.list.items}
          search={false}
          options={false}
          toolBarRender={false}
          pagination={false}
          columns={[
            {
              title: t('endpoints.list.endpoint'),
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
            packageColumn(result.list.items, (item) => item.path),
            { title: t('endpoints.list.scheme'), dataIndex: 'scheme' },
            { title: t('endpoints.list.direction'), dataIndex: 'direction' },
            { title: t('endpoints.list.file'), dataIndex: 'path' },
          ]}
        />
      </Flex>
    );
  }

  return (
    <Flex vertical gap="middle">
      <Flex align="center" gap="small" wrap>
        <Typography.Title level={1} style={{ margin: 0 }}>
          {t('endpoints.screen.title')}
        </Typography.Title>
        {result.kind === 'read' && result.list.configured && !result.list.editable && <Tag>{t('endpoints.readOnly')}</Tag>}
        <Flex gap="small" style={{ marginInlineStart: 'auto' }}>
          <Button icon={<ReloadOutlined aria-hidden />} onClick={refresh}>
            {t('endpoints.refresh')}
          </Button>
        </Flex>
      </Flex>
      <Typography.Text type="secondary">{t('endpoints.list.intro')}</Typography.Text>
      {body}
    </Flex>
  );
}
