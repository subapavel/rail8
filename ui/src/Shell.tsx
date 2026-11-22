import {
  AppstoreOutlined,
  CloseOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MoonOutlined,
  SunOutlined,
} from '@ant-design/icons';
import { Breadcrumb, Button, Drawer, Empty, Flex, Grid, Layout, Spin, Tabs, Typography } from 'antd';
import { Suspense, useCallback, useState } from 'react';
import { groupedScreens, RegisteredScreens, type Screen } from './screens';
import { Rail8Theme } from './theme';
import { closeTab, noTabs, openTab } from './tabs';

export interface ShellProps {
  /** The screens the navigation lists, in its order. */
  screens: readonly Screen[];
  /** The id of the screen open in a tab when the shell starts, if any. */
  start?: string;
}

/**
 * The application's shell: a masthead with the brand and the button that shows or hides the navigation, the
 * navigation listing every screen, and the tabs of the open screens. Choosing a screen in the navigation opens it in a
 * new tab, or shows the tab it already has; each tab closes with its close button, once its screen agrees. A screen may
 * open tabs of its own, such as one for each message it found, through the function its `render` is given. With no
 * tab open the main area says so.
 */
export function Shell({ screens, start }: ShellProps) {
  const desktop = Grid.useBreakpoint().md ?? window.matchMedia('(min-width: 768px)').matches;
  const [collapsed, setCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [tabs, setTabs] = useState(() => (start === undefined ? noTabs : openTab(noTabs, start)));
  // the tabs screens opened, by id; the navigation's screens are looked up in `screens`
  const [opened, setOpened] = useState<ReadonlyMap<string, Screen>>(() => new Map());
  const byId = new Map(screens.map((screen) => [screen.id, screen]));

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

  const active = tabs.active ? (opened.get(tabs.active) ?? byId.get(tabs.active)) : undefined;
  const groups = groupedScreens(screens);
  const navigation = (
    <nav aria-label="Screens" className="rail8-nav">
      {Array.from(groups, ([group, entries]) => (
        <section key={group} aria-label={group}>
          <Typography.Text type="secondary" className="rail8-nav-group">
            {group}
          </Typography.Text>
          {entries.map((entry) => (
            <Button
              key={entry.id}
              block
              type="text"
              aria-label={entry.title}
              icon={entry.icon ?? <AppstoreOutlined />}
              aria-current={tabs.active === entry.id ? 'page' : undefined}
              onClick={() => {
                open(entry);
                setMobileOpen(false);
              }}
            >
              {entry.title}
            </Button>
          ))}
        </section>
      ))}
    </nav>
  );
  return (
    <Rail8Theme>
      {(dark, toggle) => (
        <RegisteredScreens.Provider value={screens}>
          <Layout className="rail8-layout">
            <Layout.Header className="rail8-header">
              <Flex align="center" gap="middle">
                <Button
                  type="text"
                  aria-label="Navigation"
                  aria-expanded={desktop ? !collapsed : mobileOpen}
                  icon={(desktop ? !collapsed : mobileOpen) ? <MenuFoldOutlined /> : <MenuUnfoldOutlined />}
                  onClick={() => (desktop ? setCollapsed(!collapsed) : setMobileOpen(!mobileOpen))}
                />
                <span className="rail8-brand">
                  <img src={`${import.meta.env.BASE_URL}rail8.svg`} alt="" />
                  Rail8
                </span>
              </Flex>
              <Button
                type="text"
                icon={dark ? <SunOutlined /> : <MoonOutlined />}
                aria-label={dark ? 'Use light theme' : 'Use dark theme'}
                onClick={toggle}
              />
            </Layout.Header>
            <Layout hasSider={desktop && !collapsed}>
              {desktop && !collapsed && (
                <Layout.Sider theme="light" width={200} className="rail8-sidebar">
                  {navigation}
                </Layout.Sider>
              )}
              {!desktop && (
                <Drawer
                  placement="left"
                  title="Navigation"
                  size={240}
                  open={mobileOpen}
                  onClose={() => setMobileOpen(false)}
                >
                  {navigation}
                </Drawer>
              )}
              <Layout.Content className="rail8-workspace">
                <Breadcrumb
                  aria-label="Workspace location"
                  items={[{ title: active?.group ?? 'Workspace' }, ...(active ? [{ title: active.title }] : [])]}
                />
                {tabs.active === null ? (
                  <Empty
                    className="rail8-empty"
                    description={
                      <>
                        <h1>No screen open</h1>Choose a screen in the navigation.
                      </>
                    }
                  />
                ) : (
                  <Tabs
                    id="workspace"
                    className="rail8-tabs"
                    type="editable-card"
                    hideAdd
                    animated={false}
                    activeKey={tabs.active}
                    onChange={(id) => setTabs((current) => openTab(current, id))}
                    onEdit={(key, action) => {
                      if (action === 'remove' && typeof key === 'string') close(key);
                    }}
                    items={tabs.open.map((id) => {
                      const screen = opened.get(id) ?? byId.get(id);
                      const title = screen?.title ?? id;
                      return {
                        key: id,
                        label: title,
                        // Ant provides one remove label for the whole bar. Identify each workspace tab to assistive technology.
                        closeIcon: (
                          <CloseOutlined
                            ref={(icon) => {
                              icon?.closest('button')?.setAttribute('aria-label', `Close ${title}`);
                            }}
                          />
                        ),
                        children: (
                          <div className="rail8-panel">
                            <Suspense fallback={<Spin aria-label={`Opening ${title}`} />}>{screen?.render(open)}</Suspense>
                          </div>
                        ),
                      };
                    })}
                  />
                )}
              </Layout.Content>
            </Layout>
          </Layout>
        </RegisteredScreens.Provider>
      )}
    </Rail8Theme>
  );
}
