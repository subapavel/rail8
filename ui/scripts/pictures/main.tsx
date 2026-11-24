import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { ArtifactKinds, artifactKinds } from '../../src/artifacts';
import { home } from '../../src/Home';
import { fakeApiClient } from '../../src/mapping/api/fake/fake-api';
import { mappingsScreen } from '../../src/mapping/screen';
import { storageBrowserState } from '../../src/mapping/studio/browser-state/browser-state';
import { packagesScreen } from '../../src/packages/screen';
import { screenList, type Screen } from '../../src/screens';
import { sessionClient, type Me } from '../../src/session';
import { SessionGate } from '../../src/SessionGate';
import { Shell } from '../../src/Shell';
import { NOW, pictureWorld } from './world';

// The application with every screen of its modules, for the pictures that scripts/pictures.mjs takes: Packages over
// the fake world of world.ts at a fixed time, Mappings over the fake mapping service, and the other screens as the
// application has them, which the pictures never open. The production build of the application leaves the page out.
const modules = import.meta.glob('../../src/*/module.tsx', { eager: true });
const fakes: Record<string, Screen> = {
  packages: packagesScreen(pictureWorld(), () => NOW),
  mappings: mappingsScreen(fakeApiClient(), storageBrowserState(window.sessionStorage)),
};
const screens = screenList(home, modules).map((screen) => fakes[screen.id] ?? screen);

// With ?session=out, first or in, the frame stands behind the session's gate over a fake UI server: nobody logged in, an
// operator with a first password, or an operator logged in, who may not open Mappings. `window.endSession()` then ends
// the session, so the login shows over the open tabs.
const OPERATOR: Me = {
  name: 'anna',
  kind: 'person',
  roles: ['operator'],
  actions: ['self.read', 'self.password', 'self.logout', 'message.read', 'message.retry', 'deployment.read'],
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
const scene = new URLSearchParams(location.search).get('session');
let ended = false;
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const client = sessionClient({
  send: async (input, init) => {
    if (String(input) === '/session' && init?.method === 'POST') return json(401, { error: 'the login is refused', code: 'login-refused' });
    if (String(input) === '/session') return scene === 'out' ? json(401, { code: 'no-credential' }) : json(200, { ...OPERATOR, firstPassword: scene === 'first' });
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

const shell = <Shell screens={screens} start={home.id} />;
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ArtifactKinds.Provider value={artifactKinds(modules)}>{scene === null ? shell : <SessionGate client={client}>{shell}</SessionGate>}</ArtifactKinds.Provider>
  </StrictMode>,
);
