import type { UiModule } from '../screens';
import { httpEndpointsApi } from './api';
import { httpFlowsApi } from './flows/api';
import { flowKinds } from './screen';

/**
 * The kinds of artifact flow and endpoint, over the files of the working folder the development server serves: a
 * package's tab opens and compares them through it, and New flow writes one there. The module has no screen of its own.
 */
export default { order: 20, screens: [], artifacts: flowKinds(httpFlowsApi(), httpEndpointsApi()) } satisfies UiModule;
