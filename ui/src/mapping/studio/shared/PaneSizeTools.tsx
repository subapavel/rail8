import { FullscreenOutlined, MinusOutlined, FullscreenExitOutlined } from '@ant-design/icons';
import { useText } from '../../../language';
import type { SplitView } from './SplitLayout';
import { Tool } from './Tool';

/** The names of the tools of one area of the split: Minimize and Maximize. */
export interface PaneSizeNames {
  minimize: string;
  maximize: string;
}

/** Minimize and Maximize one area of the split, named by `names`, or Restore split view from either. */
export function PaneSizeTools({ names, label, upper = false, view, onChange }: { names: PaneSizeNames; label: string; upper?: boolean; view: SplitView; onChange: (view: SplitView) => void }) {
  const { t } = useText();
  const sizeButton = (name: string, next: SplitView, icon: typeof FullscreenOutlined) => {
    const restore = view === next;
    return <Tool label={restore ? t('studio.split.restore') : name} icon={restore ? FullscreenExitOutlined : icon} onClick={() => onChange(restore ? 'split' : next)} />;
  };
  return (
    <span role="group" aria-label={label}>
      {sizeButton(names.minimize, upper ? 'maximized' : 'minimized', MinusOutlined)}
      {sizeButton(names.maximize, upper ? 'minimized' : 'maximized', FullscreenOutlined)}
    </span>
  );
}
