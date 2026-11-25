import { expect, it } from 'vitest';
import { canvasGraph } from './canvasGraph';
import { simulationRange } from './simulationRange';
import { view } from './view';
import { textsIn } from '../../language';

const en = textsIn('en');

const flow = view(`schemaVersion: 1
kind: flow
id: orders
from: { endpoint: orders-in }
steps:
  - type: xsd
    name: Validate
  - type: to
    name: Ship
    endpoint: warehouse
processes:
  prepare:
    steps:
      - type: jq
      - type: xslt
`, { endpoints: [], steps: [], flows: [] }, en);
const edges = canvasGraph(flow, false, false).edges;
const before = (target: string) => edges.find(edge => edge.target === target)?.insertAt;
const start = before('/steps/0');
const middle = before('/steps/1');
const end = before('receiver:/steps/1');

it('takes only the steps between the two connections, including a final delivery', () => {
  expect(simulationRange(flow, start, end)?.steps.map(n => n.label)).toEqual(['Validate', 'Ship']);
  expect(simulationRange(flow, start, middle)?.steps.map(n => n.label)).toEqual(['Validate']);
  expect(simulationRange(flow, middle, end)?.steps.map(n => n.label)).toEqual(['Ship']);
});

it('requires two increasing positions inside the same step list', () => {
  expect(simulationRange(flow, undefined, middle)).toBeNull();
  expect(simulationRange(flow, start, undefined)).toBeNull();
  expect(simulationRange(flow, middle, middle)).toBeNull();
  expect(simulationRange(flow, end, start)).toBeNull();
  expect(simulationRange(flow, start, before('/processes/prepare/steps#end'))).toBeNull();
  expect(simulationRange(flow, start, ['steps', 3])).toBeNull();
  expect(simulationRange(flow, before('/processes/prepare/steps/1'), before('/processes/prepare/steps#end'))?.steps.map(n => n.label)).toEqual(['xslt']);
});

it('selects the whole uncalled process from its entry connection', () => {
  const first = edges.find(edge => edge.source === '/processes/prepare/steps' && edge.target === '/processes/prepare/steps/0');
  expect(simulationRange(flow, first?.insertAt, before('/processes/prepare/steps#end'))?.steps.map(node => node.label)).toEqual(['jq', 'xslt']);
});
