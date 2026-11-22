import {
  AimOutlined, ApiOutlined, ApartmentOutlined, BranchesOutlined, CodeOutlined, DeleteOutlined, DownOutlined, EllipsisOutlined,
  ExclamationCircleOutlined, ExportOutlined, FileProtectOutlined, FilterOutlined, FlagOutlined, ImportOutlined, KeyOutlined,
  LockOutlined, PartitionOutlined, PlusOutlined, RightOutlined, StopOutlined, SwapOutlined, WarningOutlined, ZoomInOutlined, ZoomOutOutlined,
} from '@ant-design/icons';
import {
  Background, BaseEdge, EdgeLabelRenderer, getBezierPath, getViewportForBounds, Handle, MarkerType, NodeResizeControl, Panel,
  Position, ReactFlow, useReactFlow, useViewport, type Edge, type EdgeProps, type Node, type NodeChange, type NodeProps,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { Button, Dropdown, Space, type MenuProps } from 'antd';
import { memo, useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { canvasGraph, canvasStructure, FLOW_FRAME, TILE, type CanvasEdge, type CanvasItem, type Point, type Size } from './canvasGraph';
import { placeOf } from './edit';
import { simulationRange, type SimulationRange } from './simulationRange';
import type { FlowEdge, FlowNode, FlowProblem, FlowView, Path } from './types';
import { pointer } from './view';
import './FlowCanvas.css';

export interface FlowCanvasProps {
  view: FlowView;
  selected: string | null;
  onSelect: (node: FlowNode) => void;
  onInsert?: (at: Path) => void;
  onRemove?: (node: FlowNode) => void;
  onFlowSelect?: () => void;
  onRangeChange?: (range: SimulationRange | null) => void;
  title?: string;
  description?: string;
  direction?: 'horizontal' | 'vertical';
  /** Advances when the inspector asks to reveal a location, including a repeated search result. */
  inspectSequence?: number;
  inspectTarget?: string;
  layoutRevision?: number;
}

export const selectionOf = (node: FlowNode) => node.kind === 'lane' ? node.id : pointer(node.path);

const icons: Record<string, typeof CodeOutlined> = {
  route: BranchesOutlined, split: PartitionOutlined, process: ApartmentOutlined, filter: FilterOutlined,
  extractKey: KeyOutlined, mapping: SwapOutlined, xslt: SwapOutlined, xsd: FileProtectOutlined, to: ExportOutlined,
  destination: ExportOutlined, end: StopOutlined,
};

type Station = Node<{
  item: CanvasItem; vertical: boolean; active: boolean; problems: FlowProblem[]; select: () => void;
}, 'station'>;
type Frame = Node<{
  title: string; description: string; count: number; folded: boolean; vertical: boolean; size: Size; minimum: Size;
  toggle: () => void; inspect?: () => void; resize: (size: Size) => void;
}, 'frame'>;
type CanvasNode = Station | Frame;

function Handles({ vertical }: { vertical: boolean }) {
  return <>
    <Handle type="target" position={vertical ? Position.Top : Position.Left} isConnectable={false} />
    <Handle type="source" position={vertical ? Position.Bottom : Position.Right} isConnectable={false} />
    <Handle id="side" type="target" position={vertical ? Position.Left : Position.Top} isConnectable={false} />
    <Handle id="side" type="source" position={vertical ? Position.Right : Position.Bottom} isConnectable={false} />
  </>;
}

const FrameNode = memo(function FrameNode({ data }: NodeProps<Frame>) {
  const { title, description, count, folded, size, minimum, resize } = data;
  return <section className={`flow-boundary${folded ? ' folded' : ''}`} aria-label={`Flow ${title}`}>
    <Handles vertical={data.vertical} />
    <header className="flow-boundary-header nodrag nopan">
      <Button size="small" type="text" aria-label={folded ? 'Expand flow' : 'Collapse flow'} aria-expanded={!folded}
        icon={folded ? <RightOutlined /> : <DownOutlined />} onClick={data.toggle} />
      <div>
        <div className="flow-boundary-title">
          <Button type="text" size="small" aria-label="Flow properties" title="Flow properties" icon={<ApartmentOutlined />} onClick={data.inspect}>{title}</Button>
          <span>{count} {count === 1 ? 'step' : 'steps'}</span>
        </div>
        {description && <p title={description}>{description}</p>}
      </div>
    </header>
    {!folded && <NodeResizeControl position="bottom-right" className="flow-size-grip" minWidth={minimum.width} minHeight={minimum.height}
      maxWidth={Math.max(12000, minimum.width)} maxHeight={Math.max(12000, minimum.height)} onResize={(_, next) => resize(next)}>
      <button type="button" aria-label="Resize flow frame" title="Drag to resize, or use the arrow keys" onKeyDown={event => {
        const delta: Record<string, Point> = { ArrowRight: { x: 24, y: 0 }, ArrowLeft: { x: -24, y: 0 }, ArrowDown: { x: 0, y: 24 }, ArrowUp: { x: 0, y: -24 } };
        const change = delta[event.key];
        if (!change) return;
        event.preventDefault();
        event.stopPropagation();
        resize({
          width: Math.max(minimum.width, Math.min(12000, size.width + change.x)),
          height: Math.max(minimum.height, Math.min(12000, size.height + change.y)),
        });
      }}>◢</button>
    </NodeResizeControl>}
  </section>;
});

const StationNode = memo(function StationNode({ data }: NodeProps<Station>) {
  const { item, active, vertical, problems, select } = data;
  const original = item.original!;
  const system = item.role === 'sender' || item.role === 'receiver';
  const error = problems.some(p => !p.warning);
  const type = original.type ?? original.kind;
  const Glyph = icons[type] ?? CodeOutlined;
  const marks = <span className="flow-station-marks">
    {problems.length > 0 && (error ? <ExclamationCircleOutlined className="error" title={problems.map(p => p.message).join('\n')} />
      : <WarningOutlined className="warning" title={problems.map(p => p.message).join('\n')} />)}
    {original.locked && <LockOutlined title={original.locked} />}
  </span>;
  if (item.role === 'group') return <div className="flow-inner-frame"><Handles vertical={vertical} />
    {original.kind === 'lane' ? <Button type="text" size="small" onClick={select}>{item.label}</Button> : <span>{item.label}</span>}
  </div>;
  return <div className={`flow-station ${vertical ? 'vertical' : ''}`}>
    <Handles vertical={vertical} />
    {!system && (original.type === 'to' || original.kind === 'destination') && <Handle id="delivery" type="source"
      position={vertical ? Position.Bottom : Position.Right} style={vertical ? { left: '74%' } : { top: '74%' }} isConnectable={false} />}
    {system ? <Button className={`flow-participant ${item.role}`} onClick={select} aria-label={`Inspect ${item.label}`} aria-pressed={active}>
      <span>{item.role === 'sender' ? <ImportOutlined /> : <ApiOutlined />}{item.role === 'sender' ? 'Sender' : 'Receiver'}</span>
      <strong title={item.label}>{item.label}</strong>
      {item.detail && <small title={item.detail}>{item.detail}</small>}
      {marks}
    </Button> : <Button className="flow-tile" data-type={type} aria-pressed={active} aria-label={`Inspect ${item.label}`}
      title={[item.label, original.detail, ...(original.rows ?? [])].filter(Boolean).join('\n')} onClick={select}>
      <Glyph aria-hidden /><span className="flow-caption">{item.label}</span>{marks}
    </Button>}
  </div>;
});

type Boundary = 'start' | 'end';
type Action = Edge<{
  edge: CanvasEdge; target: CanvasItem; starts: boolean; ends: boolean; vertical: boolean; mark?: (boundary: Boundary) => void;
  insert?: () => void; remove?: () => void;
}, 'action'>;

const ActionEdge = memo(function ActionEdge(props: EdgeProps<Action>) {
  const { edge, target, starts, ends, mark, insert, remove, vertical } = props.data!;
  let [path, x, y] = getBezierPath(props);
  if (edge.deliverySide !== undefined) {
    // Leave the station along its line, then turn through the gap between columns.
    // The shared turn toward the participants lies beyond the frame, clear of the steps.
    const outside = edge.deliverySide + 36;
    const { sourceX, sourceY, targetX, targetY } = props;
    path = vertical
      ? `M${sourceX},${sourceY} v42 H${outside} V${targetY} H${targetX}`
      : `M${sourceX},${sourceY} h42 V${outside} H${targetX} V${targetY}`;
    x = vertical ? (outside + targetX) / 2 : targetX;
    y = vertical ? targetY : (outside + targetY) / 2;
  }
  const actionLabel = `Actions before ${target.label}`;
  const markerTitle = starts && ends ? 'Simulation starts and ends' : starts ? 'Simulation starts' : 'Simulation ends';
  const items: MenuProps['items'] = [
    ...(insert ? [{ key: 'insert', label: 'Insert step here', icon: <PlusOutlined aria-hidden />, onClick: insert }] : []),
    ...(mark ? [
      { key: 'start', label: starts ? 'Clear simulation start' : 'Set simulation start here', icon: <FlagOutlined aria-hidden />, onClick: () => mark('start') },
      { key: 'end', label: ends ? 'Clear simulation end' : 'Set simulation end here', icon: <StopOutlined aria-hidden />, onClick: () => mark('end') },
    ] : []),
    ...(remove ? [{ key: 'remove', label: `Delete ${target.label}`, danger: true, icon: <DeleteOutlined aria-hidden />, onClick: remove }] : []),
  ];
  return <>
    <BaseEdge id={props.id} path={path} markerEnd={props.markerEnd} style={props.style} />
    <EdgeLabelRenderer>
      <div className={`flow-connection-actions nodrag nopan${vertical ? ' vertical' : ''}`}
        style={{ transform: `translate(-50%, -50%) translate(${x}px, ${y}px)` }}>
        {edge.label && <span className="flow-branch-label" title={edge.label}>{edge.label}</span>}
        {insert && edge.insertAt && <Button size="small" shape="circle" aria-label={`Add a step at ${placeOf(edge.insertAt)}`}
          title="Insert step here" icon={<PlusOutlined />} onClick={insert} />}
        {items.length > 0 && <Dropdown menu={{ items, 'aria-label': actionLabel }} trigger={['click']}>
          <Button size="small" shape="circle" className={starts || ends ? 'marked' : ''} aria-label={actionLabel}
            title={starts || ends ? `${markerTitle} before ${target.label}` : actionLabel}
            icon={starts ? <FlagOutlined /> : ends ? <StopOutlined /> : <EllipsisOutlined />} />
        </Dropdown>}
      </div>
    </EdgeLabelRenderer>
  </>;
});

const nodeTypes = { station: StationNode, frame: FrameNode };
const edgeTypes = { action: ActionEdge };
const arrow = { type: MarkerType.ArrowClosed, width: 13, height: 13, color: 'var(--flow-wire)' };
const dashes: Partial<Record<FlowEdge['kind'], string>> = { process: '6 4', ends: '2 4' };

function Camera({ fit, manual }: { fit: () => void; manual: () => void }) {
  const { zoom } = useViewport();
  const { zoomIn, zoomOut } = useReactFlow();
  return <Panel position="bottom-right" className="flow-camera nodrag nopan">
    <Space.Compact>
      <Button size="small" type="text" aria-label="Zoom out" title="Zoom out" icon={<ZoomOutOutlined />} onClick={() => { manual(); void zoomOut(); }} />
      <span>{Math.round(zoom * 100)}%</span>
      <Button size="small" type="text" aria-label="Zoom in" title="Zoom in" icon={<ZoomInOutlined />} onClick={() => { manual(); void zoomIn(); }} />
      <Button size="small" type="text" aria-label="Fit diagram" title="Fit diagram" icon={<AimOutlined />} onClick={fit} />
    </Space.Compact>
  </Panel>;
}

/** Displays the semantic graph. Fold, size, camera, station positions and simulation markers are local to the canvas. */
export function FlowCanvas({ view, selected, onSelect, onInsert, onRemove, onFlowSelect, onRangeChange, title, description, direction, inspectSequence = 0, inspectTarget, layoutRevision = 0 }: FlowCanvasProps) {
  const host = useRef<HTMLDivElement>(null);
  const [box, setBox] = useState({ width: 0, height: 0, visible: false });
  const [ready, setReady] = useState(false);
  const [foldedAt, setFoldedAt] = useState<number | null>(null);
  const folded = foldedAt === inspectSequence;
  const [clicked, setClicked] = useState<{ id: string; path: string; sequence: number } | null>(null);
  const [sizes, setSizes] = useState<Partial<Record<'horizontal' | 'vertical', Size>>>({});
  const structure = useMemo(() => canvasStructure(view, layoutRevision), [view, layoutRevision]);
  const [markers, setMarkers] = useState<{ structure: string; start?: string; end?: string } | null>(null);
  type Positions = Partial<Record<'horizontal' | 'vertical', Record<string, Point>>>;
  const [moved, setMoved] = useState<{ structure: string; positions: Positions } | null>(null);
  const manual = useRef(false);
  const { setViewport, getViewport, setCenter } = useReactFlow();
  useLayoutEffect(() => {
    const el = host.current!;
    const measure = () => setBox(old => {
      if (!el.clientWidth || !el.clientHeight) return old.visible ? { ...old, visible: false } : old;
      return old.visible && old.width === el.clientWidth && old.height === el.clientHeight ? old : { width: el.clientWidth, height: el.clientHeight, visible: true };
    });
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  const vertical = (box.width > 0 && box.width < 520) || direction === 'vertical';
  const axis = vertical ? 'vertical' : 'horizontal';
  const expanded = useMemo(() => canvasGraph(view, vertical, false, sizes[axis]), [view, vertical, sizes, axis]);
  const graph = useMemo(() => folded ? canvasGraph(view, vertical, true) : expanded, [view, vertical, folded, expanded]);
  const start = expanded.edges.find(edge => edge.id === markers?.start && edge.insertAt);
  const end = expanded.edges.find(edge => edge.id === markers?.end && edge.insertAt);
  if (markers && (markers.structure !== structure || (markers.start && !start) || (markers.end && !end))) setMarkers(null);
  const range = useMemo(() => simulationRange(view, start?.insertAt, end?.insertAt), [view, start, end]);
  useLayoutEffect(() => { onRangeChange?.(range); }, [range, onRangeChange]);
  const byId = useMemo(() => new Map(graph.nodes.map(n => [n.id, n])), [graph]);
  const searched = inspectTarget ? byId.get(inspectTarget) : undefined;
  const activeId = clicked?.path === selected && clicked?.sequence === inspectSequence ? clicked.id
    : searched?.original && selectionOf(searched.original) === selected ? searched.id : selected;
  const problemsById = useMemo(() => new Map(view.nodes.map(n => [n.id, n.kind === 'lane' || n.kind === 'group' || n.kind === 'end' ? [] :
    view.problems.filter(p => n.path.every((s, i) => p.path[i] === s) && !(n.kind === 'step' && p.path.length > n.path.length + 1 && p.path[n.path.length] === 'endpoints'))])), [view]);
  const positions = moved?.structure === structure ? moved.positions[axis] : undefined;
  const automaticNodes = useMemo<CanvasNode[]>(() => graph.nodes.map(item => {
    if (item.role === 'frame') return {
      id: item.id, type: 'frame', position: { x: 0, y: 0 }, width: item.width, height: item.height, draggable: false, zIndex: 0,
      data: { title: title ?? String(view.settings?.values.id ?? 'Flow'), description: description ?? String(view.settings?.values.description ?? ''),
        count: view.nodes.filter(n => n.kind === 'step').length, size: item, minimum: graph.minimum, vertical, folded,
        toggle: () => { manual.current = false; setFoldedAt(folded ? null : inspectSequence); }, inspect: onFlowSelect,
        resize: ({ width, height }) => { manual.current = true; setSizes(old => ({ ...old, [axis]: { width, height } })); },
      },
    };
    const original = item.original!;
    const parent = item.parentId && byId.get(item.parentId);
    const left = vertical ? 12 : 35;
    const top = parent && parent.id === FLOW_FRAME ? 65 : 34;
    const extent: [[number, number], [number, number]] | undefined = parent && item.role === 'station' ? [
      [left, top], [Math.max(left + TILE, parent.width - (vertical ? 113 : 35)), Math.max(top + TILE, parent.height - (vertical ? 12 : 38))],
    ] : undefined;
    const at = { x: item.x - (parent ? parent.x : 0), y: item.y - (parent ? parent.y : 0) };
    const problems = problemsById.get(original.id)!;
    return { id: item.id, type: 'station', width: item.width, height: item.height, position: at, parentId: item.parentId, extent,
      draggable: item.role === 'station' && original.kind === 'step', zIndex: item.role === 'group' ? 0 : 2,
      data: { item, vertical, active: item.id === activeId, problems, select: () => {
        setClicked({ id: item.id, path: selectionOf(original), sequence: inspectSequence });
        onSelect(original);
      } },
    };
  }), [graph, title, description, view, folded, inspectSequence, onFlowSelect, axis, byId, vertical, problemsById, activeId, onSelect]);
  const nodes = useMemo(() => positions ? automaticNodes.map(node => {
    const p = positions[node.id];
    if (!p || !Array.isArray(node.extent)) return node;
    const [min, max] = node.extent;
    return { ...node, position: {
      x: Math.max(min[0], Math.min(p.x, max[0] - TILE)), y: Math.max(min[1], Math.min(p.y, max[1] - TILE)),
    } };
  }) : automaticNodes, [automaticNodes, positions]);

  const edges = useMemo<Action[]>(() => {
    const places = new Set<string>();
    return graph.edges.map(edge => {
      const target = byId.get(edge.target)!;
      const side = edge.kind === 'process' || edge.kind === 'ends';
      const at = edge.insertAt;
      const insert = at && onInsert && !places.has(pointer(at)) ? () => onInsert(at) : undefined;
      if (at) places.add(pointer(at));
      const original = target.original;
      const remove = onRemove && target.role === 'station' && original?.kind === 'step' && !original.locked ? () => onRemove(original) : undefined;
      const mark = at ? (boundary: Boundary) => setMarkers(previous => ({
        ...previous, structure, [boundary]: previous?.[boundary] === edge.id ? undefined : edge.id,
      })) : undefined;
      return {
        id: edge.id, source: edge.source, target: edge.target, type: 'action', markerEnd: arrow, zIndex: 1,
        sourceHandle: edge.deliverySide !== undefined ? 'delivery' : side ? 'side' : undefined,
        targetHandle: side || edge.deliverySide !== undefined ? 'side' : undefined,
        style: { strokeDasharray: dashes[edge.kind] },
        data: { edge, target, vertical, insert, remove, starts: start?.id === edge.id, ends: end?.id === edge.id, mark },
      };
    });
  }, [graph, byId, onInsert, onRemove, start, end, structure, vertical]);
  const moveNodes = (changes: NodeChange<CanvasNode>[]) => {
    const changesAt: Record<string, Point> = {};
    for (const c of changes) if (c.type === 'position' && c.position) changesAt[c.id] = c.position;
    if (Object.keys(changesAt).length) setMoved(old => {
      const previous = old?.structure === structure ? old.positions : {};
      return { structure, positions: { ...previous, [axis]: { ...previous[axis], ...changesAt } } };
    });
  };
  const fit = useCallback(() => {
    if (!box.visible) return;
    const outer = graph.nodes.filter(n => !n.parentId);
    const x = Math.min(...outer.map(n => n.x));
    const y = Math.min(...outer.map(n => n.y));
    const width = Math.max(...outer.map(n => n.x + n.width)) - x;
    const height = Math.max(...outer.map(n => n.y + n.height)) - y;
    void setViewport(getViewportForBounds({ x, y, width, height }, box.width, box.height, 0.12, 1, 0.09));
  }, [box, graph, setViewport]);
  useLayoutEffect(() => { if (ready && !manual.current) fit(); }, [ready, fit]);
  const lastFocus = useRef({ selected, inspectSequence, structure });
  useLayoutEffect(() => {
    if (!ready || !box.visible) return;
    const previous = lastFocus.current;
    const requested = previous.inspectSequence !== inspectSequence;
    if (!requested && previous.selected === selected && previous.structure === structure) return;
    lastFocus.current = { selected, inspectSequence, structure };
    if (!manual.current && !requested) return;
    const n = byId.get((requested && inspectTarget) || selected || '');
    if (!n) return;
    const { x, y, zoom } = getViewport();
    const position = positions?.[n.id];
    const parent = n.parentId && byId.get(n.parentId);
    const cx = (position && parent ? parent.x + position.x : n.x) + n.width / 2;
    const cy = (position && parent ? parent.y + position.y : n.y) + n.height / 2;
    if (x + cx * zoom < 40 || x + cx * zoom > box.width - 40 || y + cy * zoom < 32 || y + cy * zoom > box.height - 32) {
      if (requested) manual.current = true;
      void setCenter(cx, cy, { zoom });
    }
  }, [ready, selected, structure, inspectSequence, inspectTarget, byId, box, getViewport, setCenter, positions]);
  return <div className="flow-map" ref={host}>
    {box.width > 0 && <ReactFlow<CanvasNode, Action> nodes={nodes} edges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes}
      aria-label="Flow diagram" nodesConnectable={false} elementsSelectable={false} nodesFocusable={false} edgesFocusable={false}
      onPaneClick={onFlowSelect} onNodesChange={moveNodes} onInit={() => setReady(true)} onMoveStart={event => { if (event) manual.current = true; }}
      minZoom={0.12} maxZoom={2} nodeDragThreshold={0} zoomOnDoubleClick={false} deleteKeyCode={null} zIndexMode="manual" attributionPosition="bottom-left">
      <Background gap={18} size={1} color="var(--ant-color-border)" />
      <Camera fit={() => { manual.current = false; fit(); }} manual={() => { manual.current = true; }} />
    </ReactFlow>}
  </div>;
}
