import { describe, expect, it } from 'vitest';
import { canvasGraph, FLOW_FRAME } from './canvasGraph';
import { view } from './view';
import { textsIn } from '../../language';

const en = textsIn('en');

const flow = view(`schemaVersion: 1
kind: flow
id: invoices
from: { endpoint: incoming }
steps:
  - type: split
    xpath: /items
  - type: process
    process: dispatch
processes:
  dispatch:
    steps:
      - type: to
        endpoint: warehouse
`, { endpoints: [], flows: [], steps: [] }, en);

describe('the flow canvas graph', () => {
  it('keeps step paths, nested frames and insertion places while drawing the two systems outside', () => {
    const graph = canvasGraph(flow, false, false);
    const byId = new Map(graph.nodes.map(n => [n.id, n]));
    expect(byId.get('/from')?.parentId).toBeUndefined();
    expect(byId.get('/steps/0')?.parentId).toBe(FLOW_FRAME);
    expect(byId.get('/steps/1')?.parentId).toBe('/steps/0#each');
    expect(byId.get('/processes/dispatch/steps/0')?.parentId).toBe('/processes/dispatch/steps');
    const receiver = graph.nodes.find(n => n.role === 'receiver');
    expect(receiver?.label).toBe('warehouse');
    expect(receiver?.parentId).toBeUndefined();
    expect(graph.edges.some(e => e.source === '/processes/dispatch/steps/0' && e.target === receiver?.id)).toBe(true);
    expect(graph.edges.flatMap(e => e.insertAt ? [e.insertAt] : [])).toEqual([
      ...flow.edges.flatMap(e => e.insertAt ? [e.insertAt] : []), ['processes', 'dispatch', 'steps', 0],
    ]);
  });

  it('reroutes the same participants to the folded frame and restores the expanded connections', () => {
    const expanded = canvasGraph(flow, false, false);
    const folded = canvasGraph(flow, false, true);
    expect(folded.nodes.map(n => n.role)).toEqual(['frame', 'sender', 'receiver']);
    const sender = folded.nodes[1]!;
    const receiver = folded.nodes[2]!;
    expect(folded.edges.map(e => [e.source, e.target])).toEqual([[sender.id, FLOW_FRAME], [FLOW_FRAME, receiver.id]]);
    expect(sender.x + sender.width).toBeLessThan(0);
    expect(receiver.x).toBeGreaterThan(folded.nodes[0]!.width);
    expect(canvasGraph(flow, false, false)).toEqual(expanded);
  });

  it('resizes the frame and keeps the external systems outside on either axis', () => {
    for (const vertical of [true, false]) {
      const graph = canvasGraph(flow, vertical, false, { width: 1400, height: 1400 });
      const [frame] = graph.nodes;
      expect(frame!.width).toBeGreaterThanOrEqual(1400);
      expect(frame!.height).toBeGreaterThanOrEqual(1400);
      const receiver = graph.nodes.find(n => n.role === 'receiver')!;
      expect(vertical ? receiver.x > frame!.width : receiver.y > frame!.height).toBe(true);
      expect(graph.edges.find(e => e.target === receiver.id)?.deliverySide).toBe(vertical ? frame!.width : frame!.height);
    }
  });

  it('draws the final send directly to the receiver with the append action, and keeps fan-out receiver IDs distinct', () => {
    const picture = view(`schemaVersion: 1
kind: flow
id: dispatch
from: { endpoint: in }
steps:
  - type: to
    byProp: destination
    endpoints: { retail: sales, 'retail#receiver': wholesale }
`, { endpoints: [], flows: [], steps: [] }, en);
    for (const vertical of [false, true]) {
      const graph = canvasGraph(picture, vertical, false);
      expect(new Set(graph.nodes.map(n => n.id)).size).toBe(graph.nodes.length);
      expect(graph.nodes.some(n => n.id === '/steps#end')).toBe(false);
      const receivers = graph.nodes.filter(n => n.role === 'receiver');
      expect(receivers.map(n => n.label)).toEqual(['sales', 'wholesale']);
      for (const receiver of receivers) {
        const edge = graph.edges.find(e => e.target === receiver.id)!;
        expect(edge.insertAt).toEqual(['steps', 1]);
        expect(edge.source).toBe(receiver.original!.id);
        expect(edge.deliverySide).toBeUndefined();
      }
    }
  });
});
