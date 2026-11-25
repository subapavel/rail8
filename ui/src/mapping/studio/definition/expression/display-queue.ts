import { currentLanguage, textsIn, type Texts } from '../../../../language';
import type { TraceEntry } from '../../../api/client';

/** One slot of a context: a value, the empty value `""`, or SUPPRESS (`null` on the wire). */
export type ShownValue = { kind: 'value'; text: string } | { kind: 'empty' } | { kind: 'suppress' };

type Occurrences = Extract<TraceEntry, { kind: 'occurrences' }>;
type SlotRecord = Occurrences['parents'][number]['slots'][number];

/**
 * A row of a target field's occurrences: a parent slot with the occurrences it created (`minOccurs` when fewer than
 * the field's min, the parent slot that raised `po:MINOCCURS`), or the parent slots between two kept ones that the
 * trace does not hold.
 */
export type OccurrenceRow =
  | { kind: 'parent'; parentSlot: number; created: number; minOccurs: boolean; slots: SlotRecord[] }
  | { kind: 'left-out'; from: number; to: number };

/** What the display queue shows of one trace entry; `capped` says what a capped entry cut. */
export type DisplayEntry =
  | { kind: 'queue'; contexts: ShownValue[][]; capped: string | undefined }
  | { kind: 'atomic'; type: string; value: ShownValue }
  | { kind: 'occurrences'; min: number; max: number | undefined; rows: OccurrenceRow[]; capped: string | undefined };

function shown(value: string | null): ShownValue {
  if (value === null) return { kind: 'suppress' };
  return value === '' ? { kind: 'empty' } : { kind: 'value', text: value };
}

/**
 * The display queue of a trace entry: a queue's contexts, an atomic value, or a target field's occurrences; what a
 * capped entry cut is in the language of `texts`.
 */
export function displayEntry(entry: TraceEntry, { t }: Texts = textsIn(currentLanguage())): DisplayEntry {
  switch (entry.kind) {
    case 'queue': {
      const kept = entry.contexts.reduce((sum, context) => sum + context.length, 0);
      return {
        kind: 'queue',
        contexts: entry.contexts.map((context) => context.map(shown)),
        capped: entry.capped
          ? t('studio.queue.cappedValues', { kept, slots: entry.slotCount, shownContexts: entry.contexts.length, contexts: entry.contextCount })
          : undefined,
      };
    }
    case 'atomic':
      return { kind: 'atomic', type: entry.type, value: shown(entry.value) };
    case 'occurrences':
      return { kind: 'occurrences', min: entry.min, max: entry.max, rows: occurrenceRows(entry), capped: cappedOccurrences(t, entry) };
  }
}

/**
 * A queue's size in the words of `texts`, "n contexts, n slots", from the run's totals, so a capped
 * queue counts what it cut too; no other entry, and no entry, has one.
 */
export function queueSize(entry: TraceEntry | undefined, { t }: Texts = textsIn(currentLanguage())): string | undefined {
  if (entry?.kind !== 'queue') return undefined;
  return t('studio.queue.size', { contexts: entry.contextCount, slots: entry.slotCount });
}

/** The parent slots in run order, with a `left-out` row where the numbers of two kept ones are not consecutive. */
function occurrenceRows({ parents, min }: Occurrences): OccurrenceRow[] {
  const rows: OccurrenceRow[] = [];
  parents.forEach(({ parentSlot, created, slots }, i) => {
    const before = i === 0 ? 0 : parents[i - 1]!.parentSlot;
    if (parentSlot > before + 1) rows.push({ kind: 'left-out', from: before + 1, to: parentSlot - 1 });
    rows.push({ kind: 'parent', parentSlot, created, minOccurs: created < min, slots });
  });
  return rows;
}

/**
 * What a capped occurrences entry shows: parent slots are numbered from 1 in run order and the last one is always kept,
 * so the last one's number is their total.
 */
function cappedOccurrences(t: Texts['t'], { capped, parents }: Occurrences): string | undefined {
  if (!capped) return undefined;
  const records = parents.reduce((sum, parent) => sum + parent.slots.length, 0);
  return t('studio.queue.cappedSlots', { shown: parents.length, total: parents.at(-1)!.parentSlot, records });
}
