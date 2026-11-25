import { FileTextOutlined, SearchOutlined } from '@ant-design/icons';
import { Button, Input, Typography, type InputRef } from 'antd';
import { useContext, useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { ArtifactKinds } from './artifacts';
import { say, useText } from './language';
import { MountedModal } from './MountedModal';
import type { Find, Found, Screen } from './screens';

/** How many of the artifacts that match are listed at most. */
export const MAX_FOUND = 20;

type Finding = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; found: readonly Found[] };

/**
 * Finds a screen or an open tab by name, and, once something is typed, an artifact of the working folder that `find`
 * finds by its name or its path: a flow, an endpoint, a mapping or a test, each with its kind and path, at most
 * {@link MAX_FOUND} of them. The artifacts are read when it opens; while they are read, and when they cannot be, it
 * says so under the screens. An artifact whose own tab is open is listed as that tab. A screen or an artifact the caller
 * may not open, for which `refusedOf` gives why, is listed disabled with that reason.
 */
export function WorkspaceQuickOpen({ screens, find, choose, chooseFound, dismiss, refusedOf = () => undefined }: {
  screens: readonly Screen[];
  find?: Find;
  refusedOf?: (screen: Screen) => string | undefined;
  choose: (screen: Screen) => void;
  chooseFound: (found: Found) => void;
  dismiss: () => void;
}) {
  const texts = useText();
  const { t } = texts;
  const kinds = useContext(ArtifactKinds);
  const [query, setQuery] = useState('');
  const [finding, setFinding] = useState<Finding>(find === undefined ? { kind: 'read', found: [] } : { kind: 'reading' });
  const field = useRef<InputRef>(null);

  useEffect(() => {
    if (find === undefined) return;
    let current = true;
    find(kinds).then(
      (found) => current && setFinding({ kind: 'read', found }),
      (error: unknown) => current && setFinding({ kind: 'failed', message: error instanceof Error ? error.message : String(error) }),
    );
    return () => {
      current = false;
    };
  }, [find, kinds]);

  const term = query.trim().toLowerCase();
  const matches = screens.filter(({ id, title, group }) =>
    `${say(texts, title)} ${id} ${group === undefined ? '' : say(texts, group)}`.toLowerCase().includes(term),
  );
  // an artifact whose own tab is open is that tab; one that opens a tab of something else, such as a test its package's, is not
  const tabs = new Map(screens.map((screen) => [screen.id, say(texts, screen.title)]));
  const artifacts =
    term === '' || finding.kind !== 'read'
      ? []
      : finding.found.filter((found) => tabs.get(found.screen.id) !== found.name && `${found.name} ${found.path}`.toLowerCase().includes(term));
  const shown = artifacts.slice(0, MAX_FOUND);

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
            else {
              const artifact = shown.find((found) => refusedOf(found.screen) === undefined);
              if (artifact) chooseFound(artifact);
            }
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
        {shown.map((found) => {
          const refused = refusedOf(found.screen);
          return (
            <Button
              type="text"
              data-choice
              key={`found:${found.path}`}
              disabled={refused !== undefined}
              icon={found.screen.icon ?? <FileTextOutlined aria-hidden />}
              onClick={() => chooseFound(found)}
            >
              {found.name}
              <Typography.Text type="secondary" ellipsis>
                {refused ?? t('frame.quickOpen.found', { kind: say(texts, found.kind), path: found.path })}
              </Typography.Text>
            </Button>
          );
        })}
        {artifacts.length > shown.length && (
          <Typography.Text type="secondary">{t('frame.quickOpen.more', { count: artifacts.length - shown.length })}</Typography.Text>
        )}
        {term !== '' && finding.kind === 'reading' && (
          <Typography.Text type="secondary" role="status">
            {t('frame.quickOpen.reading')}
          </Typography.Text>
        )}
        {finding.kind === 'failed' && (
          <Typography.Text type="danger" role="alert">
            {t('frame.quickOpen.unread', { reason: finding.message })}
          </Typography.Text>
        )}
        {matches.length === 0 && shown.length === 0 && finding.kind !== 'reading' && (
          <Typography.Text type="secondary" role="status">{t('frame.quickOpen.none')}</Typography.Text>
        )}
      </div>
    </MountedModal>
  );
}
