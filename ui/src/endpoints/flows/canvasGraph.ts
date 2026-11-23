import type { FlowEdge, FlowNode, FlowView } from './types';

export const FLOW_FRAME = 'flow-frame';
export const TILE = 54;
const SYSTEM = { width: 154, height: 76 };
const GAP = 80;

export interface Size { width: number; height: number }
export interface Point { x: number; y: number }
export interface CanvasItem extends Point, Size {
  id: string;
  role: 'frame' | 'station' | 'group' | 'sender' | 'receiver';
  label: string;
  detail?: string;
  parentId?: string;
  original?: FlowNode;
  /** Source handle inside a process lane, relative to its top-left corner. */
  entry?: Point;
}

export interface CanvasGraph {
  nodes: CanvasItem[];
  edges: CanvasEdge[];
  minimum: Size;
}

export interface CanvasEdge extends FlowEdge {
  /** The perpendicular boundary an intermediate delivery crosses to reach its receiver. */
  deliverySide?: number;
}

/**
 * A key for the structure of the flow `view` draws: the ids and types of its sender, steps and receivers, with
 * `revision`. It changes when a step is added, removed or retyped; a move of steps of one type changes neither ids nor
 * types, so the editor raises `revision` for it.
 */
export function canvasStructure(view: FlowView, revision: number): string {
  return JSON.stringify([revision, view.nodes.filter(n => n.kind === 'step' || n.kind === 'from' || n.kind === 'destination')
    .map(n => [n.id, n.type])]);
}

/** Canvas coordinates are absolute. Only the final React Flow nodes use parent-relative coordinates. */
export function canvasGraph(view: FlowView, vertical: boolean, folded: boolean, size?: Size): CanvasGraph {
  const byId = new Map(view.nodes.map(n => [n.id, n]));
  const calls = new Map(view.edges.filter(e => e.kind === 'calls').map(e => [e.source, byId.get(e.target)]));
  const inside: CanvasItem[] = [];
  const receivers: { item: CanvasItem; source: string }[] = [];
  const sender = view.nodes.find(n => n.kind === 'from');

  for (const n of view.nodes) {
    if (n.kind === 'from' || n.kind === 'flow') continue;
    const group = n.kind === 'lane' || n.kind === 'group';
    const label = n.kind === 'destination' ? String(n.path.at(-1)) : n.kind === 'lane' ? `process ${n.label}` : n.label;
    // The semantic layout has distinct columns for forks and separate lanes for processes.
    // Keep those centres when turning the line sideways and tightening the tiles.
    const along = (n.y + n.height / 2) * 1.4;
    const across = (n.x + n.width / 2) * (vertical ? 0.79 : 0.67);
    inside.push({
      id: n.id, role: group ? 'group' : 'station', label, original: n,
      parentId: n.parent ?? FLOW_FRAME, x: (vertical ? across : along) - TILE / 2,
      y: (vertical ? along : across) - TILE / 2, width: TILE, height: TILE,
    });
    const endpoint = n.kind === 'destination' ? n.label : n.type === 'to' ? n.form?.values.endpoint : undefined;
    if (typeof endpoint !== 'string') continue;
    const called = calls.get(n.id);
    receivers.push({
      source: n.id,
      item: {
        id: `receiver:${n.id}`, role: 'receiver', original: called ?? n,
        label: called?.label ?? endpoint, detail: called ? endpoint : n.kind === 'destination' ? n.detail ?? '' : '',
        x: 0, y: 0, ...SYSTEM,
      },
    });
  }

  const endings = view.edges.filter(e => e.target === '/steps#end');
  const outputs = new Set(receivers.map(r => r.source));
  const terminal = new Map(endings.length && endings.every(e => outputs.has(e.source)) ? endings.map(e => [e.source, e]) : []);
  if (terminal.size) inside.splice(inside.findIndex(n => n.id === '/steps#end'), 1);

  // A group's geometry includes all child captions and its heading, including nested groups.
  const children = new Map<string, CanvasItem[]>();
  for (const item of inside) {
    const list = children.get(item.parentId!) ?? [];
    list.push(item);
    children.set(item.parentId!, list);
  }
  function bounds(items: CanvasItem[]) {
    const left = Math.min(...items.map(n => n.x - (n.role === 'station' && !vertical ? 31 : 0)));
    const top = Math.min(...items.map(n => n.y));
    const right = Math.max(...items.map(n => n.x + n.width + (n.role === 'station' ? vertical ? 118 : 31 : 0)));
    const bottom = Math.max(...items.map(n => n.y + n.height + (n.role === 'station' && !vertical ? 42 : 0)));
    return { left, top, right, bottom };
  }
  const processEntries: CanvasEdge[] = [];
  for (const group of inside.filter(n => n.role === 'group').reverse()) {
    const members = children.get(group.id);
    if (!members?.length) continue;
    const box = bounds(members);
    const first = group.original?.kind === 'lane' ? members.find(n => n.id === `${group.id}/0`) : undefined;
    if (first?.original) {
      const x = first.x + (vertical ? TILE / 2 : -GAP);
      const y = first.y + (vertical ? -GAP : TILE / 2);
      box.left = Math.min(box.left, x);
      box.top = Math.min(box.top, y);
      group.entry = { x: x - box.left + 14, y: y - box.top + 32 };
      processEntries.push({ id: `${group.id}->${first.id}`, source: group.id, target: first.id, kind: 'next', insertAt: first.original.path });
    }
    Object.assign(group, { x: box.left - 14, y: box.top - 32, width: box.right - box.left + 28, height: box.bottom - box.top + 46 });
  }
  const roots = children.get(FLOW_FRAME) ?? [];
  const { left, top, right, bottom } = roots.length ? bounds(roots) : { left: 0, top: 0, right: 0, bottom: 0 };
  const minimum = { width: Math.max(292, right - left + 54), height: Math.max(206, bottom - top + 103) };
  const width = folded ? 304 : Math.max(size?.width ?? 0, minimum.width);
  const height = folded ? 82 : Math.max(size?.height ?? 0, minimum.height);
  for (const item of inside) {
    item.x += (width - (right - left)) / 2 - left;
    item.y += (height - (bottom - top) + 54) / 2 - top;
  }
  const nodes: CanvasItem[] = [{ id: FLOW_FRAME, role: 'frame', label: 'Flow', x: 0, y: 0, width, height }];
  if (!folded) nodes.push(...inside);
  const edges: CanvasEdge[] = folded ? [] : view.edges.filter(e => e.kind !== 'calls' && !(terminal.size && e.target === '/steps#end'));
  if (!folded) edges.push(...processEntries);
  const firstEdge = sender && view.edges.find(e => e.source === sender.id);
  const first = firstEdge && inside.find(n => n.id === firstEdge.target);
  const centre = !folded && first ? vertical ? first.x + TILE / 2 : first.y + TILE / 2 : (vertical ? width : height) / 2;
  if (sender) {
    nodes.push({
      id: sender.id, role: 'sender', original: sender, label: sender.label,
      ...(sender.detail ? { detail: sender.detail } : {}), ...SYSTEM,
      x: vertical ? centre - SYSTEM.width / 2 : -GAP - SYSTEM.width,
      y: vertical ? -GAP - SYSTEM.height : centre - SYSTEM.height / 2,
    });
    if (folded || !firstEdge) edges.push({ id: 'sender-to-frame', source: sender.id, target: FLOW_FRAME, kind: 'next' });
  }
  const straightCount = folded ? receivers.length : terminal.size;
  let straightIndex = 0;
  let sideEnd = -Infinity;
  const stations = new Map(inside.map(n => [n.id, n]));
  receivers.sort((a, b) => {
    const first = stations.get(a.source)!;
    const next = stations.get(b.source)!;
    return vertical ? first.y - next.y : first.x - next.x;
  });
  receivers.forEach(({ item, source }) => {
    const end = terminal.get(source);
    const intermediate = !folded && !end;
    if (intermediate) {
      const station = stations.get(source)!;
      const along = Math.max(sideEnd + 24, vertical ? station.y : station.x);
      item.x = vertical ? width + GAP : along;
      item.y = vertical ? along : height + GAP;
      sideEnd = along + (vertical ? SYSTEM.height : SYSTEM.width);
    } else {
      const offset = (straightIndex++ - (straightCount - 1) / 2) * (vertical ? SYSTEM.width + 22 : SYSTEM.height + 24);
      item.x = vertical ? centre + offset - SYSTEM.width / 2 : width + GAP;
      item.y = vertical ? height + GAP : centre + offset - SYSTEM.height / 2;
    }
    nodes.push(item);
    edges.push({ id: `${source}->${item.id}#external`, source: folded ? FLOW_FRAME : source, target: item.id, kind: 'calls',
      ...(intermediate ? { deliverySide: vertical ? width : height } : {}),
      ...(!folded && end ? { label: 'end', insertAt: end.insertAt } : {}),
    });
  });
  return { nodes, edges, minimum };
}
