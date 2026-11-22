import { describe, expect, it } from 'vitest';
import { uiPort } from './uiPort';

describe('the UI port', () => {
  it('defaults to 5173 and accepts another local port', () => {
    expect(uiPort(undefined)).toBe(5173);
    expect(uiPort('5175')).toBe(5175);
  });

  it.each(['', '0', '65536', '51.75', 'invalid'])('rejects %j', (value) => {
    expect(() => uiPort(value)).toThrow('RAIL8_UI_PORT');
  });
});
