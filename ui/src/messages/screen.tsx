import { lazy } from 'react';
import { MailOutlined } from '@ant-design/icons';
import type { Screen } from '../screens';
import type { AdminApi } from './admin';

const MessageList = lazy(() => import('./MessageList').then((module) => ({ default: module.MessageList })));
const MessageView = lazy(() => import('./MessageView').then((module) => ({ default: module.MessageView })));

/** The tab of one message: its id is `message:` and the message's id, its title the message's id. */
export function messageTab(api: AdminApi, id: string): Screen {
  return { id: `message:${id}`, title: id, group: 'Monitor', render: () => <MessageView api={api} id={id} /> };
}

/** The Messages screen over `api`: the list of recent messages, each one opening in a tab of its own. */
export function messagesScreen(api: AdminApi, now?: () => Date): Screen {
  return {
    id: 'messages',
    title: 'Messages',
    group: 'Monitor',
    icon: <MailOutlined />,
    description: 'Find recorded messages and inspect their execution history.',
    render: (open) => <MessageList api={api} now={now} onOpen={(id) => open(messageTab(api, id))} />,
  };
}
