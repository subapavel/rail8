import { FileTextOutlined, SearchOutlined } from '@ant-design/icons';
import { Button, Input, Typography, type InputRef } from 'antd';
import { useRef, useState, type KeyboardEvent } from 'react';
import { MountedModal } from './MountedModal';
import type { Screen } from './screens';

export function WorkspaceQuickOpen({ screens, choose, dismiss }: {
  screens: readonly Screen[];
  choose: (screen: Screen) => void;
  dismiss: () => void;
}) {
  const [query, setQuery] = useState('');
  const field = useRef<InputRef>(null);
  const matches = screens.filter(({ id, title, group }) =>
    `${title} ${id} ${group ?? ''}`.toLowerCase().includes(query.trim().toLowerCase()),
  );

  function move(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    const choices = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('button[data-choice]'));
    if (!choices.length) return;
    event.preventDefault();
    const current = choices.findIndex((choice) => choice === document.activeElement);
    const next = event.key === 'ArrowDown' ? current + 1 : (current < 0 ? 0 : current) - 1;
    choices[(next + choices.length) % choices.length]?.focus();
  }

  return (
    <MountedModal
      title="Quick open"
      width={470}
      footer={null}
      onCancel={dismiss}
      afterOpenChange={(visible) => { if (visible) field.current?.focus(); }}
    >
      <div className="rail8-quick-list" onKeyDown={move}>
        <Input
          ref={field}
          allowClear
          prefix={<SearchOutlined />}
          aria-label="Find a screen or open tab"
          placeholder="Find a screen or open tab…"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onPressEnter={() => { if (matches[0]) choose(matches[0]); }}
        />
        {matches.map((screen) => (
          <Button
            type="text"
            data-choice
            key={screen.id}
            icon={screen.icon ?? <FileTextOutlined aria-hidden />}
            onClick={() => choose(screen)}
          >
            {screen.title}
            <Typography.Text type="secondary" ellipsis>
              {screen.id.includes(':') ? screen.id.slice(screen.id.indexOf(':') + 1) : screen.group}
            </Typography.Text>
          </Button>
        ))}
        {matches.length === 0 && <Typography.Text type="secondary" role="status">No matching screens or tabs</Typography.Text>}
      </div>
    </MountedModal>
  );
}
