import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { ArtifactKinds } from '../../src/artifacts';
import { flowKinds } from '../../src/endpoints/screen';
import { home } from '../../src/Home';
import { fakeApiClient } from '../../src/mapping/api/fake/fake-api';
import { mappingKind } from '../../src/mapping/screen';
import { messagesScreen } from '../../src/messages/screen';
import { storageBrowserState } from '../../src/mapping/studio/browser-state/browser-state';
import { findArtifacts, packagesScreen } from '../../src/packages/screen';
import { screenList, type Screen } from '../../src/screens';
import { sessionClient, type Me } from '../../src/session';
import { SessionGate } from '../../src/SessionGate';
import { Shell } from '../../src/Shell';
import { auditScreen, myTokensScreen, tokensScreen, usersScreen } from '../../src/users/screen';
import { pictureAudit } from './audit';
import { pictureEndpoints, pictureFlows } from './flows';
import { pictureMessages } from './messages';
import { pictureUsers } from './users';
import { NOW, pictureWorld } from './world';

// The application with every screen of its modules, for the pictures that scripts/pictures.mjs takes: Packages and
// Quick open over the fake world of world.ts at a fixed time, its flows and endpoints opening over the folder of
// flows.ts and its mappings over the fake mapping service, and the other screens as the application has them, which
// the pictures never open. The production build of the application leaves the page out.
const people = pictureUsers(() => NOW);
const modules = import.meta.glob('../../src/*/module.tsx', { eager: true });
const world = pictureWorld();
const kinds = new Map(
  [...flowKinds(pictureFlows(), pictureEndpoints()), mappingKind(fakeApiClient(), storageBrowserState(window.sessionStorage))].map((kind) => [kind.kind, kind]),
);
const fakes: Record<string, Screen> = {
  packages: packagesScreen(world, () => NOW),
  messages: messagesScreen(pictureMessages(), () => NOW),
  users: usersScreen(people.users, people.tokens),
  tokens: tokensScreen(people.tokens),
  audit: auditScreen(pictureAudit()),
  'my-tokens': myTokensScreen(people.tokens),
};
const screens = screenList(home, modules).map((screen) => fakes[screen.id] ?? screen);

// With ?session=out, first, in, none, admin or failed, the frame stands behind the session's gate over a fake UI server:
// nobody logged in, an operator with a first password, an operator logged in, who may not open a mapping nor read a body, a
// person with no role, an administrator on a node that holds the emergency key, or a UI server that answers 503. `start`
// names the screen open at first. `window.endSession()` ends the session, so the login shows over the open tabs.
const OPERATOR: Me = {
  name: 'anna',
  kind: 'person',
  roles: ['operator'],
  actions: ['self.read', 'self.password', 'self.logout', 'self.tokens', 'self.token-issue', 'message.read', 'message.retry', 'deployment.read'],
  refused: {
    'module.call': { code: 'not-allowed', needs: ['developer', 'administrator'] },
    'message.body': { code: 'not-allowed', needs: ['message-bodies'] },
    'package.deploy': { code: 'not-allowed', needs: ['developer'] },
  },
  via: 'session',
  firstPassword: false,
  expiresAt: '2026-11-24T23:00:00Z',
  emergencyKey: false,
  session: '0123456789abcdef',
};
const NOBODY: Me = {
  ...OPERATOR,
  name: 'bob',
  roles: [],
  actions: ['self.read', 'self.password', 'self.logout', 'self.tokens', 'self.token-issue'],
  refused: {
    ...OPERATOR.refused,
    'message.read': { code: 'not-allowed', needs: ['operator'] },
    'deployment.read': { code: 'not-allowed', needs: ['developer', 'operator'] },
  },
};
const ADMINISTRATOR: Me = {
  ...OPERATOR,
  name: 'alice',
  roles: ['administrator'],
  actions: [
    'self.read', 'self.password', 'self.logout', 'self.tokens', 'self.token-issue', 'user.read', 'user.create', 'roles.change', 'administrators.change',
    'user.block', 'user.password-reset', 'session.end', 'token.read', 'token.issue', 'token.revoke', 'token.ceiling', 'audit.read',
  ],
  refused: {
    'message.read': { code: 'not-allowed', needs: ['operator'] },
    'deployment.read': { code: 'not-allowed', needs: ['developer', 'operator'] },
    'module.call': { code: 'not-allowed', needs: ['developer'] },
  },
  emergencyKey: true,
};
const query = new URLSearchParams(location.search);
const scene = query.get('session');
let ended = false;
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const client = sessionClient({
  send: async (input, init) => {
    if (String(input) === '/session' && init?.method === 'POST') return json(401, { error: 'the login is refused', code: 'login-refused' });
    if (String(input) === '/session') {
      if (scene === 'out') return json(401, { code: 'no-credential' });
      if (scene === 'failed') return json(503, { error: 'the node at 127.0.0.1:8081 is busy', code: 'busy' });
      return json(200, scene === 'none' ? NOBODY : scene === 'admin' ? ADMINISTRATOR : { ...OPERATOR, firstPassword: scene === 'first' });
    }
    return ended ? json(401, { code: 'session-ended' }) : json(200, {});
  },
  reload: () => undefined,
  timer: () => new Promise(() => undefined),
});
Object.assign(window, {
  endSession: () => {
    ended = true;
    void client.fetch('/admin/messages');
  },
});

const shell = <Shell screens={screens} start={query.get('start') ?? home.id} find={(found) => findArtifacts(world, found, () => NOW)} />;
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ArtifactKinds.Provider value={kinds}>{scene === null ? shell : <SessionGate client={client}>{shell}</SessionGate>}</ArtifactKinds.Provider>
  </StrictMode>,
);
