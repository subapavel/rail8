import { describe, expect, test, vi } from 'vitest';
import { chooseLanguage } from '../../language';
import { ApiProblem, describeError, isSaveConflict } from './client';

describe('a save conflict', () => {
  test('is a 409 mapping-changed problem, and nothing else', () => {
    const problem = (type: string, status: number) => new ApiProblem({ type, title: 't', status, detail: '' });

    expect(isSaveConflict(problem('urn:rail8:mapping:problem:mapping-changed', 409))).toBe(true);
    expect(isSaveConflict(problem('urn:rail8:mapping:problem:mapping-exists', 409))).toBe(false);
    expect(isSaveConflict(new Error('urn:rail8:mapping:problem:mapping-changed'))).toBe(false);
  });
});

describe('describing a failed call', () => {
  test('a refused model shows its title, detail and diagnostics', () => {
    const diagnostic = {
      severity: 'error' as const,
      code: 'box-cycle',
      message: 'a cycle',
      location: { kind: 'authoring-id' as const, authoringId: 't1' },
    };
    const error = new ApiProblem({ type: 'urn:x', title: 'Model refused', status: 422, detail: 'stage 2', diagnostics: [diagnostic] });

    expect(describeError(error)).toEqual({ title: 'Model refused', detail: 'stage 2', diagnostics: [diagnostic] });
  });

  test('a problem without diagnostics shows none', () => {
    const error = new ApiProblem({ type: 'urn:x', title: 'Not found', status: 404, detail: 'no mapping m' });

    expect(describeError(error)).toEqual({ title: 'Not found', detail: 'no mapping m', diagnostics: [] });
  });

  test("a problem whose code the UI knows shows in the UI's language, and one of an unknown code in its English", () => {
    const exists = new ApiProblem({ type: 'urn:rail8:mapping:problem:mapping-exists', code: 'mapping-exists', values: {}, title: 'Mapping exists', status: 409, detail: 'a mapping with this id exists' });
    const unknown = new ApiProblem({ type: 'urn:rail8:mapping:problem:not-a-code-of-the-ui', code: 'not-a-code-of-the-ui', values: {}, title: 'Refused', status: 409, detail: 'the studio says no' });
    vi.stubGlobal('document', { documentElement: {} });
    chooseLanguage('cs');
    try {
      expect(describeError(exists)).toEqual({ title: 'Mapování s tímto id už existuje.', detail: '', diagnostics: [] });
      expect(describeError(unknown)).toEqual({ title: 'Refused', detail: 'the studio says no', diagnostics: [] });
    } finally {
      chooseLanguage('en');
      vi.unstubAllGlobals();
    }
  });

  test('an error that is no problem, such as a lost connection, shows its message', () => {
    expect(describeError(new TypeError('Failed to fetch'))).toEqual({ title: 'Failed to fetch', detail: '', diagnostics: [] });
    expect(describeError('offline')).toEqual({ title: 'offline', detail: '', diagnostics: [] });
  });
});
