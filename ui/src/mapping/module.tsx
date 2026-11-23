import type { UiModule } from '../screens';
import { httpApiClient } from './api/http-api';
import { MappingChange } from './changes/MappingChange';
import { mappingsScreen, mappingTab } from './screen';
import { storageBrowserState } from './studio/browser-state/browser-state';

const api = httpApiClient();
const browserState = storageBrowserState(window.localStorage);

/**
 * The Mappings screen, over the studio's API, keeping the viewer's preferences in the browser's local storage, and the
 * kind of artifact it owns: a mapping opened from a package in the studio's tab, by its id, its path without
 * `.mapping.json`, and its change read by its target fields.
 */
export default {
  order: 30,
  screens: [mappingsScreen(api, browserState)],
  artifacts: [{ kind: 'mapping', open: (path) => mappingTab(api, browserState, path.replace(/\.mapping\.json$/, '')), Change: MappingChange }],
} satisfies UiModule;
