import { AppstoreOutlined, CloseOutlined, EllipsisOutlined, FileTextOutlined, MoonOutlined, PushpinFilled, SearchOutlined, SunOutlined } from '@ant-design/icons';
import { ProLayout, RouteContext, type ProLayoutProps } from '@ant-design/pro-components';
import { Button, Dropdown, Empty, Tabs, theme, Tooltip, type BreadcrumbProps, type MenuProps } from 'antd';
import {
  cloneElement,
  Suspense,
  useCallback,
  useContext,
  useEffect,
  useState,
  type AriaAttributes,
  type HTMLAttributes,
  type KeyboardEvent as ReactKeyboardEvent,
  type MouseEvent,
  type ReactElement,
  type ReactNode,
} from 'react';
import { LanguageSwitch } from './LanguageSwitch';
import { say, useText } from './language';
import { ReadSpinner } from './ReadSpinner';
import { SessionActions, SessionPrompt, useAccess } from './SessionGate';
import { groupedScreens, RegisteredScreens, WORKSPACE, type Find, type Screen } from './screens';
import { closedBy, closeTab, isPinned, noTabs, openTab, pinTab, unpinTab, type CloseCommand } from './tabs';
import { Rail8Theme } from './theme';
import { UnsavedDialog } from './UnsavedDialog';
import { RegisteredWorkingFiles, workingFileRegistry, WorkingTab } from './workingFiles';
import { WorkspaceQuickOpen } from './WorkspaceQuickOpen';

export interface ShellProps {
  /** The screens the navigation lists, in its order. */
  screens: readonly Screen[];
  /** The id of the screen open in a tab when the shell starts, if any. */
  start?: string;
  /** What Quick open finds beside the screens and the open tabs: the artifacts of the working folder. */
  find?: Find;
}

/** The items of a tab's menu that close tabs, with the command each one runs and the id of its text. */
const closeItems: readonly { key: CloseCommand; text: string }[] = [
  { key: 'close', text: 'frame.tab.close' },
  { key: 'others', text: 'frame.tab.closeOthers' },
  { key: 'left', text: 'frame.tab.closeLeft' },
  { key: 'right', text: 'frame.tab.closeRight' },
  { key: 'unpinned', text: 'frame.tab.closeUnpinned' },
];

/** Windows narrower than this show the menu collapsed to its icons when they open or shrink below it. */
const narrow = '(max-width: 1279.98px)';

/** An open tab's screen, and the screens that led to it when another screen opened it, the first one first. */
interface Opened {
  screen: Screen;
  trail: readonly Screen[];
}

/**
 * The application's frame, in the Ant Design Pro style: a header across the top with the brand, Quick open, the
 * language switch and the theme switch, a side menu that stays in place and lists `screens` in their groups, and the content beside it. The
 * content starts with a row of tabs, one for each open screen, and shows the screen of the chosen tab under it. Choosing
 * a screen in the menu opens it in a new tab, or shows the tab it already has. A screen may open tabs of its own, such
 * as one for each message it found, through the function its `render` is given. Every open screen stays mounted while
 * another tab shows.
 *
 * Each screen draws its page with `Page`, which shows the breadcrumb the shell gives its tab: the group, the screens
 * that led to it, each of which opens again when chosen, and its own title. A screen that is a canvas, such as an
 * editor, fills the content without a gutter. The menu collapses to its icons with the button on its edge or with
 * Ctrl+B or ⌘B, to give a canvas more room; on a window narrower than 1280 pixels it collapses by itself.
 *
 * Each tab closes with its close button. A right click on a tab, or the menu key or Shift+F10 on a focused one, opens
 * its menu: Close, Close other tabs, Close tabs to the left, Close tabs to the right, Close all but pinned, and Pin tab
 * or Unpin tab. A pinned tab is marked, stays at the start of the row and only its own Close closes it; an item that
 * would close nothing is disabled. Tabs whose screens have no unsaved changes close at once; for the others one
 * question is asked, for all of them together: save and close, close without saving, or keep them open. A tab whose
 * save fails stays open, and the first of them is shown, its screen saying why. When the row is too narrow, the button
 * with three dots at its end lists the tabs it hides.
 *
 * Quick open, also on Ctrl+K or ⌘K, finds a screen of `screens` or an open tab by name and shows it, and an artifact
 * `find` finds, whose tab it opens after the screens that artifact's breadcrumb starts from. `start` names the
 * screen open at first; with no tab open the content says so. The editors in every tab register the files they hold
 * with the shell's working files, which `useWorkingFiles` answers there.
 */
export function Shell({ screens, start, find }: ShellProps) {
  const [quickOpen, setQuickOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(() => matchMedia(narrow).matches);
  const [tabs, setTabs] = useState(() => (start === undefined ? noTabs : openTab(noTabs, start)));
  const [opened, setOpened] = useState<ReadonlyMap<string, Opened>>(() => new Map());
  // the tab whose menu is open, and the tabs with unsaved changes the shell is asking about
  const [menu, setMenu] = useState<string | null>(null);
  const [asking, setAsking] = useState<readonly string[]>([]);
  const [saving, setSaving] = useState(false);
  const [workingFiles] = useState(() => workingFileRegistry((id) => setTabs((current) => openTab(current, id))));
  const byId = new Map(screens.map((screen) => [screen.id, screen]));
  const access = useAccess();
  const texts = useText();
  const { t } = texts;
  const titleOf = (screen: Screen | undefined, id: string) => (screen === undefined ? id : say(texts, screen.title));
  // why the caller may not open a screen, or undefined when they may
  const refusedOf = (screen: Screen | undefined) => {
    const may = access(screen?.action);
    return may === true ? undefined : may;
  };
  const screenOf = (id: string) => opened.get(id)?.screen ?? byId.get(id);

  useEffect(() => {
    const shortcut = (event: KeyboardEvent) => {
      if (!(event.metaKey || event.ctrlKey) || event.altKey || event.shiftKey) return;
      const key = event.key.toLowerCase();
      if (key !== 'k' && key !== 'b') return;
      const dialogs = document.querySelectorAll('[role="dialog"][aria-modal="true"]');
      if (event.defaultPrevented || Array.from(dialogs).some((dialog) => dialog.checkVisibility())) return;
      event.preventDefault();
      if (key === 'k') setQuickOpen(true);
      else setCollapsed((current) => !current);
    };
    document.addEventListener('keydown', shortcut);
    return () => document.removeEventListener('keydown', shortcut);
  }, []);

  useEffect(() => {
    const query = matchMedia(narrow);
    const follow = () => setCollapsed(query.matches);
    query.addEventListener('change', follow);
    return () => query.removeEventListener('change', follow);
  }, []);

  /**
   * Opens `screen` in a tab, or shows the tab it has. A tab it opens for the first time remembers the screens that led
   * to it, which `trailOf` gives from the tabs open now, up to `screen` itself when it led there before.
   */
  const openAfter = useCallback((screen: Screen, trailOf: (current: ReadonlyMap<string, Opened>) => readonly Screen[]) => {
    setOpened((current) => {
      if (current.has(screen.id)) return current;
      const trail = trailOf(current);
      const own = trail.findIndex((step) => step.id === screen.id);
      return new Map(current).set(screen.id, { screen, trail: own === -1 ? trail : trail.slice(0, own) });
    });
    setTabs((current) => openTab(current, screen.id));
  }, []);
  /** Opens a screen in a tab, as `from` opens it: after the screens that led to `from`, and `from` itself. */
  const openFrom = useCallback(
    (from?: Screen) => (screen: Screen) => openAfter(screen, (current) => (from === undefined ? [] : [...(current.get(from.id)?.trail ?? []), from])),
    [openAfter],
  );
  const open = openFrom();

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

  /**
   * The breadcrumb of `screen`'s tab: its group, then, for a tab another screen opened, the screens that led to it,
   * from the last screen of the menu among them, each opening again when chosen, after the screens that led to it;
   * then its own title. A screen of the menu has its place there, so its trail is its group alone. The trail stays
   * when the tabs of the screens on it close.
   */
  const breadcrumbOf = (screen: Screen): BreadcrumbProps['items'] => {
    const led = byId.has(screen.id) ? [] : (opened.get(screen.id)?.trail ?? []);
    const first = Math.max(led.findLastIndex((step) => byId.has(step.id)), 0);
    const trail = led.slice(first);
    return [
      { title: say(texts, (trail[0] ?? screen).group ?? WORKSPACE) },
      ...trail.map((step, n) => ({
        title: say(texts, step.title),
        href: '#',
        onClick: (event: MouseEvent) => {
          event.preventDefault();
          openAfter(step, () => led.slice(0, first + n));
        },
      })),
      { title: say(texts, screen.title) },
    ];
  };

  /**
   * The id of the menu's screen the tab `id` belongs to: its own screen when the menu lists it, else the last screen of
   * the menu among those that led to it, where its breadcrumb starts; undefined when none of them is in the menu.
   */
  const menuScreenOf = (id: string | null) => {
    if (id === null || byId.has(id)) return id ?? undefined;
    return (opened.get(id)?.trail ?? []).findLast((step) => byId.has(step.id))?.id;
  };
  const marked = menuScreenOf(tabs.active);

  const menuOf = (id: string): MenuProps => {
    const pinned = isPinned(tabs, id);
    return {
      items: [
        ...closeItems.map(({ key, text }) => ({ key, label: t(text), disabled: closedBy(tabs, key, id).length === 0 })),
        { type: 'divider' },
        { key: 'pin', label: t(pinned ? 'frame.tab.unpin' : 'frame.tab.pin') },
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
          <Frame
            className="rail8-frame"
            layout="mix"
            fixSiderbar
            breakpoint={false}
            disableMobile
            collapsed={collapsed}
            onCollapse={setCollapsed}
            title="Rail8"
            logo={<img src={`${import.meta.env.BASE_URL}${dark ? 'rail8-mark-dark.svg' : 'rail8.svg'}`} alt="" />}
            headerTitleRender={(logo) => (
              <span
                className="rail8-brand"
                ref={(brand) => {
                  // ProLayout puts an empty header before the fixed one to hold its place; it is no landmark.
                  brand?.closest('header')?.previousElementSibling?.setAttribute('role', 'presentation');
                }}
              >
                {logo}
                <span>Rail8</span>
              </span>
            )}
            pageTitleRender={false}
            actionsRender={() => [
              <Button
                key="quick-open"
                size="small"
                aria-label={t('frame.quickOpen')}
                aria-keyshortcuts="Control+K Meta+K"
                title={t('frame.quickOpen.shortcut')}
                icon={<SearchOutlined />}
                onClick={() => setQuickOpen(true)}
              >
                {t('frame.quickOpen')}
              </Button>,
              <LanguageSwitch key="language" />,
              <Button
                key="theme"
                size="small"
                type="text"
                icon={dark ? <SunOutlined /> : <MoonOutlined />}
                aria-label={t(dark ? 'frame.theme.light' : 'frame.theme.dark')}
                onClick={toggle}
              />,
              <SessionActions key="session" />,
            ]}
            menu={{ type: 'group' }}
            route={{
              path: '/',
              routes: Array.from(groupedScreens(screens), ([key, { group, entries }]) => ({
                key,
                name: say(texts, group),
                path: `/${key}`,
                routes: entries.map((screen) => ({
                  key: screen.id,
                  name: say(texts, screen.title),
                  path: `/${screen.id}`,
                  disabled: refusedOf(screen) !== undefined,
                  icon: <span aria-hidden>{screen.icon ?? <AppstoreOutlined />}</span>,
                })),
              })),
            }}
            location={{ pathname: '/' }}
            menuProps={{
              selectedKeys: marked === undefined ? [] : [marked],
              onClick: ({ key }) => {
                const screen = byId.get(key);
                if (screen && refusedOf(screen) === undefined) open(screen);
              },
            }}
            menuItemRender={(item, dom, { collapsed: folded }) => (
              // Each item keeps its screen's name for assistive technology while the collapsed menu hides the text, and the
              // item of the shown tab's screen is marked for it, as it is marked on screen.
              <span
                ref={(span) => {
                  const entry = span?.closest('li');
                  if (item.name) entry?.setAttribute('aria-label', item.name);
                  if (item.key === marked) entry?.setAttribute('aria-current', 'page');
                  else entry?.removeAttribute('aria-current');
                }}
              >
                <Tooltip title={refusedOf(byId.get(String(item.key))) ?? (folded ? item.name : undefined)} placement="right">
                  {dom}
                </Tooltip>
              </span>
            )}
            menuContentRender={(_, dom) => <nav aria-label={t('frame.screens')}>{dom}</nav>}
            collapsedButtonRender={(folded, dom) => {
              const label = t(folded ? 'frame.menu.expand' : 'frame.menu.collapse');
              return cloneElement(dom as ReactElement<HTMLAttributes<HTMLElement>>, {
                role: 'button',
                tabIndex: 0,
                'aria-label': label,
                'aria-expanded': !folded,
                'aria-keyshortcuts': 'Control+B Meta+B',
                title: t('frame.menu.shortcut', { action: label }),
                onKeyDown: (event) => {
                  if (event.key !== 'Enter' && event.key !== ' ') return;
                  event.preventDefault();
                  setCollapsed(!folded);
                },
              });
            }}
          >
            {tabs.active === null ? (
              <div className="rail8-empty">
                <Empty description={<><h1>{t('frame.noScreen')}</h1>{t('frame.noScreen.hint')}</>} />
              </div>
            ) : (
              <Tabs
                id="workspace"
                className="rail8-tabs"
                classNames={{ header: 'rail8-tab-bar', body: 'rail8-tab-body', content: 'rail8-tab-page' }}
                type="editable-card"
                size="small"
                aria-label={t('frame.tabs')}
                animated={false}
                activeKey={tabs.active}
                onChange={(id) => setTabs((current) => openTab(current, id))}
                onEdit={(key, action) => {
                  if (action === 'add') setQuickOpen(true);
                  else if (typeof key === 'string') close([key]);
                }}
                more={{ icon: <EllipsisOutlined aria-label={t('frame.tabs.hidden')} /> }}
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
                  const title = titleOf(screen, id);
                  const refused = refusedOf(screen);
                  return {
                    key: id,
                    label: isPinned(tabs, id) ? (
                      <>
                        {title} <PushpinFilled aria-label={t('frame.tab.pinned')} />
                      </>
                    ) : (
                      title
                    ),
                    icon: <span aria-hidden>{screen?.icon ?? <FileTextOutlined />}</span>,
                    // Ant supplies one remove label for the bar. Identify each tab for assistive technology.
                    closeIcon: (
                      <CloseOutlined ref={(icon) => {
                        icon?.closest('button')?.setAttribute('aria-label', t('frame.tab.closeNamed', { title }));
                      }} />
                    ),
                    children: (
                      <div className="rail8-panel">
                        {refused !== undefined ? (
                          <div className="rail8-empty">
                            <Empty description={<><h1>{t('frame.tab.refused', { title })}</h1>{refused}.</>} />
                          </div>
                        ) : screen && (
                          <TabRoute breadcrumb={breadcrumbOf(screen)}>
                            <RegisteredWorkingFiles.Provider value={workingFiles}>
                              <WorkingTab.Provider value={id}>
                                <Suspense fallback={<ReadSpinner label={t('frame.tab.opening', { title })} />}>{screen.render(openFrom(screen))}</Suspense>
                              </WorkingTab.Provider>
                            </RegisteredWorkingFiles.Provider>
                          </TabRoute>
                        )}
                      </div>
                    ),
                  };
                })}
              />
            )}
          </Frame>
          {asking.length > 0 && (
            <UnsavedDialog
              titles={asking.map((id) => titleOf(screenOf(id), id))}
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
              screens={[
                ...screens,
                ...Array.from(opened.values(), ({ screen }) => screen).filter((screen) => !byId.has(screen.id)),
              ]}
              find={find}
              refusedOf={refusedOf}
              dismiss={() => setQuickOpen(false)}
              choose={(screen) => {
                open(screen);
                setQuickOpen(false);
              }}
              chooseFound={({ screen, trail }) => {
                openAfter(screen, () => trail);
                setQuickOpen(false);
              }}
            />
          )}
          <SessionPrompt />
        </RegisteredScreens.Provider>
      )}
    </Rail8Theme>
  );
}

/**
 * ProLayout with the sizes of the frame taken from the theme: the header as tall as the extra large size, a page's
 * sides as wide as the large padding, and its top and bottom from the extra large padding, of which PageContainer puts
 * parts around the page's header and all of it below the page's body.
 */
function Frame(props: ProLayoutProps) {
  const { token } = theme.useToken();
  return (
    <ProLayout
      {...props}
      token={{
        header: { heightLayoutHeader: token.sizeXXL },
        pageContainer: {
          paddingBlockPageContainerContent: token.paddingXL,
          paddingInlinePageContainerContent: token.paddingLG,
        },
      }}
    />
  );
}

/** Gives the page of one tab its own breadcrumb, which PageContainer shows above the page's title. */
function TabRoute({ breadcrumb, children }: { breadcrumb: BreadcrumbProps['items']; children: ReactNode }) {
  const route = useContext(RouteContext);
  const { t } = useText();
  const trail: BreadcrumbProps & AriaAttributes = { items: breadcrumb, 'aria-label': t('frame.breadcrumb') };
  return <RouteContext.Provider value={{ ...route, breadcrumb: trail }}>{children}</RouteContext.Provider>;
}
