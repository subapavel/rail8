import type { FlowNode, FlowView, Path } from './types';

export interface SimulationRange {
  list: Path;
  startIndex: number;
  endIndex: number;
  steps: FlowNode[];
}

/** The steps after the start connection and before the end connection in the same list. */
export function simulationRange(view: FlowView, start?: Path, end?: Path): SimulationRange | null {
  if (!start || !end || start.length !== end.length) return null;
  const startIndex = start.at(-1);
  const endIndex = end.at(-1);
  const list = start.slice(0, -1);
  if (typeof startIndex !== 'number' || typeof endIndex !== 'number' || startIndex >= endIndex ||
      !list.every((part, i) => end[i] === part)) return null;
  const steps = view.nodes.filter(node => {
    const index = node.path.at(-1);
    return node.kind === 'step' && node.path.length === start.length && list.every((part, i) => node.path[i] === part) &&
      typeof index === 'number' && index >= startIndex && index < endIndex;
  });
  return steps.length === endIndex - startIndex ? { list, startIndex, endIndex, steps } : null;
}
