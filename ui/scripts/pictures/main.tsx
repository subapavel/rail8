import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { ArtifactKinds, artifactKinds } from '../../src/artifacts';
import { home } from '../../src/Home';
import { fakeApiClient } from '../../src/mapping/api/fake/fake-api';
import { mappingsScreen } from '../../src/mapping/screen';
import { storageBrowserState } from '../../src/mapping/studio/browser-state/browser-state';
import { packagesScreen } from '../../src/packages/screen';
import { screenList, type Screen } from '../../src/screens';
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

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ArtifactKinds.Provider value={artifactKinds(modules)}>
      <Shell screens={screens} start={home.id} />
    </ArtifactKinds.Provider>
  </StrictMode>,
);
