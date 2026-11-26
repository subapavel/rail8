import type { UiModule } from '../screens';
import { httpSecretsApi } from './api';
import { secretsScreen } from './screen';

/** The Secrets screen over the node's admin API, in the Administration group. */
export default { order: 45, screens: [secretsScreen(httpSecretsApi())] } satisfies UiModule;
