import { AppstoreOutlined, MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons';
import { Button, Layout, Menu } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { groupedScreens, type Screen } from './screens';

export function WorkspaceNavigation({ screens, active, open }: {
  screens: readonly Screen[];
  active: string | null;
  open: (screen: Screen) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const anchor = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const label = expanded ? 'Collapse navigation' : 'Expand navigation';

  useEffect(() => {
    if (!expanded) return;
    const outside = (event: Event) => {
      if (event.target instanceof Node && !anchor.current?.contains(event.target)) setExpanded(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      event.preventDefault();
      event.stopPropagation();
      setExpanded(false);
      trigger.current?.focus({ preventScroll: true });
    };
    document.addEventListener('pointerdown', outside, true);
    document.addEventListener('focusin', outside, true);
    document.addEventListener('keydown', escape, true);
    return () => {
      document.removeEventListener('pointerdown', outside, true);
      document.removeEventListener('focusin', outside, true);
      document.removeEventListener('keydown', escape, true);
    };
  }, [expanded]);

  return (
    <div ref={anchor} className="rail8-nav-anchor">
      <Layout.Sider width={216} collapsed={!expanded} collapsible collapsedWidth={0} trigger={null} className="rail8-sider">
        <Button
          ref={trigger}
          type="text"
          className="rail8-nav-trigger"
          aria-label={label}
          title={label}
          aria-expanded={expanded}
          aria-controls="rail8-navigation"
          icon={expanded ? <MenuFoldOutlined /> : <MenuUnfoldOutlined />}
          onClick={() => setExpanded(!expanded)}
        />
        <nav id="rail8-navigation" aria-label="Screens" hidden={!expanded}>
          <img className="rail8-nav-logo" src={`${import.meta.env.BASE_URL}rail8-logo-dark.svg`} alt="Rail8" />
          <Menu
            mode="inline"
            theme="dark"
            selectedKeys={active ? [active] : []}
            items={Array.from(groupedScreens(screens), ([group, entries]) => ({
              key: group,
              type: 'group',
              label: group,
              children: entries.map((screen) => ({
                key: screen.id,
                label: screen.title,
                icon: screen.icon ?? <AppstoreOutlined aria-hidden />,
                'aria-label': screen.title,
                'aria-current': screen.id === active ? 'page' : undefined,
              })),
            }))}
            onClick={({ key }) => {
              const screen = screens.find((entry) => entry.id === key);
              if (screen) open(screen);
              setExpanded(false);
              trigger.current?.focus({ preventScroll: true });
            }}
          />
        </nav>
      </Layout.Sider>
    </div>
  );
}
