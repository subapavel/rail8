import type { UiModule } from '../screens';
import { httpUsersApi } from './api';
import { usersScreen } from './screen';

/** The Users screen, over the node's admin API: the screen of the Administration group. */
export default { order: 40, screens: [usersScreen(httpUsersApi())] } satisfies UiModule;
