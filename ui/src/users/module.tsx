import type { UiModule } from '../screens';
import { httpTokensApi, httpUsersApi } from './api';
import { myTokensScreen, tokensScreen, usersScreen } from './screen';

const tokens = httpTokensApi();

/** The screens of users and tokens, over the node's admin API: the Administration group, and the caller's own tokens. */
export default { order: 40, screens: [usersScreen(httpUsersApi(), tokens), tokensScreen(tokens), myTokensScreen(tokens)] } satisfies UiModule;
