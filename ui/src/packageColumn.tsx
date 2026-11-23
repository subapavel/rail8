import type { ProColumns } from '@ant-design/pro-components';

/** The package of the file at `path`, relative to the working folder with `/`: its first segment; undefined for a file at the top. */
export function packageOfPath(path: string): string | undefined {
  const slash = path.indexOf('/');
  return slash < 0 ? undefined : path.slice(0, slash);
}

/**
 * The Package column of a list of the working folder's files, with a filter on it: each row's package, the first
 * segment of `pathOf(row)`, or a dash for a file at the top of the working folder. The filter offers every package of
 * `rows`. The column links nowhere: a package's tab opens from the Packages screen.
 */
export function packageColumn<T>(rows: readonly T[], pathOf: (row: T) => string): ProColumns<T> {
  const packages = [...new Set(rows.map((row) => packageOfPath(pathOf(row)) ?? ''))].sort();
  return {
    title: 'Package',
    key: 'package',
    render: (_, row) => packageOfPath(pathOf(row)) ?? '—',
    filters: packages.map((name) => ({ text: name === '' ? '—' : name, value: name })),
    onFilter: (value, row) => (packageOfPath(pathOf(row)) ?? '') === value,
  };
}
