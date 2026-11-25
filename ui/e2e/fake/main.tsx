import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { ArtifactKinds } from '../../src/artifacts';
import { fakeApiClient } from '../../src/mapping/api/fake/fake-api';
import { mappingKind } from '../../src/mapping/screen';
import { storageBrowserState } from '../../src/mapping/studio/browser-state/browser-state';
import { folderTab } from '../../src/packages/screen';
import { Shell } from '../../src/Shell';

// The shell with only the tab of the folder mappings, which is no package, over the fake mapping service, for the
// end-to-end scenarios that need no real service. The development server serves this page at /e2e/fake/; the
// production build leaves it out.
const kinds = new Map([['mapping', mappingKind(fakeApiClient(), storageBrowserState(window.sessionStorage))]]);
const mappings = folderTab('mappings', true);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ArtifactKinds.Provider value={kinds}>
      <Shell screens={[mappings]} start={mappings.id} />
    </ArtifactKinds.Provider>
  </StrictMode>,
);
