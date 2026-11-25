import type { UiModule } from '../screens';
import { httpPackagesApi } from './api';
import { findArtifacts, packagesScreen } from './screen';

const api = httpPackagesApi();

/**
 * The Packages screen, over the development server's git half: the first screen of the Design group; and what Quick
 * open finds, every artifact of the working folder through its package or its folder.
 */
export default { order: 15, screens: [packagesScreen(api)], find: (kinds) => findArtifacts(api, kinds) } satisfies UiModule;
