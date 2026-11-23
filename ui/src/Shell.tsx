import { CloseOutlined, EllipsisOutlined, FileTextOutlined, MoonOutlined, PushpinFilled, SearchOutlined, SunOutlined } from '@ant-design/icons';
import { Breadcrumb, Button, Dropdown, Empty, Flex, Layout, Tabs, type MenuProps } from 'antd';
import { cloneElement, Suspense, useCallback, useEffect, useState, type HTMLAttributes, type KeyboardEvent as ReactKeyboardEvent, type ReactElement } from 'react';
import { ReadSpinner } from './ReadSpinner';
import { RegisteredScreens, type Screen } from './screens';
import { closedBy, closeTab, isPinned, noTabs, openTab, pinTab, unpinTab, type CloseCommand } from './tabs';
import { Rail8Theme } from './theme';
import { UnsavedDialog } from './UnsavedDialog';
import { RegisteredWorkingFiles, workingFileRegistry, WorkingTab } from './workingFiles';
import { WorkspaceNavigation } from './WorkspaceNavigation';
import { WorkspaceQuickOpen } from './WorkspaceQuickOpen';

export interface ShellProps {
  /** The screens the navigation lists, in its order. */
  screens: readonly Screen[];
  /** The id of the screen open in a tab when the shell starts, if any. */
  start?: string;
}

/** The items of a tab's menu that close tabs, with the command each one runs. */
const closeItems: readonly { key: CloseCommand; label: string }[] = [
  { key: 'close', label: 'Close' },
  { key: 'others', label: 'Close other tabs' },
  { key: 'left', label: 'Close tabs to the left' },
  { key: 'right', label: 'Close tabs to the right' },
  { key: 'unpinned', label: 'Close all but pinned' },
];

/**
 * The application's shell: a header with the brand, the button that shows or hides the navigation, Quick open and the
 * tabs of the open screens. Choosing a screen in the navigation opens it in a new tab, or shows the tab it already has.
 * A screen may open tabs of its own, such as one for each message it found, through the function its `render` is
 * given. Every open screen stays mounted while another tab shows.
 *
 * Each tab closes with its close button. A right click on a tab, or the menu key or Shift+F10 on a focused one, opens
 * its menu: Close, Close other tabs, Close tabs to the left, Close tabs to the right, Close all but pinned, and Pin tab
 * or Unpin tab. A pinned tab is marked, stays at the start of the row and only its own Close closes it; an item that
 * would close nothing is disabled. Tabs whose screens have no unsaved changes close at once; for the others one
 * question is asked, for all of them together: save and close, close without saving, or keep them open. A tab whose
 * save fails stays open, and the first of them is shown, its screen saying why. When the row is too narrow, the button
 * with three dots at its end lists the tabs it hides.
 *
 * Quick open, also on Ctrl+K or ⌘K, finds a screen of `screens` or an open tab by name and shows it. `start` names the
 * screen open at first; with no tab open the main area says so. The editors in every tab register the files they
 * hold with the shell's working files, which `useWorkingFiles` answers there.
 */
export function Shell({ screens, start }: ShellProps) {
  const [quickOpen, setQuickOpen] = useState(false);
  const [tabs, setTabs] = useState(() => (start === undefined ? noTabs : openTab(noTabs, start)));
  const [opened, setOpened] = useState<ReadonlyMap<string, Screen>>(() => new Map());
  // the tab whose menu is open, and the tabs with unsaved changes the shell is asking about
  const [menu, setMenu] = useState<string | null>(null);
  const [asking, setAsking] = useState<readonly string[]>([]);
  const [saving, setSaving] = useState(false);
  const [workingFiles] = useState(() => workingFileRegistry((id) => setTabs((current) => openTab(current, id))));
  const byId = new Map(screens.map((screen) => [screen.id, screen]));
  const screenOf = (id: string) => opened.get(id) ?? byId.get(id);
  const active = tabs.active ? screenOf(tabs.active) : undefined;

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

  const remove = (ids: readonly string[]) => {
    if (ids.length === 0) return;
    setTabs((current) => ids.reduce(closeTab, current));
    setOpened((current) => {
      const rest = new Map(current);
      for (const id of ids) rest.delete(id);
      return rest;
    });
  };
  /** Closes the tabs of `ids` without unsaved changes at once, and asks about the others. */
  const close = (ids: readonly string[]) => {
    const unsaved = ids.filter((id) => screenOf(id)?.unsaved?.());
    remove(ids.filter((id) => !unsaved.includes(id)));
    setAsking(unsaved);
  };
  const saveAndClose = async () => {
    setSaving(true);
    const saved = await Promise.all(
      asking.map(async (id) => {
        const changes = screenOf(id)?.unsaved?.();
        return changes ? changes.save().catch(() => false) : true;
      }),
    );
    setSaving(false);
    setAsking([]);
    remove(asking.filter((_, i) => saved[i]));
    const failed = asking.find((_, i) => !saved[i]);
    if (failed !== undefined) setTabs((current) => openTab(current, failed));
  };

  const menuOf = (id: string): MenuProps => {
    const pinned = isPinned(tabs, id);
    return {
      items: [
        ...closeItems.map(({ key, label }) => ({ key, label, disabled: closedBy(tabs, key, id).length === 0 })),
        { type: 'divider' },
        { key: 'pin', label: pinned ? 'Unpin tab' : 'Pin tab' },
      ],
      onClick: ({ key }) => {
        setMenu(null);
        if (key === 'pin') setTabs((current) => (pinned ? unpinTab(current, id) : pinTab(current, id)));
        else close(closedBy(tabs, key as CloseCommand, id));
      },
    };
  };
  // Focus left in a menu closed with Escape, or lost with it, goes back to the tab.
  const refocus = (id: string) =>
    requestAnimationFrame(() => {
      const at = document.activeElement;
      if (at !== null && at !== document.body && !at.closest('[role="menu"]')) return;
      document.querySelector<HTMLElement>(`#workspace [data-node-key="${CSS.escape(id)}"] [role="tab"]`)?.focus();
    });
  // The menu key, or Shift+F10, on a focused tab opens its menu, as a right click does.
  const menuKey = (id: string) => (event: ReactKeyboardEvent) => {
    if (event.key !== 'ContextMenu' && !(event.shiftKey && event.key === 'F10')) return;
    event.preventDefault();
    setMenu(id);
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
                  else if (typeof key === 'string') close([key]);
                }}
                more={{ icon: <EllipsisOutlined aria-label="Hidden tabs" /> }}
                renderTabBar={(props, TabBar) => (
                  <TabBar {...props}>
                    {(node) => {
                      const id = String(node.key);
                      return (
                        <Dropdown
                          key={id}
                          trigger={['contextMenu']}
                          open={menu === id}
                          onOpenChange={(open, { source }) => {
                            setMenu(open ? id : null);
                            if (!open && source === 'trigger') refocus(id);
                          }}
                          menu={menuOf(id)}
                          autoFocus
                        >
                          {cloneElement(node as ReactElement<HTMLAttributes<HTMLElement>>, { onKeyDown: menuKey(id) })}
                        </Dropdown>
                      );
                    }}
                  </TabBar>
                )}
                items={tabs.open.map((id) => {
                  const screen = screenOf(id);
                  const title = screen?.title ?? id;
                  return {
                    key: id,
                    label: isPinned(tabs, id) ? (
                      <>
                        {title} <PushpinFilled aria-label="pinned" />
                      </>
                    ) : (
                      title
                    ),
                    icon: <span aria-hidden>{screen?.icon ?? <FileTextOutlined />}</span>,
                    // Ant supplies one remove label for the bar. Identify each tab for assistive technology.
                    closeIcon: (
                      <CloseOutlined ref={(icon) => {
                        icon?.closest('button')?.setAttribute('aria-label', `Close ${title}`);
                      }} />
                    ),
                    children: (
                      <main className="rail8-panel">
                        <RegisteredWorkingFiles.Provider value={workingFiles}>
                          <WorkingTab.Provider value={id}>
                            <Suspense fallback={<ReadSpinner label={`Opening ${title}`} />}>{screen?.render(open)}</Suspense>
                          </WorkingTab.Provider>
                        </RegisteredWorkingFiles.Provider>
                      </main>
                    ),
                  };
                })}
              />
            )}
          </Layout>
          {asking.length > 0 && (
            <UnsavedDialog
              titles={asking.map((id) => screenOf(id)?.title ?? id)}
              saving={saving}
              onSave={() => void saveAndClose()}
              onDiscard={() => {
                remove(asking);
                setAsking([]);
              }}
              onCancel={() => setAsking([])}
            />
          )}
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
