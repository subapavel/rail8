import type { UiModule } from '../screens';
import { httpApiClient } from './api/http-api';
import { mappingKind } from './screen';
import { storageBrowserState } from './studio/browser-state/browser-state';

/**
 * The kind of artifact mapping, over the studio's API, keeping the viewer's preferences in the browser's local
 * storage: a package's tab and a folder of mappings open, compare and create mappings through it. The module has no
 * screen of its own.
 */
export default { order: 30, screens: [], artifacts: [mappingKind(httpApiClient(), storageBrowserState(window.localStorage))] } satisfies UiModule;
