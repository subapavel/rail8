import { CloseOutlined, FileTextOutlined, MoonOutlined, SearchOutlined, SunOutlined } from '@ant-design/icons';
import { Breadcrumb, Button, Empty, Flex, Layout, Tabs } from 'antd';
import { Suspense, useCallback, useEffect, useState } from 'react';
import { ReadSpinner } from './ReadSpinner';
import { RegisteredScreens, type Screen } from './screens';
import { closeTab, noTabs, openTab } from './tabs';
import { Rail8Theme } from './theme';
import { WorkspaceNavigation } from './WorkspaceNavigation';
import { WorkspaceQuickOpen } from './WorkspaceQuickOpen';

export interface ShellProps {
  /** The screens the navigation lists, in its order. */
  screens: readonly Screen[];
  /** The id of the screen open in a tab when the shell starts, if any. */
  start?: string;
}

/** The workspace keeps open screens mounted. A registered screen or an open document can be reached from Quick open. */
export function Shell({ screens, start }: ShellProps) {
  const [quickOpen, setQuickOpen] = useState(false);
  const [tabs, setTabs] = useState(() => (start === undefined ? noTabs : openTab(noTabs, start)));
  const [opened, setOpened] = useState<ReadonlyMap<string, Screen>>(() => new Map());
  const byId = new Map(screens.map((screen) => [screen.id, screen]));
  const active = tabs.active ? (opened.get(tabs.active) ?? byId.get(tabs.active)) : undefined;

  useEffect(() => {
    const shortcut = (event: KeyboardEvent) => {
      if (!(event.metaKey || event.ctrlKey) || event.altKey || event.shiftKey || event.key.toLowerCase() !== 'k') return;
      const dialogs = document.querySelectorAll('[role="dialog"][aria-modal="true"]');
      if (event.defaultPrevented || Array.from(dialogs).some((dialog) => dialog.checkVisibility())) return;
      event.preventDefault();
      setQuickOpen(true);
    };
    document.addEventListener('keydown', shortcut);
    return () => document.removeEventListener('keydown', shortcut);
  }, []);

  const open = useCallback((screen: Screen) => {
    setOpened((current) => (current.has(screen.id) ? current : new Map(current).set(screen.id, screen)));
    setTabs((current) => openTab(current, screen.id));
  }, []);

  const remove = (id: string) => {
    setTabs((current) => closeTab(current, id));
    setOpened((current) => {
      if (!current.has(id)) return current;
      const rest = new Map(current);
      rest.delete(id);
      return rest;
    });
  };
  const close = (id: string) => {
    const screen = opened.get(id) ?? byId.get(id);
    if (screen?.beforeClose) screen.beforeClose(() => remove(id));
    else remove(id);
  };

  return (
    <Rail8Theme>
      {(dark, toggle) => (
        <RegisteredScreens.Provider value={screens}>
          <Layout className="rail8-layout" hasSider={false}>
            <WorkspaceNavigation screens={screens} active={tabs.active} open={open} />
            <Layout.Header className="rail8-header">
              <Flex align="center" gap="small" className="rail8-location">
                <img src={`${import.meta.env.BASE_URL}${dark ? 'rail8-mark-dark.svg' : 'rail8.svg'}`} alt="Rail8" />
                <Breadcrumb
                  aria-label="Workspace location"
                  items={[{ title: 'Workspace' }, ...(active?.group ? [{ title: active.group }] : [])]}
                />
              </Flex>
              <Flex align="center" gap={4}>
                <Button
                  size="small"
                  aria-label="Quick open"
                  aria-keyshortcuts="Control+K Meta+K"
                  title="Quick open (Ctrl+K or ⌘K)"
                  icon={<SearchOutlined />}
                  onClick={() => setQuickOpen(true)}
                >
                  Quick open
                </Button>
                <Button
                  size="small"
                  type="text"
                  icon={dark ? <SunOutlined /> : <MoonOutlined />}
                  aria-label={dark ? 'Use light theme' : 'Use dark theme'}
                  onClick={toggle}
                />
              </Flex>
            </Layout.Header>
            {tabs.active === null ? (
              <main className="rail8-empty">
                <Empty description={<><h1>No screen open</h1>Choose a screen in the navigation.</>} />
              </main>
            ) : (
              <Tabs
                id="workspace"
                className="rail8-tabs"
                classNames={{ header: 'rail8-tab-bar', body: 'rail8-tab-body', content: 'rail8-tab-page' }}
                type="editable-card"
                size="small"
                aria-label="Open workspace tabs"
                animated={false}
                activeKey={tabs.active}
                onChange={(id) => setTabs((current) => openTab(current, id))}
                onEdit={(key, action) => {
                  if (action === 'add') setQuickOpen(true);
                  else if (typeof key === 'string') close(key);
                }}
                items={tabs.open.map((id) => {
                  const screen = opened.get(id) ?? byId.get(id);
                  const title = screen?.title ?? id;
                  return {
                    key: id,
                    label: title,
                    icon: <span aria-hidden>{screen?.icon ?? <FileTextOutlined />}</span>,
                    // Ant supplies one remove label for the bar. Identify each tab for assistive technology.
                    closeIcon: (
                      <CloseOutlined ref={(icon) => {
                        icon?.closest('button')?.setAttribute('aria-label', `Close ${title}`);
                      }} />
                    ),
                    children: (
                      <main className="rail8-panel">
                        <Suspense fallback={<ReadSpinner label={`Opening ${title}`} />}>{screen?.render(open)}</Suspense>
                      </main>
                    ),
                  };
                })}
              />
            )}
          </Layout>
          {quickOpen && (
            <WorkspaceQuickOpen
              screens={[...screens, ...Array.from(opened.values()).filter((screen) => !byId.has(screen.id))]}
              dismiss={() => setQuickOpen(false)}
              choose={(screen) => {
                open(screen);
                setQuickOpen(false);
              }}
            />
          )}
        </RegisteredScreens.Provider>
      )}
    </Rail8Theme>
  );
}
