import { describe, expect, test } from 'vitest';
import { artifactKinds, type ArtifactKind } from './artifacts';

const kindOf = (kind: ArtifactKind['kind']): ArtifactKind => ({ kind, open: (path) => ({ id: path, title: path, render: () => null }), Change: () => null });

describe('artifactKinds', () => {
  test('collects the kinds every module registers, and passes over modules that register none', () => {
    const flow = kindOf('flow');
    const endpoint = kindOf('endpoint');
    const mapping = kindOf('mapping');
    const kinds = artifactKinds({
      './endpoints/module.tsx': { default: { screens: [], artifacts: [flow, endpoint] } },
      './mapping/module.tsx': { default: { screens: [], artifacts: [mapping] } },
      './messages/module.tsx': { default: { screens: [] } },
    });

    expect([...kinds.keys()].sort()).toEqual(['endpoint', 'flow', 'mapping']);
    expect(kinds.get('flow')).toBe(flow);
    expect(kinds.get('mapping')).toBe(mapping);
  });

  test('refuses a kind that two modules register, naming both', () => {
    expect(() =>
      artifactKinds({
        './a/module.tsx': { default: { screens: [], artifacts: [kindOf('flow')] } },
        './b/module.tsx': { default: { screens: [], artifacts: [kindOf('flow')] } },
      }),
    ).toThrow('the artifact kind "flow" is registered twice: by ./a/module.tsx and by ./b/module.tsx');
  });

  test('refuses artifacts that are no list of kinds', () => {
    expect(() => artifactKinds({ './a/module.tsx': { default: { screens: [], artifacts: [{ kind: 'flow' }] } } })).toThrow(
      './a/module.tsx registers artifacts that are no list of { kind, open, Change }',
    );
    expect(() => artifactKinds({ './a/module.tsx': { default: { screens: [], artifacts: kindOf('flow') } } })).toThrow('registers artifacts that are no list');
  });
});
