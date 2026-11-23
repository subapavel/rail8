import { ApiOutlined, ApartmentOutlined } from '@ant-design/icons';
import type { Screen, UnsavedChanges } from '../screens';
import { EndpointForm } from './EndpointForm';
import { EndpointList } from './EndpointList';
import type { EndpointsApi } from './api';
import { FlowList } from './flows/FlowList';
import { FlowPicture } from './flows/FlowPicture';
import type { FlowsApi } from './flows/api';

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
    group: 'Design',
    render: () => <EndpointForm api={api} path={path} guardClose={guardClose} />,
    unsaved: () => guard,
  };
}

/** The Endpoints screen over `api`: the working folder's endpoint files, each one's settings form opening in a tab of its own. */
export function endpointsScreen(api: EndpointsApi): Screen {
  return {
    id: 'endpoints',
    title: 'Endpoints',
    group: 'Design',
    icon: <ApiOutlined aria-hidden />,
    description: 'Inspect and edit connection settings in the working folder.',
    render: (open) => <EndpointList api={api} onOpen={(path, id) => open(endpointTab(api, path, id))} />,
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
    group: 'Design',
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

/** The Flows screen over `flows`: the working folder's flow files, each one's picture opening in a tab of its own. */
export function flowsScreen(flows: FlowsApi, endpoints: EndpointsApi): Screen {
  return {
    id: 'flows',
    title: 'Flows',
    group: 'Design',
    icon: <ApartmentOutlined aria-hidden />,
    description: 'Inspect flow diagrams and edit their steps.',
    render: (open) => <FlowList api={flows} onOpen={(path, id) => open(flowTab(flows, endpoints, path, id))} />,
  };
}
