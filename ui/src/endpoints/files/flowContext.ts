import { readFlow, saysFlow } from '../flows/flowYaml.ts';
import { EndpointFileError, readEndpoint } from './endpointYaml.ts';

/** One flow file of the working folder: its path relative to the folder, with `/`, and its id or why it cannot be read. */
export type FlowItem = { path: string; id: string } | { path: string; problem: string };

/**
 * What a flow refers to in the working folder. `endpoints` are those of the flow's package, the nearest folder above the
 * file that holds `package.yaml`; `address` is the endpoint's own, `overlaid` says an environment changes it, and `addressIn`
 * holds what each environment that changes it sets it to.
 * `flows` are every flow of the working folder, `address` the one their inbound `direct` endpoint serves. `partial` is
 * true when the walk stopped at its limit or a file of the package could not be read, so that what is not found may still
 * exist.
 */
export interface FlowContext {
  endpoints: { id: string; path: string; scheme: string; direction: 'in' | 'out'; address?: string; overlaid?: true; addressIn?: Record<string, unknown> }[];
  flows: { path: string; id: string; mode?: 'sync' | 'at-least-once' | 'at-most-once'; address?: string }[];
  partial: boolean;
}

const MODES = new Set(['sync', 'at-least-once', 'at-most-once']);

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/** The folder of `path`, with `/`; '' for a file at the top of the working folder. */
const folderOf = (path: string) => path.slice(0, Math.max(0, path.lastIndexOf('/')));

/** The flows and endpoints of a set of YAML files, the package folder of each path, and the packages a file could not be read in. */
export interface Survey {
  flows: { item: FlowItem; from?: string; mode?: string; pkg: string }[];
  endpoints: { entry: FlowContext['endpoints'][number]; pkg: string }[];
  unreadable: Set<string>;
  /** The package folder of `path`: the nearest folder above it that holds `package.yaml`, else ''. */
  packageOf(path: string): string;
}

/** Reads `files`, YAML files by their path relative to the working folder with `/`, as {@link Survey} describes. */
export function surveyOf(files: readonly { path: string; text: string }[]): Survey {
  const packages = new Set(files.filter((f) => f.path === 'package.yaml' || f.path.endsWith('/package.yaml')).map((f) => folderOf(f.path)));
  const packageOf = (path: string) => {
    for (let folder = folderOf(path); ; folder = folderOf(folder)) {
      if (packages.has(folder) || folder === '') return folder;
    }
  };
  const flows: Survey['flows'] = [];
  const endpoints: Survey['endpoints'] = [];
  const unreadable = new Set<string>();
  for (const { path, text } of files) {
    const pkg = packageOf(path);
    const read = readFlow(text);
    if (read.kind === 'flow') {
      const from = isRecord(read.value.from) && typeof read.value.from.endpoint === 'string' ? read.value.from.endpoint : undefined;
      const mode = isRecord(read.value.delivery) && typeof read.value.delivery.mode === 'string' ? read.value.delivery.mode : undefined;
      flows.push({ item: { path, id: read.id }, from, mode, pkg });
      continue;
    }
    if (read.kind === 'problem') {
      unreadable.add(pkg);
      if (saysFlow(text)) flows.push({ item: { path, problem: read.message }, pkg });
      continue;
    }
    try {
      const doc = readEndpoint(text);
      if (doc === null) continue;
      const address = typeof doc.config.address === 'string' ? doc.config.address : undefined;
      const changing = doc.environments.filter((env) => env.options.includes('address'));
      const overlay = changing.length === 0 ? {} : { overlaid: true as const, addressIn: Object.fromEntries(changing.map((env) => [env.name, env.config.address])) };
      endpoints.push({ pkg, entry: { id: doc.id, path, scheme: doc.scheme, direction: doc.direction, ...(address === undefined ? {} : { address }), ...overlay } });
    } catch (error) {
      if (!(error instanceof EndpointFileError)) throw error;
      unreadable.add(pkg);
    }
  }
  return { flows, endpoints, unreadable, packageOf };
}

/**
 * The context of the flow at `path` in `survey`: the endpoints of its package, and every flow of the survey with the
 * address its inbound `direct` endpoint serves. `truncated` says the files were not all read.
 */
export function contextOf(survey: Survey, path: string, truncated: boolean): FlowContext {
  const { flows, endpoints, unreadable, packageOf } = survey;
  const pkg = packageOf(path);
  const served = (f: Survey['flows'][number]) =>
    endpoints.find((e) => e.pkg === f.pkg && e.entry.id === f.from && e.entry.scheme === 'direct' && e.entry.direction === 'in')?.entry;
  return {
    endpoints: endpoints.filter((e) => e.pkg === pkg).map((e) => e.entry),
    flows: flows.flatMap((f) => {
      if (!('id' in f.item)) return [];
      const door = served(f);
      const address = door !== undefined && door.overlaid === undefined ? door.address : undefined;
      return [
        {
          path: f.item.path,
          id: f.item.id,
          ...(f.mode !== undefined && MODES.has(f.mode) ? { mode: f.mode as 'sync' } : {}),
          ...(address === undefined ? {} : { address }),
        },
      ];
    }),
    partial: truncated || unreadable.has(pkg),
  };
}
