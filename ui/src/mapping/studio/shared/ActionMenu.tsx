import { Dropdown, type MenuProps } from 'antd';
import { CheckOutlined, DeleteOutlined, RightOutlined } from '@ant-design/icons';
import { useEffect, useState, useRef, type KeyboardEvent } from 'react';
import { createPortal } from 'react-dom';

/**
 * One item of an action menu: it runs, or opens its `children` in a flyout, or is shown disabled. A child with
 * `checked` set is a choice: the flyout of such children is a single-select list with the one in force marked.
 * `danger` marks a destructive item.
 */
export interface ActionMenuItem {
  label: string;
  run?: () => void;
  disabled?: boolean;
  danger?: boolean;
  /** Tells apart items of one menu that share a label. */
  key?: string;
  checked?: boolean;
  children?: ActionMenuItem[];
}

/** A group of an action menu, under its label when it has one. */
export interface ActionMenuGroup {
  label?: string;
  items: ActionMenuItem[];
}

/** An open action menu: what it is named, its groups, the element it belongs to, and the point it opens at, if any. */
export interface OpenActionMenu {
  label: string;
  groups: ActionMenuGroup[];
  /** The element the menu opens below, unless `at` is given, and that takes the focus back. */
  anchor: HTMLElement;
  /** The viewport point a context menu click opened it at. */
  at?: { x: number; y: number };
}

/** The keys that open a context menu from the keyboard. */
export const isContextMenuKey = (e: KeyboardEvent) => e.key === 'ContextMenu' || (e.shiftKey && e.key === 'F10');

/** A context menu positioned below a field or at the pointer, with keyboard-accessible flyouts. */
export function ActionMenu({ menu, onClose }: { menu: OpenActionMenu; onClose: () => void }) {
  const rect = menu.anchor.getBoundingClientRect();
  const point = menu.at ?? { x: rect.left, y: rect.bottom };
  const close = (restore: boolean) => {
    onClose();
    if (restore && menu.anchor.isConnected) menu.anchor.focus({ preventScroll: true });
  };
  useEffect(() => {
    window.addEventListener('resize', onClose);
    return () => window.removeEventListener('resize', onClose);
  }, [onClose]);

  const options = (items: ActionMenuItem[], prefix: string): NonNullable<MenuProps['items']> =>
    items.map((item) => {
      const key = `${prefix}/${item.key ?? item.label}`;
      return item.children
        ? { key, label: item.label, disabled: item.disabled, children: options(item.children, key) }
        : {
            key,
            label: item.label,
            disabled: item.disabled,
            danger: item.danger,
            role: item.checked !== undefined ? 'menuitemradio' : 'menuitem',
            'aria-checked': item.checked,
            icon: item.danger ? <DeleteOutlined aria-hidden /> : item.checked ? <CheckOutlined aria-hidden /> : undefined,
            onClick: () => {
              close(true);
              item.run?.();
            },
          };
    });
  const entries: NonNullable<MenuProps['items']> = menu.groups.flatMap(({ label, items }, index) => {
    const children = options(items, String(index));
    return label ? [{ type: 'group', key: `group-${index}`, label, role: 'group', 'aria-label': label, children }] : children;
  });
  return createPortal(
    <Dropdown
      open
      autoFocus
      placement="bottomLeft"
      trigger={['click']}
      align={{ offset: [0, 0] }}
      onOpenChange={(open, info) => {
        if (!open && info.source === 'trigger') close(false);
      }}
      menu={{
        'aria-label': menu.label,
        items: entries,
        expandIcon: <RightOutlined aria-hidden />,
        triggerSubMenuAction: 'click',
        onKeyDown: (event) => {
          if (event.key === 'Escape') {
            event.preventDefault();
            close(true);
          }
        },
      }}
    >
      <span aria-hidden style={{ position: 'fixed', pointerEvents: 'none', width: 1, height: 1, left: point.x, top: point.y - 1 }} />
    </Dropdown>,
    document.body,
  );
}

/**
 * One action menu at a time: `showMenu` opens one, `menu` is the open one, `closeMenu` closes it and `element`
 * renders it.
 */
export function useActionMenu() {
  const [open, setOpen] = useState<{ menu: OpenActionMenu; serial: number } | null>(null);
  const serial = useRef(0);
  const showMenu = (menu: OpenActionMenu) => setOpen({ menu, serial: ++serial.current });
  // a menu opened while another is open replaces it
  const element = open && <ActionMenu key={open.serial} menu={open.menu} onClose={() => setOpen(null)} />;
  return { menu: open?.menu ?? null, showMenu, closeMenu: () => setOpen(null), element };
}

/** Where a context menu click happened, for `OpenActionMenu.at`; none for a key or a click without a point. */
export function pointOf(event: { type: string; clientX?: number; clientY?: number }): OpenActionMenu['at'] {
  return event.type === 'contextmenu' && (event.clientX || event.clientY) ? { x: event.clientX!, y: event.clientY! } : undefined;
}
