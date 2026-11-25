import { describe, expect, test } from 'vitest';
import type { Artifact } from './api';
import { filtered, foldersOf, treeOf, type TreeNode } from './tree';

const DEMO: Artifact[] = [
  { path: 'demo/endpoints/http-in.yaml', kind: 'endpoint', id: 'http-in' },
  { path: 'demo/flows/invoice.yaml', kind: 'flow', id: 'invoice' },
  { path: 'demo/greeting/folder.yaml', kind: 'folder', namespace: 'urn:rail8:demo:greeting' },
  { path: 'demo/greeting/greet.test.yaml', kind: 'test' },
  { path: 'demo/greeting/greet.yaml', kind: 'flow', id: 'greet' },
  { path: 'demo/greeting/mappings/greeting.mapping.json', kind: 'mapping', id: 'greeting' },
  { path: 'demo/greeting/structures/greeting/person.schema.json', kind: 'file', usedBy: ['demo/greeting/mappings/greeting.mapping.json'] },
  { path: 'demo/package.yaml', kind: 'package' },
];

/** The tree as lines: each node indented by its depth, a folder with a slash, its namespace and its problems' codes. */
const lines = (nodes: readonly TreeNode[], depth = 0): string[] =>
  nodes.flatMap((node) =>
    node.type === 'folder'
      ? [
          `${'  '.repeat(depth)}${node.name}/${node.settings?.namespace === undefined ? '' : ` ${node.settings.namespace}`}${node.problems.map((p) => ` !${p.code}`).join('')}`,
          ...lines(node.children, depth + 1),
        ]
      : [`${'  '.repeat(depth)}${node.name}`],
  );

describe('the tree of a package', () => {
  test('nests the folders, folders first and each part by name, with each folder.yaml as its folder’s settings', () => {
    expect(lines(treeOf('demo', DEMO, true))).toEqual([
      'endpoints/',
      '  http-in',
      'flows/',
      '  invoice',
      'greeting/ urn:rail8:demo:greeting',
      '  mappings/',
      '    greeting',
      '  structures/',
      '    greeting/',
      '      person.schema.json',
      '  greet',
      '  greet.test.yaml',
      'package.yaml',
    ]);
  });

  test('marks a name that is no folder’s name, the first folder over four deep, and a namespace two folders share, in a package only', () => {
    const artifacts: Artifact[] = [
      { path: 'p/Billing/a.yaml', kind: 'flow', id: 'a' },
      { path: 'p/a/b/c/d/e/f/deep.yaml', kind: 'flow', id: 'deep' },
      { path: 'p/one/folder.yaml', kind: 'folder', namespace: 'urn:x' },
      { path: 'p/two/folder.yaml', kind: 'folder', namespace: 'urn:x' },
    ];
    expect(lines(treeOf('p', artifacts, true))).toEqual([
      'a/',
      '  b/',
      '    c/',
      '      d/',
      '        e/ !folder-too-deep',
      '          f/',
      '            deep',
      'Billing/ !folder-name',
      '  a',
      'one/ urn:x !folder-namespace-taken',
      'two/ urn:x !folder-namespace-taken',
    ]);
    expect(foldersOf(treeOf('p', artifacts, false)).flatMap((f) => f.problems)).toEqual([]);
  });

  test('filters by kind and by text in names, paths and namespaces, keeping the folders that hold what shows', () => {
    const tree = treeOf('demo', DEMO, true);
    expect(lines(filtered(tree, 'flow', ''))).toEqual(['flows/', '  invoice', 'greeting/ urn:rail8:demo:greeting', '  greet']);
    expect(lines(filtered(tree, 'all', 'GREET'))).toEqual(['greeting/ urn:rail8:demo:greeting', '  mappings/', '    greeting', '  structures/', '    greeting/', '      person.schema.json', '  greet', '  greet.test.yaml']);
    expect(lines(filtered(tree, 'all', 'rail8:demo'))).toEqual(lines(filtered(tree, 'all', 'greeting/')));
    expect(lines(filtered(tree, 'file', ''))).toEqual(['greeting/ urn:rail8:demo:greeting', '  structures/', '    greeting/', '      person.schema.json', 'package.yaml']);
    expect(filtered(tree, 'endpoint', 'greet')).toEqual([]);
    expect(filtered(tree, 'all', '  ')).toEqual(tree);
  });
});
