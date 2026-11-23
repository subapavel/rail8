import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test } from 'vitest';
import { page } from 'vitest/browser';
import type { Side } from '../../artifacts';
import { Rail8Theme } from '../../theme';
import type { MappingModel, TargetFieldMapping } from '../generated/mapping-model';
import { MappingChange } from './MappingChange';

const PATH = 'orders/mappings/order.mapping.json';
const SOURCE = 'orders/udfs/Amounts.java';

const field = (from: string, x: number): TargetFieldMapping => ({
  id: 'm',
  result: 'c',
  boxes: { c: { kind: 'function', fn: 'concat', inputs: ['s', 'd'] }, s: { kind: 'source', field: from }, d: { kind: 'function', fn: 'constant', params: { value: '-' } } },
  layout: { boxes: { s: { x, y: 0 } } },
});

const model = (id: string, x: number, fingerprint: string): MappingModel => ({
  formatVersion: 1,
  source: { bundle: ['../structures/order.xsd'], root: { element: 'Orders' }, fingerprint },
  target: { bundle: ['../structures/out.xsd'], root: { element: 'Out' }, fingerprint: 'sha256:out' },
  targetFieldMappings: { '/Out/Id': field(id, 0), '/Out/Copy': field('/Orders/Order/Id', x) },
  udfs: { roundAmount: { executionKind: 'single', inputs: ['amount'], source: '../udfs/Amounts.java#roundAmount' } },
});

/** A version of the mapping at `rev`, its package holding the UDF source `java`. */
const side = (rev: string, m: MappingModel, java: string): Side => ({
  rev,
  text: JSON.stringify(m, null, 2),
  artifacts: async () => [],
  read: async (path) => (path === SOURCE ? java : null),
});

let root: Root | undefined;

function draw(before: Side | null, after: Side | null) {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(<Rail8Theme>{() => <MappingChange path={PATH} before={before} after={after} />}</Rail8Theme>);
}

const table = () => page.getByRole('table', { name: `Changes of ${PATH}` });
const row = (text: string) => table().getByRole('row').filter({ hasText: text });

afterEach(() => {
  root?.unmount();
  root = undefined;
  document.body.innerHTML = '';
});

describe('the readable change of a mapping', () => {
  test('lists the structure, a remapped field as its formulas, the boxes moved once, and the UDF source as a line diff', async () => {
    draw(
      side('a'.repeat(40), model('/Orders/Order/Id', 0, 'sha256:one'), 'class Amounts {\n  int a;\n}\n'),
      side('work', model('/Orders/Order/Number', 90, 'sha256:two'), 'class Amounts {\n  long a;\n}\n'),
    );

    await expect.element(row('Source structure')).toHaveTextContent('Source structuresha256:onesha256:twoStructure changed');
    await expect.element(row('/Out/Id')).toHaveTextContent('/Out/Idconcat(/Orders/Order/Id, "-")concat(/Orders/Order/Number, "-")Changed');
    await expect.element(row('Boxes moved')).toHaveTextContent('Boxes moved in 1 target fieldChanged');
    await expect.element(table().getByText('/Out/Copy')).not.toBeInTheDocument();

    await row(`UDF source ${SOURCE}`).getByRole('button', { name: /expand row/i }).click();
    const diff = page.getByLabelText('Line diff');
    await expect.element(diff).toBeVisible();
    expect(diff.element().textContent).toBe('  class Amounts {-   int a;+   long a;  }');
  });

  test('says a mapping was added, and that a version is no mapping', async () => {
    draw(null, side('work', model('/Orders/Order/Id', 0, 'sha256:one'), ''));
    await expect.element(page.getByText('Added: every target field is new.')).toBeVisible();
    await expect.element(row('/Out/Id')).toHaveTextContent('/Out/Idnoneconcat(/Orders/Order/Id, "-")Added');
    root?.unmount();

    draw({ rev: 'a'.repeat(40), text: '{"formatVersion": 2}', artifacts: async () => [], read: async () => null }, side('work', model('/a', 0, 'x'), ''));
    await expect.element(page.getByText('The older version cannot be read')).toBeVisible();
  });

  test('says no target field changed when the boxes are only numbered differently', async () => {
    const renumbered = (m: MappingModel): MappingModel => {
      const text = JSON.stringify(m).replaceAll('"c"', '"c2"').replaceAll('"s"', '"s2"').replaceAll('"d"', '"d2"');
      return JSON.parse(text) as MappingModel;
    };
    const m = model('/Orders/Order/Id', 0, 'sha256:one');
    draw(side('a'.repeat(40), m, 'x'), side('work', renumbered(m), 'x'));

    await expect.element(page.getByText('No target field changed: the boxes are only numbered differently.')).toBeVisible();
  });
});
