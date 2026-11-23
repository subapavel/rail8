import { ChangedOnly } from '../ChangedOnly';
import type { UiModule } from '../screens';
import { httpEndpointsApi } from './api';
import { httpFlowsApi } from './flows/api';
import { endpointsScreen, endpointTab, flowsScreen, flowTab } from './screen';

const endpoints = httpEndpointsApi();
const flows = httpFlowsApi();

/** The id a tab opened from a package shows for the file at `path`: its name without `.yaml` or `.yml`. */
const idOf = (path: string) => path.slice(path.lastIndexOf('/') + 1).replace(/\.ya?ml$/, '');

/**
 * The Endpoints and Flows screens, over the files of the working folder the development server serves, and the kinds
 * of artifact they own, opened from a package in the tabs their lists open.
 */
export default {
  order: 20,
  screens: [endpointsScreen(endpoints), flowsScreen(flows, endpoints)],
  artifacts: [
    { kind: 'flow', open: (path) => flowTab(flows, endpoints, path, idOf(path)), Change: ChangedOnly },
    { kind: 'endpoint', open: (path) => endpointTab(endpoints, path, idOf(path)), Change: ChangedOnly },
  ],
} satisfies UiModule;
