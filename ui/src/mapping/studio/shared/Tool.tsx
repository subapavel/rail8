import { Button, Dropdown } from 'antd';
import { CheckOutlined, DownOutlined } from '@ant-design/icons';
import { useRef, useState, type ComponentType } from 'react';

interface ToolProps {
  label: string;
  icon: ComponentType;
  /** A toggle tool: whether it is on. */
  pressed?: boolean;
  onClick: () => void;
  disabled?: boolean;
}

/** An icon button of a toolbar, named by its label. */
export function Tool({ label, icon: Icon, pressed, onClick, disabled }: ToolProps) {
  return (
    <Button
      type={pressed ? 'primary' : 'text'}
      size="small"
      aria-label={label}
      title={label}
      aria-pressed={pressed}
      disabled={disabled}
      icon={<Icon aria-hidden />}
      onClick={onClick}
    />
  );
}

/** One option of a `ToolMenu`: it runs; `checked` marks the option in force. */
export interface ToolMenuItem {
  label: string;
  run: () => void;
  checked?: boolean;
  disabled?: boolean;
}

/** A tool's options as a menu below its caret; `icon` shows on the caret when the menu is a tool of its own. */
export function ToolMenu({ label, items, icon: Icon, disabled }: { label: string; items: ToolMenuItem[]; icon?: ComponentType; disabled?: boolean }) {
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  return (
    <Dropdown
      autoFocus
      trigger={['click']}
      placement="bottomLeft"
      open={open}
      onOpenChange={setOpen}
      disabled={disabled}
      menu={{
        'aria-label': label,
        items: items.map((item) => ({
          key: item.label,
          label: item.label,
          role: item.checked === undefined ? 'menuitem' : 'menuitemradio',
          'aria-checked': item.checked,
          disabled: item.disabled,
          icon: item.checked ? <CheckOutlined aria-hidden /> : undefined,
          onClick: () => {
            setOpen(false);
            trigger.current?.focus({ preventScroll: true });
            item.run();
          },
        })),
      }}
    >
      <Button
        ref={trigger}
        type="text"
        size="small"
        aria-label={label}
        title={label}
        aria-haspopup="menu"
        aria-expanded={open}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown') {
            event.preventDefault();
            setOpen(true);
          }
        }}
        disabled={disabled}
        icon={Icon ? <Icon aria-hidden /> : <DownOutlined aria-hidden />}
      />
    </Dropdown>
  );
}
