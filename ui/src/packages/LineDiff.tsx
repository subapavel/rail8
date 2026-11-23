import { Typography } from 'antd';
import { diffLines } from 'diff';
import { useMemo } from 'react';

/** A line diff compares at most this many lines a side. */
export const MAX_DIFF_LINES = 5000;

/** Unchanged lines kept around each change; a longer run of unchanged lines between them is folded. */
const CONTEXT = 3;

type Line = { mark: '+' | '-' | ' '; text: string } | { folded: number };

const linesOf = (text: string) => (text === '' ? [] : text.replace(/\n$/, '').split('\n'));

/** The lines of the diff from `before` to `after`, each marked, with long runs of unchanged lines folded. */
export function diffOf(before: string, after: string): Line[] {
  const marked: { mark: '+' | '-' | ' '; text: string }[] = [];
  for (const part of diffLines(before, after)) {
    const mark = part.added ? '+' : part.removed ? '-' : ' ';
    for (const text of linesOf(part.value)) marked.push({ mark, text });
  }
  const near = marked.map(() => false);
  marked.forEach((line, n) => {
    if (line.mark === ' ') return;
    for (let i = Math.max(0, n - CONTEXT); i <= Math.min(marked.length - 1, n + CONTEXT); i++) near[i] = true;
  });
  const lines: Line[] = [];
  marked.forEach((line, n) => {
    if (near[n]) lines.push(line);
    else {
      const last = lines.at(-1);
      if (last !== undefined && 'folded' in last) last.folded++;
      else lines.push({ folded: 1 });
    }
  });
  return lines;
}

const STYLE = {
  '+': { background: 'var(--ant-color-success-bg)' },
  '-': { background: 'var(--ant-color-error-bg)' },
  ' ': {},
} as const;

/**
 * The line diff of two versions of a text file: each added line marked `+` on the success colour, each removed line
 * `-` on the error colour, three unchanged lines around each change, and longer runs of unchanged lines folded. A side
 * of more than 5000 lines is not compared.
 */
export function LineDiff({ before, after }: { before: string; after: string }) {
  const tooLong = linesOf(before).length > MAX_DIFF_LINES || linesOf(after).length > MAX_DIFF_LINES;
  const lines = useMemo(() => (tooLong ? [] : diffOf(before, after)), [before, after, tooLong]);
  if (tooLong) return <Typography.Text type="secondary">Too many lines to compare: more than {MAX_DIFF_LINES} on a side.</Typography.Text>;
  if (lines.every((l) => 'folded' in l)) return <Typography.Text type="secondary">No line changed.</Typography.Text>;
  return (
    <pre aria-label="Line diff" style={{ margin: 0, fontFamily: 'var(--ant-font-family-code)', fontSize: 'var(--ant-font-size-sm)', overflowX: 'auto' }}>
      {lines.map((line, n) =>
        'folded' in line ? (
          <div key={n} style={{ color: 'var(--ant-color-text-secondary)' }}>
            {`… ${line.folded} unchanged ${line.folded === 1 ? 'line' : 'lines'}`}
          </div>
        ) : (
          <div key={n} style={STYLE[line.mark]} data-mark={line.mark}>
            {`${line.mark} ${line.text}`}
          </div>
        ),
      )}
    </pre>
  );
}
