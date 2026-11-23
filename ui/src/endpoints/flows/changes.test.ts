import { describe, expect, it } from 'vitest';
import { flowChanges } from './changes';

const flow = (steps: string, extra = '') => `schemaVersion: 1
kind: flow
id: orders
version: 1.0.0
from:
  endpoint: orders-in
delivery:
  mode: sync
steps:
${steps}${extra}`;

const step = (type: string, rest = '') => `  - type: ${type}\n${rest}`;
const named = (type: string, name: string, rest = '') => `  - type: ${type}\n    name: ${name}\n${rest}`;

describe('flowChanges', () => {
  it('reports a step added, one removed with the step it followed, one moved and one renamed', () => {
    const before = flow(named('validate', 'check') + named('jq', 'normalize') + named('xslt', 'shape') + named('to', 'send', '    endpoint: out\n'));
    const after = flow(named('jq', 'normalize') + named('validate', 'check') + named('xslt', 'reshape') + named('filter', 'only-big', '    when: /a\n') + named('to', 'send', '    endpoint: out\n'));

    const changes = flowChanges(before, after);

    expect(changes.status).toBe('modified');
    expect(changes.steps).toEqual([
      { status: 'moved', list: 'steps', name: 'check', path: ['steps', 1], settings: [] },
      { status: 'added', list: 'steps', name: 'reshape', path: ['steps', 2], settings: [] },
      { status: 'added', list: 'steps', name: 'only-big', path: ['steps', 3], settings: [] },
      { status: 'removed', list: 'steps', name: 'shape', path: ['steps', 2], follows: 'normalize', settings: [] },
    ]);
    expect(changes.repeated).toEqual([]);
    expect(changes.settings).toEqual([]);
  });

  it('names a step by its type, or by the process a call calls, when it has no name', () => {
    const before = flow(step('validate') + step('process', '    process: enrich\n'), '\nprocesses:\n  enrich:\n    steps:\n' + step('jq').replace(/^ {2}/gm, '      '));
    const after = flow(step('process', '    process: enrich\n') + step('validate'), '\nprocesses:\n  enrich:\n    steps:\n' + step('jq').replace(/^ {2}/gm, '      '));

    expect(flowChanges(before, after).steps).toEqual([{ status: 'moved', list: 'steps', name: 'validate', path: ['steps', 1], settings: [] }]);
  });

  it('matches the steps that share a name in one list in their order, and says so', () => {
    const before = flow(step('jq', '    filter: .a\n') + step('jq', '    filter: .b\n'));
    const after = flow(step('jq', '    filter: .a\n') + step('jq', '    filter: .c\n') + step('jq', '    filter: .d\n'));

    const changes = flowChanges(before, after);

    expect(changes.repeated).toEqual([{ list: 'steps', names: ['jq'] }]);
    expect(changes.steps).toEqual([
      { status: 'changed', list: 'steps', name: 'jq', path: ['steps', 1], settings: [{ key: 'filter', before: '".b"', after: '".c"' }] },
      { status: 'added', list: 'steps', name: 'jq', path: ['steps', 2], settings: [] },
    ]);
  });

  it('lists every changed setting of a matched step leaf by leaf, secret names as written and long values cut', () => {
    const long = 'x'.repeat(300);
    const before = flow(named('route', 'pick', '    prop: region\n    routes:\n      - when: /a\n        set: eu\n    otherwise: us\n') + named('http', 'call', '    password: secret://old\n'));
    const after = flow(named('route', 'pick', `    prop: region\n    routes:\n      - when: /b\n        set: eu\n      - when: /c\n        set: asia\n    retryable: true\n`) + named('http', 'call', `    password: secret://new\n    note: ${long}\n`));

    const changes = flowChanges(before, after);

    expect(changes.steps).toEqual([
      {
        status: 'changed',
        list: 'steps',
        name: 'pick',
        path: ['steps', 0],
        settings: [
          { key: 'routes[0].when', before: '"/a"', after: '"/b"' },
          { key: 'routes[1].when', before: 'none', after: '"/c"' },
          { key: 'routes[1].set', before: 'none', after: '"asia"' },
          { key: 'otherwise', before: '"us"', after: 'none' },
          { key: 'retryable', before: 'none', after: 'true' },
        ],
      },
      {
        status: 'changed',
        list: 'steps',
        name: 'call',
        path: ['steps', 1],
        settings: [
          { key: 'password', before: '"secret://old"', after: '"secret://new"' },
          { key: 'note', before: 'none', after: `"${'x'.repeat(198)}…` },
        ],
      },
    ]);
    expect(changes.steps[1]!.settings[1]!.after).toHaveLength(200);
  });

  it("lists the flow's own settings leaf by leaf", () => {
    const before = flow(step('jq'), '\nrecording:\n  level: all\nlimits:\n  maxBodyBytes: 1024\n');
    const after = flow(step('jq'), '\nrecording:\n  level: errors\n  payload: metadata-only\npartitionBy: customer\n').replace('mode: sync', 'mode: at-least-once');

    expect(flowChanges(before, after).settings).toEqual([
      { key: 'delivery.mode', before: '"sync"', after: '"at-least-once"' },
      { key: 'recording.level', before: '"all"', after: '"errors"' },
      { key: 'recording.payload', before: 'none', after: '"metadata-only"' },
      { key: 'limits.maxBodyBytes', before: '1024', after: 'none' },
      { key: 'partitionBy', before: 'none', after: '"customer"' },
    ]);
  });

  it('reads a change of comments, quoting or key order only as formatting', () => {
    const before = flow(named('jq', 'normalize', '    filter: .a\n'));
    const after = `# the orders flow\nkind: "flow"\nschemaVersion: 1\nid: 'orders'\nversion: 1.0.0\ndelivery: { mode: sync }\nfrom: { endpoint: orders-in }\nsteps:\n  - name: normalize  # first\n    type: jq\n    filter: ".a"\n`;

    expect(flowChanges(before, after)).toMatchObject({ status: 'formatting', steps: [], settings: [] });
    expect(flowChanges(before, before).status).toBe('same');
  });

  it('reports an endpoint renamed since the older side as the settings that name it', () => {
    const before = flow(named('to', 'send', '    endpoint: partner\n'));
    const after = flow(named('to', 'send', '    endpoint: partner-api\n')).replace('endpoint: orders-in', 'endpoint: orders-inbound');

    const changes = flowChanges(before, after);

    expect(changes.settings).toEqual([{ key: 'from.endpoint', before: '"orders-in"', after: '"orders-inbound"' }]);
    expect(changes.steps).toEqual([{ status: 'changed', list: 'steps', name: 'send', path: ['steps', 0], settings: [{ key: 'endpoint', before: '"partner"', after: '"partner-api"' }] }]);
  });

  it('matches processes by name, and the steps of each one in its own list', () => {
    const processes = (body: string) => `\nprocesses:\n${body}`;
    const proc = (name: string, steps: string) => `  ${name}:\n    steps:\n${steps.replace(/^ {2}/gm, '      ')}`;
    const before = flow(step('process', '    process: enrich\n'), processes(proc('enrich', named('jq', 'a') + named('jq', 'b')) + proc('old', step('jq'))));
    const after = flow(step('process', '    process: enrich\n'), processes(proc('enrich', named('jq', 'b') + named('jq', 'a')) + proc('fresh', step('xslt'))));

    const changes = flowChanges(before, after);

    expect(changes.processes).toEqual([
      { name: 'fresh', status: 'added' },
      { name: 'old', status: 'removed' },
    ]);
    expect(changes.steps).toEqual([
      { status: 'moved', list: 'processes.enrich.steps', name: 'a', path: ['processes', 'enrich', 'steps', 1], settings: [] },
      { status: 'added', list: 'processes.fresh.steps', name: 'xslt', path: ['processes', 'fresh', 'steps', 0], settings: [] },
      { status: 'removed', list: 'processes.old.steps', name: 'jq', path: ['processes', 'old', 'steps', 0], follows: null, settings: [] },
    ]);
  });

  it('reads every step of an added flow as added, and of a deleted one as removed', () => {
    const text = flow(named('jq', 'a') + named('to', 'b', '    endpoint: out\n'));

    expect(flowChanges(null, text)).toMatchObject({
      status: 'added',
      settings: [],
      steps: [
        { status: 'added', name: 'a', path: ['steps', 0] },
        { status: 'added', name: 'b', path: ['steps', 1] },
      ],
    });
    expect(flowChanges(text, null)).toMatchObject({
      status: 'deleted',
      settings: [],
      steps: [
        { status: 'removed', name: 'a', path: ['steps', 0], follows: null },
        { status: 'removed', name: 'b', path: ['steps', 1], follows: 'a' },
      ],
    });
  });

  it('says which version cannot be read, and compares nothing', () => {
    const good = flow(named('jq', 'a'));

    expect(flowChanges(good, 'kind: flow\nid: orders\nsteps: [ unclosed\n')).toMatchObject({
      status: 'unreadable',
      unreadable: [{ side: 'after', message: expect.stringMatching(/^the file is not YAML/) }],
      steps: [],
    });
    expect(flowChanges('kind: flow\nsteps: []\n', good)).toMatchObject({
      status: 'unreadable',
      unreadable: [{ side: 'before', message: 'a flow file names its id' }],
    });
  });
});
