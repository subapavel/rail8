import { AUDIT_PAGE_MAX, type AuditApi, type AuditEntry, type AuditQuery } from './api';

/** The most rows an export holds. */
export const EXPORT_MAX = 100_000;

/** What an export read: every row the query keeps, or more than the export holds. */
export type Exported = { kind: 'rows'; rows: AuditEntry[] } | { kind: 'too-many'; max: number };

/**
 * Every row `query` keeps, newest first, read a page of the node's largest size after another; `onRead` hears how many rows
 * are read so far. Stops with `too-many` as soon as the list is known to be longer than `max` rows.
 */
export async function readAll(api: AuditApi, query: AuditQuery, onRead: (count: number) => void = () => {}, max = EXPORT_MAX): Promise<Exported> {
  const rows: AuditEntry[] = [];
  let after: string | undefined;
  for (;;) {
    const page = await api.read(query, after, AUDIT_PAGE_MAX);
    rows.push(...page.items);
    onRead(rows.length);
    if (rows.length > max || (rows.length === max && page.next !== null)) return { kind: 'too-many', max };
    if (page.next === null) return { kind: 'rows', rows };
    after = page.next;
  }
}

/** The columns of the file, in its order: every field of a row, `detail` as JSON. */
export const CSV_COLUMNS = ['id', 'at', 'action', 'subject', 'actor', 'via', 'credential', 'detail'] as const;

/**
 * A cell of the file: in quotes when it holds a comma, a quote or a line break, its quotes doubled. A text that starts with
 * `=`, `+`, `-`, `@`, a tab or a carriage return gets a `'` before it, so a spreadsheet shows it rather than runs it as a
 * formula.
 */
function cell(value: string): string {
  const safe = /^[=+\-@\t\r]/.test(value) ? `'${value}` : value;
  return /[",\r\n]/.test(safe) ? `"${safe.replaceAll('"', '""')}"` : safe;
}

/**
 * `rows` as a CSV file: a byte order mark, so spreadsheets read it as UTF-8, the header of {@link CSV_COLUMNS}, then a line
 * per row, each line ended by CRLF. A row with no credential has an empty cell.
 */
export function auditCsv(rows: readonly AuditEntry[]): string {
  const lines = [CSV_COLUMNS.join(',')];
  for (const r of rows) {
    lines.push([String(r.id), r.at, r.action, r.subject, r.actor, r.via, r.credential ?? '', JSON.stringify(r.detail)].map(cell).join(','));
  }
  return `\uFEFF${lines.join('\r\n')}\r\n`;
}
