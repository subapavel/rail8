import type { UiModule } from '../screens';
import { httpAuditApi, httpTokensApi, httpUsersApi } from './api';
import { auditScreen, myTokensScreen, tokensScreen, usersScreen } from './screen';

const tokens = httpTokensApi();

/** The screens of users, tokens and the audit, over the node's admin API: the Administration group, and the caller's own tokens. */
export default { order: 40, screens: [usersScreen(httpUsersApi(), tokens), tokensScreen(tokens), auditScreen(httpAuditApi()), myTokensScreen(tokens)] } satisfies UiModule;
