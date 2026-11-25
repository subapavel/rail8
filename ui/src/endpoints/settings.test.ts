import { readFileSync } from 'node:fs';
import { describe, expect, test } from 'vitest';
import { checkForm, checkOptions, endpointSettings, readForm, shownFields, type ComponentForms, type DescribedForm, type Options, type Problem } from './settings';
import { DIRECT_FORMS, HTTP_FORMS, SFTP_FORMS } from './fake/forms';
import { say, textsIn } from '../language';

const en = textsIn('en');

/** The cases the platform's check runs too, so that the form and the deploy judge alike. */
const shared = JSON.parse(readFileSync(new URL('../../../platform/api/src/test/resources/dev/rail8/api/settings/check-cases.json', import.meta.url), 'utf8')) as {
  description: DescribedForm;
  cases: { name: string; direction: 'in' | 'out'; options: Options; problems: Problem[] }[];
};
const form = readForm(shared.description);

describe('checkForm', () => {
  test.each(shared.cases)('$name', ({ direction, options, problems }) => {
    expect(checkForm(en, form, options, direction)).toEqual(problems);
  });

  test('a form that describes nothing is not judged here', () => {
    expect(checkForm(en, { format: 2, described: false, tabs: [], fields: [], rules: [] }, { anything: 1 }, 'in')).toEqual([]);
  });

  test('a field of a type the form does not know makes no claim on its value', () => {
    const headers = readForm({ described: true, tabs: [], fields: [{ name: 'headers', type: 'map', label: 'Headers', help: 'Headers.', tab: 'general', required: true, directions: ['out'] }], rules: [] });
    expect(checkForm(en, headers, { headers: [1, 2] }, 'out')).toEqual([]);
    expect(checkForm(en, headers, {}, 'out')).toEqual([{ path: '/headers', code: 'required', message: 'is required' }]);
  });
});

describe('readForm', () => {
  test("keeps each choice by its value, with its label beside it, and each field's label", () => {
    const afterSuccess = form.fields.find((f) => f.name === 'afterSuccess')!;
    expect(afterSuccess.choices).toEqual(['delete', 'move']);
    expect(afterSuccess.choiceLabels).toEqual({ delete: 'Delete', move: 'Move' });
    expect(form.fields.find((f) => f.name === 'privateKey')?.label).toBe('Private key');
  });
});

describe('endpointSettings', () => {
  test("puts the connection's fields first, then the reply's limit of an out endpoint that reaches a connection, then the flow's", () => {
    const out = endpointSettings(HTTP_FORMS, 'out');
    expect(out.fields.map((f) => [f.name, f.group])).toEqual([
      ['url', 'connection'],
      ['timeoutMs', 'connection'],
      ['poolSize', 'connection'],
      ['maxBodyBytes', 'connection'],
      ['address', 'flow'],
      ['path', 'flow'],
    ]);
    const reply = out.fields.find((f) => f.name === 'maxBodyBytes')!;
    expect([reply.required, reply.min, say(en, reply.label!), say(en, reply.help)]).toEqual([undefined, 1, 'Largest reply', 'The largest reply body taken, in bytes.']);
    expect(endpointSettings(HTTP_FORMS, 'in').fields.map((f) => f.name)).not.toContain('maxBodyBytes');
    expect(endpointSettings(DIRECT_FORMS, 'out').fields.map((f) => f.name)).toEqual(['address']);
  });

  test("drops the patterns of the connection's fields and keeps those of the flow's", () => {
    const fields = endpointSettings(HTTP_FORMS, 'out').fields;
    expect(fields.find((f) => f.name === 'url')?.pattern).toBeUndefined();
    expect(fields.find((f) => f.name === 'path')?.pattern).toBe('/.*');
  });
});

describe('checkOptions', () => {
  const check = (forms: ComponentForms, options: Options, direction: 'in' | 'out') => checkOptions(en, endpointSettings(forms, direction), options, direction);

  test("takes a URL with a user name on an out endpoint, and still holds a flow field to its pattern", () => {
    expect(check(HTTP_FORMS, { url: 'https://anna:pw@partner.example/api', maxBodyBytes: 1024 }, 'out')).toEqual([]);
    expect(check(HTTP_FORMS, { url: 'https://partner.example', maxBodyBytes: 1024, path: 'orders' }, 'out')).toEqual([{ path: '/path', code: 'pattern', message: 'must match /.*' }]);
    expect(check(DIRECT_FORMS, { address: 'orders' }, 'in')).toEqual([expect.objectContaining({ path: '/address', code: 'pattern' })]);
  });

  test("judges the connection's form, then the flow's, then the reply's limit, then the keys no form takes", () => {
    expect(check(HTTP_FORMS, { colour: 'red', path: 'x', address: '/in', maxBodyBytes: 0 }, 'out')).toEqual([
      { path: '/url', code: 'required', message: 'is required' },
      { path: '/address', code: 'direction', message: 'a setting of in endpoints only' },
      { path: '/path', code: 'pattern', message: 'must match /.*' },
      { path: '/maxBodyBytes', code: 'min', message: 'must be a whole number of at least 1' },
      { path: '/colour', code: 'unknown', message: 'no such setting' },
    ]);
    expect(check(HTTP_FORMS, { url: 'https://partner.example' }, 'out')).toEqual([]);
  });

  test("leaves the keys the node sets on an in endpoint alone, and refuses the reply's limit of an out endpoint that reaches none", () => {
    expect(check(HTTP_FORMS, { address: '/orders', flow: 'orders', maxBodyBytes: 5 }, 'in')).toEqual([]);
    expect(check(DIRECT_FORMS, { address: '/orders', maxBodyBytes: 5 }, 'out')).toEqual([{ path: '/maxBodyBytes', code: 'unknown', message: 'no such setting' }]);
  });

  test('a rule over several fields is judged with the form its first field is in', () => {
    const base = { host: 'sftp', user: 'rail8', hostKey: 'secret://partner.host-key' };
    expect(check(SFTP_FORMS, base, 'in')).toEqual([{ path: '', code: 'exactlyOne', message: 'set exactly one of password, privateKey' }]);
    expect(check(SFTP_FORMS, { ...base, password: 'secret://partner.password' }, 'in')).toEqual([]);
  });

  test('an endpoint of a direction the component does not serve has that one problem', () => {
    expect(check(SFTP_FORMS, { host: 'sftp' }, 'out')).toEqual([{ path: '', code: 'direction', message: 'the adapter does not serve the out direction' }]);
  });

  test('a form that describes nothing takes what the other form does not describe', () => {
    const none = { ...HTTP_FORMS, described: { connection: false, flow: true }, connection: [] };
    expect(check(none, { anything: 1, path: '/a' }, 'out')).toEqual([]);
    expect(check(none, { anything: 1, address: '/in' }, 'in')).toEqual([{ path: '/anything', code: 'unknown', message: 'no such setting' }]);
    expect(check({ ...HTTP_FORMS, described: { connection: true, flow: false }, flow: [] }, { url: 'https://partner.example', maxBodyBytes: 1, anything: 1 }, 'out')).toEqual([]);
  });

  test('a component that describes neither form is not judged here, and has no reply limit to give', () => {
    const mock: ComponentForms = { format: 2, reach: { in: 'connection', out: 'connection' }, described: { connection: false, flow: false }, tabs: [], connection: [], flow: [], rules: [] };
    expect(endpointSettings(mock, 'out').described).toBe(false);
    expect(check(mock, { anything: 1 }, 'out')).toEqual([]);
  });
});

describe('shownFields', () => {
  test('a field is shown while the field it names has one of its values, set or by default', () => {
    const shown = (options: Options) => [...shownFields(form, options)].filter((n) => ['moveTo', 'archive', 'archiveDir'].includes(n));
    expect(shown({})).toEqual([]);
    expect(shown({ afterSuccess: 'move' })).toEqual(['moveTo', 'archive']);
    expect(shown({ afterSuccess: 'move', archive: true })).toEqual(['moveTo', 'archive', 'archiveDir']);
    expect(shown({ archive: true })).toEqual([]);
  });
});
