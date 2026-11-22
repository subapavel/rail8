import { ApartmentOutlined, EllipsisOutlined, FullscreenExitOutlined, FullscreenOutlined, MinusOutlined, SlidersOutlined } from '@ant-design/icons';
import { Button, Splitter, Tooltip, Typography } from 'antd';
import { useId, useState, type KeyboardEvent, type ReactNode } from 'react';

export type InspectorView = 'minimized' | 'split' | 'maximized';

interface FlowInspectorProps {
  mode: InspectorView;
  setMode: (mode: InspectorView) => void;
  selection: string;
  onFlowSelect: (() => void) | undefined;
  canvas: ReactNode;
  children: ReactNode;
}

const DEFAULT_SHARE = 34;
const clampShare = (percent: number) => Math.max(15, Math.min(80, percent));

/** Resizes the properties below the graph and keeps both the camera and unfinished fields when the split is restored. */
export function FlowInspector({ mode, setMode, selection, onFlowSelect, canvas, children }: FlowInspectorProps) {
  const [share, setShare] = useState(DEFAULT_SHARE);
  const fieldsId = useId();
  const split = mode === 'split';
  const minimized = mode === 'minimized';
  const maximized = mode === 'maximized';
  const minimizeLabel = minimized ? 'Restore split view' : 'Minimize properties';
  const maximizeLabel = maximized ? 'Restore split view' : 'Maximize properties';
  const resizeByKey = (event: KeyboardEvent) => {
    const next = { ArrowUp: share + 5, ArrowDown: share - 5, Home: 15, End: 80 }[event.key];
    if (next === undefined) return;
    event.preventDefault();
    setShare(clampShare(next));
  };
  const restoreOnEscape = (event: KeyboardEvent) => {
    if (!maximized || event.key !== 'Escape' || event.defaultPrevented) return;
    if (!(event.target as HTMLElement).closest('[role="combobox"][aria-expanded="true"]')) setMode('split');
  };
  const height = minimized ? 38 : maximized ? '100%' : `${share}%`;
  return (
    <div className={`rail8-flow-split properties-${mode}`}>
      <Splitter
        orientation="vertical"
        onResize={([top, bottom]) => {
          if (split && top !== undefined && bottom !== undefined && top + bottom > 0) setShare(clampShare((100 * bottom) / (top + bottom)));
        }}
        onDraggerDoubleClick={() => setShare(DEFAULT_SHARE)}
        draggerIcon={
          split && (
            <button type="button" className="rail8-properties-grip" aria-label="Resize properties with arrow keys" onKeyDown={resizeByKey}>
              <EllipsisOutlined aria-hidden />
            </button>
          )
        }
      >
        <Splitter.Panel min={split ? '20%' : 0} resizable={split}>
          {canvas}
        </Splitter.Panel>
        <Splitter.Panel min={split ? '15%' : 0} size={height} resizable={split}>
          <section className="rail8-properties" aria-label="Flow properties" onKeyDown={restoreOnEscape}>
            <header>
              <Button
                type="text"
                size="small"
                icon={<SlidersOutlined aria-hidden />}
                aria-controls={fieldsId}
                aria-expanded={!minimized}
                onClick={() => setMode(minimized ? 'split' : 'minimized')}
              >
                Properties
              </Button>
              <Typography.Text ellipsis type="secondary">{selection}</Typography.Text>
              <div className="rail8-properties-actions">
                {onFlowSelect && (
                  <Tooltip title="Show the flow's settings">
                    <Button size="small" type="text" aria-label="Show the flow's settings" icon={<ApartmentOutlined aria-hidden />} onClick={onFlowSelect} />
                  </Tooltip>
                )}
                <Button
                  size="small"
                  type="text"
                  aria-label={minimizeLabel}
                  title={minimizeLabel}
                  icon={minimized ? <FullscreenExitOutlined aria-hidden /> : <MinusOutlined aria-hidden />}
                  onClick={() => setMode(minimized ? 'split' : 'minimized')}
                />
                <Button
                  size="small"
                  type="text"
                  aria-label={maximizeLabel}
                  title={maximizeLabel}
                  icon={maximized ? <FullscreenExitOutlined aria-hidden /> : <FullscreenOutlined aria-hidden />}
                  onClick={() => setMode(maximized ? 'split' : 'maximized')}
                />
              </div>
            </header>
            <div className="rail8-properties-fields" id={fieldsId} hidden={minimized}>
              {children}
            </div>
          </section>
        </Splitter.Panel>
      </Splitter>
    </div>
  );
}
