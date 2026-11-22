import { FullscreenOutlined, MinusOutlined, FullscreenExitOutlined } from '@ant-design/icons';
import type { SplitView } from './SplitLayout';
import { Tool } from './Tool';

/** Minimize and Maximize one area of the split, or Restore split view from either. */
export function PaneSizeTools({ pane, label, upper = false, view, onChange }: { pane: string; label: string; upper?: boolean; view: SplitView; onChange: (view: SplitView) => void }) {
  const sizeButton = (verb: string, next: SplitView, icon: typeof FullscreenOutlined) => {
    const restore = view === next;
    return <Tool label={restore ? 'Restore split view' : `${verb} ${pane}`} icon={restore ? FullscreenExitOutlined : icon} onClick={() => onChange(restore ? 'split' : next)} />;
  };
  return (
    <span role="group" aria-label={label}>
      {sizeButton('Minimize', upper ? 'maximized' : 'minimized', MinusOutlined)}
      {sizeButton('Maximize', upper ? 'minimized' : 'maximized', FullscreenOutlined)}
    </span>
  );
}
