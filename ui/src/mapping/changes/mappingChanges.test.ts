import { describe, expect, test } from 'vitest';
import type { MappingModel, StructureRef, TargetFieldMapping } from '../generated/mapping-model';
import { mappingChanges, udfSourcePath, udfSourcePaths, type UdfSources } from './mappingChanges';

const PATH = 'orders/mappings/order.mapping.json';
const NO_SOURCES: UdfSources = { before: new Map(), after: new Map() };

const structure = (root: string, fingerprint = 'sha256:aaa'): StructureRef => ({ bundle: ['../structures/order.xsd'], root: { element: root }, fingerprint });

/** `/Out/Id` as `concat(/Orders/Order/Id, "-")`, its boxes numbered by `ids` and placed at `x`. */
function concatId(ids = ['b1', 'b2', 'b3'], x = 10, field = '/Orders/Order/Id'): TargetFieldMapping {
  const [concat, source, dash] = ids as [string, string, string];
  return {
    id: 'm1',
    result: concat,
    boxes: {
      [concat]: { kind: 'function', fn: 'concat', inputs: [source, dash] },
      [source]: { kind: 'source', field },
      [dash]: { kind: 'function', fn: 'constant', params: { value: '-' } },
    },
    layout: { target: { x: 400, y: 0 }, boxes: { [concat]: { x: 200, y: 0 }, [source]: { x, y: 0 }, [dash]: { x, y: 50 } } },
  };
}

const model = (rest: Partial<MappingModel> = {}): MappingModel => ({
  formatVersion: 1,
  source: structure('Orders'),
  target: structure('Out'),
  targetFieldMappings: { '/Out/Id': concatId() },
  ...rest,
});

const text = (m: MappingModel) => `${JSON.stringify(m, null, 2)}\n`;
const changes = (before: MappingModel | null, after: MappingModel | null, sources = NO_SOURCES) =>
  mappingChanges(PATH, before === null ? null : text(before), after === null ? null : text(after), sources);

describe('mappingChanges', () => {
  test('writes a remapped target field as one formula a side, from its result box down', () => {
    const after = model({ targetFieldMappings: { '/Out/Id': concatId(undefined, undefined, '/Orders/Order/Number') } });

    expect(changes(model(), after)).toEqual({
      status: 'modified',
      unreadable: [],
      rows: [{ key: 'field:/Out/Id', field: '/Out/Id', before: 'concat(/Orders/Order/Id, "-")', after: 'concat(/Orders/Order/Number, "-")', status: 'changed' }],
      moved: 0,
    });
  });

  test('lists a target field added and one removed, with nothing connected written so', () => {
    const before = model({ targetFieldMappings: { '/Out/Id': concatId(), '/Out/Old': { id: 'm2', result: null, boxes: {} } } });
    const after = model({
      targetFieldMappings: {
        '/Out/Id': concatId(),
        '/Out/Total': {
          id: 'm3',
          result: 'r',
          boxes: {
            r: { kind: 'udf', udf: 'roundAmount', inputs: ['p', null], params: { scale: '2' } },
            p: { kind: 'source', field: '/Orders/Order/Price', context: '/Orders' },
          },
        },
      },
    });

    expect(changes(before, after).rows).toEqual([
      { key: 'field:/Out/Old', field: '/Out/Old', before: 'nothing connected', after: 'none', status: 'removed' },
      { key: 'field:/Out/Total', field: '/Out/Total', before: 'none', after: 'roundAmount(/Orders/Order/Price (context /Orders), _, scale: "2")', status: 'added' },
    ]);
  });

  test('takes boxes that are only numbered differently for no change', () => {
    const after = model({ targetFieldMappings: { '/Out/Id': concatId(['x9', 'x8', 'x7']) } });

    expect(changes(model(), after)).toMatchObject({ status: 'modified', rows: [], moved: 0 });
  });

  test('counts the target fields whose boxes only moved, once, and not one whose formula changed too', () => {
    const before = model({ targetFieldMappings: { '/Out/A': concatId(), '/Out/B': concatId(), '/Out/C': concatId() } });
    const after = model({
      targetFieldMappings: { '/Out/A': concatId(['n1', 'n2', 'n3'], 90), '/Out/B': concatId(undefined, 90), '/Out/C': concatId(undefined, 90, '/Orders/Order/Other') },
    });

    const result = changes(before, after);

    expect(result.moved).toBe(2);
    expect(result.rows.map((r) => r.field)).toEqual(['/Out/C']);
  });

  test('lists a box added that is not connected to the result, and counts one that only moved', () => {
    const loose = (x: number): TargetFieldMapping => {
      const m = concatId();
      return { ...m, boxes: { ...m.boxes, u: { kind: 'function', fn: 'upper', inputs: [null] } }, layout: { ...m.layout, boxes: { ...m.layout!.boxes, u: { x, y: 90 } } } };
    };
    const before = model({ targetFieldMappings: { '/Out/Id': concatId(), '/Out/B': loose(0) } });
    const after = model({ targetFieldMappings: { '/Out/Id': loose(0), '/Out/B': loose(50) } });

    expect(changes(before, after)).toMatchObject({
      rows: [{ field: '/Out/Id', before: 'concat(/Orders/Order/Id, "-")', after: 'concat(/Orders/Order/Id, "-"); not connected: upper(_)', status: 'changed' }],
      moved: 1,
    });
  });

  test('reads a version whose fields, boxes, inputs or UDFs are not of their shape as no mapping', () => {
    const broken = [
      '{"formatVersion": 1, "targetFieldMappings": {"/x": null}}',
      '{"formatVersion": 1, "udfs": {"f": null}}',
      '{"formatVersion": 1, "targetFieldMappings": {"/x": {"id": "m", "result": "b", "boxes": {"b": {"kind": "function", "fn": "f", "inputs": "b"}}}}}',
      '{"formatVersion": 1, "targetFieldMappings": {"/x": {"id": "m", "result": "b", "boxes": {"b": 1}}}}',
    ];
    for (const after of broken) expect(mappingChanges(PATH, text(model()), after, NO_SOURCES)).toMatchObject({ status: 'unreadable', unreadable: [{ side: 'after' }] });
  });

  test('says "structure changed" when a fingerprint differs, and lists a root or bundle that changed', () => {
    const after = model({ source: structure('Orders', 'sha256:bbb'), target: { ...structure('Invoice'), bundle: ['common@1.0.0:/invoice.xsd'] } });

    expect(changes(model(), after).rows).toEqual([
      { key: 'Source:structure', field: 'Source structure', before: 'sha256:aaa', after: 'sha256:bbb', status: 'structure changed' },
      { key: 'Target:bundle', field: 'Target bundle', before: '../structures/order.xsd', after: 'common@1.0.0:/invoice.xsd', status: 'changed' },
      { key: 'Target:root', field: 'Target root', before: 'Out', after: 'Invoice', status: 'changed' },
    ]);
  });

  test('lists a UDF’s inputs, parameters and source as fields, and its source file as a line diff', () => {
    const udf = { executionKind: 'single' as const, inputs: ['amount'], params: { scale: 'int' as const }, source: '../udfs/Amounts.java#roundAmount' };
    const before = model({ udfs: { roundAmount: udf, old: { executionKind: 'queue', source: '../udfs/Amounts.java#old' } } });
    const after = model({ udfs: { roundAmount: { ...udf, inputs: ['amount', 'fallback'], params: { scale: 'int', mode: 'string' } }, fresh: { executionKind: 'context', inputs: ['keys'], source: '../udfs/Amounts.java#fresh' } } });
    const sources: UdfSources = { before: new Map([['orders/udfs/Amounts.java', 'class Amounts {\n  int a;\n}\n']]), after: new Map([['orders/udfs/Amounts.java', 'class Amounts {\n  long a;\n}\n']]) };

    expect(changes(before, after, sources).rows).toEqual([
      { key: 'udf:fresh', field: 'UDF fresh', before: 'none', after: 'context(keys)', status: 'added' },
      { key: 'udf:old', field: 'UDF old', before: 'queue()', after: 'none', status: 'removed' },
      { key: 'udf:roundAmount:inputs', field: 'UDF roundAmount: inputs', before: 'amount', after: 'amount, fallback', status: 'changed' },
      { key: 'udf:roundAmount:parameters', field: 'UDF roundAmount: parameters', before: 'scale: int', after: 'mode: string, scale: int', status: 'changed' },
      {
        key: 'source:orders/udfs/Amounts.java',
        field: 'UDF source orders/udfs/Amounts.java',
        before: 'orders/udfs/Amounts.java',
        after: 'orders/udfs/Amounts.java',
        status: 'changed',
        diff: { before: 'class Amounts {\n  int a;\n}\n', after: 'class Amounts {\n  long a;\n}\n' },
      },
    ]);
  });

  test('finds a UDF’s source file from the mapping’s folder, within its package', () => {
    expect(udfSourcePath(PATH, '../udfs/Amounts.java#roundAmount')).toBe('orders/udfs/Amounts.java');
    expect(udfSourcePath(PATH, 'Local.java#f')).toBe('orders/mappings/Local.java');
    expect(udfSourcePath(PATH, '../../other/Amounts.java#f')).toBeNull();
    expect(udfSourcePath(PATH, 'common@1.0.0:/Amounts.java#f')).toBeNull();
    expect(udfSourcePaths(PATH, text(model({ udfs: { a: { executionKind: 'single', source: 'B.java#a' }, b: { executionKind: 'single', source: 'B.java#b' } } })))).toEqual(['orders/mappings/B.java']);
    expect(udfSourcePaths(PATH, 'not json')).toEqual([]);
  });

  test('lists the description', () => {
    expect(changes(model(), model({ description: 'Orders to the partner’s list.' })).rows).toEqual([
      { key: 'description', field: 'Description', before: 'none', after: 'Orders to the partner’s list.', status: 'added' },
    ]);
  });

  test('says when only the formatting changed, and when nothing did', () => {
    const m = model();
    expect(mappingChanges(PATH, text(m), JSON.stringify(m), NO_SOURCES).status).toBe('formatting');
    expect(mappingChanges(PATH, text(m), text(m), NO_SOURCES).status).toBe('same');
  });

  test('lists every target field of an added mapping and of a deleted one', () => {
    expect(changes(null, model())).toMatchObject({ status: 'added', rows: [{ field: 'Source bundle' }, { field: 'Source root' }, { field: 'Target bundle' }, { field: 'Target root' }, { field: '/Out/Id', status: 'added' }] });
    expect(changes(model(), null)).toMatchObject({ status: 'deleted', rows: [{}, {}, {}, {}, { field: '/Out/Id', status: 'removed' }] });
  });

  test('says which version is no mapping, and why', () => {
    expect(mappingChanges(PATH, '{', text(model()), NO_SOURCES)).toMatchObject({ status: 'unreadable', unreadable: [{ side: 'before' }] });
    expect(mappingChanges(PATH, text(model()), '{"formatVersion": 2}', NO_SOURCES).unreadable).toEqual([{ side: 'after', message: 'the file is not a mapping of format 1: its formatVersion is 2' }]);
  });

  test('writes a box that feeds several others once in the comparison, so a deep sharing stays small', () => {
    const boxes: TargetFieldMapping['boxes'] = { s: { kind: 'source', field: '/A' } };
    for (let i = 0; i < 60; i++) boxes[`c${i}`] = { kind: 'function', fn: 'concat', inputs: [i === 0 ? 's' : `c${i - 1}`, i === 0 ? 's' : `c${i - 1}`] };
    const deep = (field: string): MappingModel => model({ targetFieldMappings: { '/Out/Id': { id: 'm', result: 'c59', boxes: { ...boxes, s: { kind: 'source', field } } } } });

    const [row] = changes(deep('/A'), deep('/B')).rows;

    expect(row?.after.length).toBeLessThanOrEqual(500);
    expect(row?.after.endsWith('…')).toBe(true);
  });
});
