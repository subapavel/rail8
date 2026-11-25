import { FileTextOutlined, SearchOutlined } from '@ant-design/icons';
import { Button, Input, Typography, type InputRef } from 'antd';
import { useRef, useState, type KeyboardEvent } from 'react';
import { say, useText } from './language';
import { MountedModal } from './MountedModal';
import type { Screen } from './screens';

/**
 * Finds a screen or an open tab by name. A screen the caller may not open, for which `refusedOf` gives why, is listed
 * disabled with that reason.
 */
export function WorkspaceQuickOpen({ screens, choose, dismiss, refusedOf = () => undefined }: {
  screens: readonly Screen[];
  refusedOf?: (screen: Screen) => string | undefined;
  choose: (screen: Screen) => void;
  dismiss: () => void;
}) {
  const texts = useText();
  const { t } = texts;
  const [query, setQuery] = useState('');
  const field = useRef<InputRef>(null);
  const matches = screens.filter(({ id, title, group }) =>
    `${say(texts, title)} ${id} ${group === undefined ? '' : say(texts, group)}`.toLowerCase().includes(query.trim().toLowerCase()),
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
      title={t('frame.quickOpen')}
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
          aria-label={t('frame.quickOpen.find')}
          placeholder={t('frame.quickOpen.placeholder')}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onPressEnter={() => {
            const first = matches.find((screen) => refusedOf(screen) === undefined);
            if (first) choose(first);
          }}
        />
        {matches.map((screen) => {
          const refused = refusedOf(screen);
          return (
            <Button
              type="text"
              data-choice
              key={screen.id}
              disabled={refused !== undefined}
              icon={screen.icon ?? <FileTextOutlined aria-hidden />}
              onClick={() => choose(screen)}
            >
              {say(texts, screen.title)}
              <Typography.Text type="secondary" ellipsis>
                {refused ?? (screen.id.includes(':') ? screen.id.slice(screen.id.indexOf(':') + 1) : screen.group && say(texts, screen.group))}
              </Typography.Text>
            </Button>
          );
        })}
        {matches.length === 0 && <Typography.Text type="secondary" role="status">{t('frame.quickOpen.none')}</Typography.Text>}
      </div>
    </MountedModal>
  );
}
