import { describe, expect, test } from 'vitest';
import { endpointChanges, HIDDEN } from './endpointChanges';
import type { SettingsDescription } from './settings';

const endpoint = (config: string, rest = '') => `schemaVersion: 1
kind: endpoint
id: partner
scheme: http
direction: out
config:
${config}${rest}`;

const HTTP: SettingsDescription = {
  format: 1,
  described: true,
  tabs: [
    { id: 'general', label: 'General', common: false },
    { id: 'auth', label: 'Authentication', common: false },
  ],
  fields: [
    { name: 'url', type: 'text', help: 'Where it sends.', tab: 'general', required: true, directions: ['out'] },
    { name: 'headers', type: 'map', help: 'Headers.', tab: 'general', required: false, directions: ['out'] },
    { name: 'password', type: 'secret', help: 'The password.', tab: 'auth', required: false, directions: ['out'] },
  ],
  rules: [],
};

const described = new Map([['http', HTTP]]);
const none = new Map<string, SettingsDescription>();

describe('endpointChanges', () => {
  test('lists a changed setting under its tab and field, and a key the description lacks under its key path', () => {
    const before = endpoint('  url: http://a\n  headers:\n    accept: text/xml\n  retries: 1\n');
    const after = endpoint('  url: http://b\n  headers:\n    accept: application/json\n  retries: 2\n');

    expect(endpointChanges(before, after, described)).toEqual({
      status: 'modified',
      unreadable: [],
      envelope: [],
      config: [
        { key: 'url', title: 'General › url', before: '"http://a"', after: '"http://b"' },
        { key: 'headers.accept', title: 'General › headers.accept', before: '"text/xml"', after: '"application/json"' },
        { key: 'retries', title: 'retries', before: '1', after: '2' },
      ],
      environments: [],
    });
  });

  test('lists every setting under its key path when the node gives no description', () => {
    const changes = endpointChanges(endpoint('  url: http://a\n'), endpoint('  url: http://b\n'), none);

    expect(changes.config).toEqual([{ key: 'url', title: 'url', before: '"http://a"', after: '"http://b"' }]);
  });

  test('puts the scheme and the direction first, then the rest of the envelope', () => {
    const before = endpoint('  url: http://a\n');
    const after = before.replace('scheme: http', 'scheme: ftp').replace('direction: out', 'direction: in').replace('id: partner', 'id: supplier');

    expect(endpointChanges(before, after, described).envelope).toEqual([
      { key: 'scheme', before: '"http"', after: '"ftp"' },
      { key: 'direction', before: '"out"', after: '"in"' },
      { key: 'id', before: '"partner"', after: '"supplier"' },
    ]);
  });

  test('lists each environment whose overlay changed, was added or was removed, after the settings', () => {
    const before = endpoint('  url: http://a\n', '\nenvironments:\n  test:\n    config:\n      url: http://test\n  prod:\n    config:\n      url: http://prod\n  qa:\n    config:\n      url: http://qa\n');
    const after = endpoint('  url: http://a\n', '\nenvironments:\n  test:\n    config:\n      url: http://test\n  prod:\n    config:\n      url: http://prod2\n  staging:\n    config:\n      url: http://staging\n');

    expect(endpointChanges(before, after, described).environments).toEqual([
      { name: 'prod', status: 'changed', config: [{ key: 'url', title: 'General › url', before: '"http://prod"', after: '"http://prod2"' }] },
      { name: 'staging', status: 'added', config: [{ key: 'url', title: 'General › url', before: 'none', after: '"http://staging"' }] },
      { name: 'qa', status: 'removed', config: [{ key: 'url', title: 'General › url', before: '"http://qa"', after: 'none' }] },
    ]);
  });

  test('hides the value of a secret field that is no secret name, and shows a secret name as written', () => {
    const changes = endpointChanges(endpoint('  password: hunter2\n'), endpoint('  password: secret://partner.password\n'), described);

    expect(changes.config).toEqual([{ key: 'password', title: 'Authentication › password', before: HIDDEN, after: '"secret://partner.password"' }]);
  });

  test('says when only comments or formatting changed', () => {
    const before = endpoint('  url: http://a\n  retries: 1\n');
    const after = endpoint('  retries: 1   # once\n  url: "http://a"\n');

    expect(endpointChanges(before, after, described).status).toBe('formatting');
    expect(endpointChanges(before, before, described).status).toBe('same');
  });

  test('lists every setting of an added endpoint and of a deleted one against none', () => {
    const text = endpoint('  url: http://a\n');

    const added = endpointChanges(null, text, described);
    expect(added.status).toBe('added');
    expect(added.envelope.map((c) => c.key)).toEqual(['scheme', 'direction', 'schemaVersion', 'kind', 'id']);
    expect(added.config).toEqual([{ key: 'url', title: 'General › url', before: 'none', after: '"http://a"' }]);

    const deleted = endpointChanges(text, null, described);
    expect(deleted.status).toBe('deleted');
    expect(deleted.config).toEqual([{ key: 'url', title: 'General › url', before: '"http://a"', after: 'none' }]);
  });

  test('says which version is no endpoint file the form reads, and why', () => {
    expect(endpointChanges('kind: endpoint\nid: x\n', endpoint('  url: http://a\n'), described)).toMatchObject({
      status: 'unreadable',
      unreadable: [{ side: 'before', message: 'an endpoint file names its id and scheme' }],
    });
    expect(endpointChanges(endpoint('  url: http://a\n'), 'kind: flow\n', described).unreadable).toEqual([{ side: 'after', message: 'the file is not an endpoint' }]);
  });
});
