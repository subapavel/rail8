import type { ArtifactKind, NewArtifactProps, Side } from '../artifacts';
import { Page } from '../Page';
import type { Screen, UnsavedChanges } from '../screens';
import { msg } from '../language';
import { EndpointChange } from './EndpointChange';
import { EndpointForm } from './EndpointForm';
import type { EndpointsApi } from './api';
import { FlowChange } from './flows/FlowChange';
import { FlowPicture } from './flows/FlowPicture';
import type { FlowsApi } from './flows/api';
import { NewFlow } from './flows/NewFlow';

/**
 * The tab of one endpoint file: its id is `endpoint:` and the file's path, its title the endpoint's id. Closing it while
 * the form has unsaved changes asks first whether to save them.
 */
export function endpointTab(api: EndpointsApi, path: string, id: string): Screen {
  let guard: UnsavedChanges | null = null;
  const guardClose = (next: UnsavedChanges | null) => {
    guard = next;
  };
  return {
    id: `endpoint:${path}`,
    title: id,
    group: msg('frame.group.design'),
    render: () => (
      <Page title={false}>
        <EndpointForm api={api} path={path} guardClose={guardClose} />
      </Page>
    ),
    unsaved: () => guard,
  };
}

/** `api` with every endpoint file read as one that may not be edited: the form beside a flow's picture only shows it. */
function readOnly(api: EndpointsApi): EndpointsApi {
  return {
    ...api,
    read: async (path) => {
      const stored = await api.read(path);
      return { ...stored, file: { ...stored.file, editable: false } };
    },
  };
}

/**
 * The tab of one flow file: its id is `flow:` and the file's path, its title the flow's id. Its picture opens the
 * endpoints and flows it links to in tabs of their own. Closing it while the flow has unsaved changes asks first
 * whether to save them.
 */
export function flowTab(flows: FlowsApi, endpoints: EndpointsApi, path: string, id: string): Screen {
  const shown = readOnly(endpoints);
  const idOf = (file: string) => file.split('/').at(-1)!.replace(/\.ya?ml$/, '');
  let guard: UnsavedChanges | null = null;
  const guardClose = (next: UnsavedChanges | null) => {
    guard = next;
  };
  return {
    id: `flow:${path}`,
    title: id,
    group: msg('frame.group.design'),
    render: (open) => (
      <FlowPicture
        api={flows}
        path={path}
        endpointForm={(file) => <EndpointForm key={file} api={shown} path={file} />}
        openEndpoint={(file) => open(endpointTab(endpoints, file, idOf(file)))}
        openFlow={(file) => open(flowTab(flows, endpoints, file, idOf(file)))}
        createEndpoint={(file, text) => endpoints.create(file, text)}
        guardClose={guardClose}
      />
    ),
    unsaved: () => guard,
  };
}

/** The id a tab shows for the file at `path`: its name without `.yaml` or `.yml`. */
const idOf = (path: string) => path.slice(path.lastIndexOf('/') + 1).replace(/\.ya?ml$/, '');

/** The artifacts of `kind` among the files `list` gives: each file that could be read, by its path and its id. */
const listed = (kind: 'flow' | 'endpoint', { items }: { items: ({ path: string } & ({ id: string } | { problem: string }))[] }) =>
  items.flatMap((item) => ('id' in item ? [{ path: item.path, kind, id: item.id }] : []));

/**
 * The kinds of artifact flow and endpoint over `flows` and `endpoints`: each file opens in its tab and draws its
 * readable change, a flow's over the working folder's flows on disk, an endpoint's titled by the settings descriptions
 * of the node's components; each lists its files of the working folder; New flow writes a flow file and opens it.
 */
export function flowKinds(flows: FlowsApi, endpoints: EndpointsApi): ArtifactKind[] {
  function FlowFileChange(props: { path: string; before: Side | null; after: Side | null }) {
    return <FlowChange api={flows} {...props} />;
  }
  function EndpointFileChange(props: { path: string; before: Side | null; after: Side | null }) {
    return <EndpointChange api={endpoints} {...props} />;
  }
  function NewFlowFile({ open, ...props }: NewArtifactProps) {
    return <NewFlow api={flows} {...props} openFlow={(path, id) => open(flowTab(flows, endpoints, path, id))} />;
  }
  return [
    {
      kind: 'flow',
      open: (path) => flowTab(flows, endpoints, path, idOf(path)),
      Change: FlowFileChange,
      New: NewFlowFile,
      list: async (signal) => listed('flow', await flows.list(signal)),
    },
    {
      kind: 'endpoint',
      open: (path) => endpointTab(endpoints, path, idOf(path)),
      Change: EndpointFileChange,
      list: async () => listed('endpoint', await endpoints.list()),
    },
  ];
}
