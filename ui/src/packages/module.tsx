import type { UiModule } from '../screens';
import { httpPackagesApi } from './api';
import { packagesScreen } from './screen';

/** The Packages screen, over the development server's git half: the first screen of the Design group. */
export default { order: 15, screens: [packagesScreen(httpPackagesApi())] } satisfies UiModule;
