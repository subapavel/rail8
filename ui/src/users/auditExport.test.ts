import { describe, expect, it, vi } from 'vitest';
import type { AuditApi, AuditEntry, AuditQuery } from './api';
import { auditCsv, readAll } from './auditExport';

const entry = (id: number, rest: Partial<AuditEntry> = {}): AuditEntry => ({
  id,
  at: `2026-11-24T10:00:${String(id % 60).padStart(2, '0')}Z`,
  action: 'login',
  subject: 'user/anna',
  actor: 'anna',
  via: 'session',
  credential: '0123456789abcdef',
  detail: { remote: '10.0.0.7' },
  ...rest,
});

/** A node holding `count` rows, newest first, that hands out at most `size` of them a page. */
function node(count: number, size: number) {
  const rows = Array.from({ length: count }, (_, i) => entry(count - i));
  const read = vi.fn(async (_query: AuditQuery, after?: string, limit?: number) => {
    const from = after === undefined ? 0 : Number(after);
    const to = from + Math.min(size, limit ?? 50);
    return { items: rows.slice(from, to), next: to < rows.length ? String(to) : null };
  });
  return { api: { read } satisfies AuditApi, read, rows };
}

describe('readAll', () => {
  it('reads every page of the query at the largest size, and tells how many rows it has read', async () => {
    const { api, read, rows } = node(7, 3);
    const counts: number[] = [];
    const query = { user: 'anna', from: '2026-11-24T00:00:00Z' };
    expect(await readAll(api, query, (n) => counts.push(n))).toEqual({ kind: 'rows', rows });
    expect(read.mock.calls).toEqual([
      [query, undefined, 500],
      [query, '3', 500],
      [query, '6', 500],
    ]);
    expect(counts).toEqual([3, 6, 7]);
  });

  it('stops once the list is longer than the export holds', async () => {
    const { api, read } = node(10, 3);
    expect(await readAll(api, {}, undefined, 6)).toEqual({ kind: 'too-many', max: 6 });
    expect(read).toHaveBeenCalledTimes(2);
    expect(await readAll(node(10, 4).api, {}, undefined, 6)).toEqual({ kind: 'too-many', max: 6 });
    const exactly = node(6, 3);
    expect(await readAll(exactly.api, {}, undefined, 6)).toEqual({ kind: 'rows', rows: exactly.rows });
  });
});

describe('auditCsv', () => {
  it('writes a byte order mark, the header and every column of each row, each line ended by CRLF', () => {
    const csv = auditCsv([
      entry(2, { action: 'message.body.view', subject: 'message/m-1', detail: { step: 2, remote: '10.0.0.7' } }),
      entry(1, { actor: 'emergency-key', via: 'emergency-key', credential: null, detail: {} }),
    ]);
    expect(csv).toBe(
      '﻿id,at,action,subject,actor,via,credential,detail\r\n' +
        '2,2026-11-24T10:00:02Z,message.body.view,message/m-1,anna,session,0123456789abcdef,"{""step"":2,""remote"":""10.0.0.7""}"\r\n' +
        '1,2026-11-24T10:00:01Z,login,user/anna,emergency-key,emergency-key,,{}\r\n',
    );
  });

  it('quotes a cell with a comma, a quote or a line break, and keeps a spreadsheet from running a formula', () => {
    const [, line] = auditCsv([entry(3, { actor: '=HYPERLINK("x")', subject: 'a,b', detail: { reason: 'two\nlines' } })]).split('\r\n');
    expect(line).toBe('3,2026-11-24T10:00:03Z,login,"a,b","\'=HYPERLINK(""x"")",session,0123456789abcdef,"{""reason"":""two\\nlines""}"');
    expect(auditCsv([entry(4, { actor: '-1', subject: '@x' })]).split('\r\n')[1]).toBe(
      "4,2026-11-24T10:00:04Z,login,'@x,'-1,session,0123456789abcdef,\"{\"\"remote\"\":\"\"10.0.0.7\"\"}\"",
    );
  });
});
