import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { ArtifactKinds, artifactKinds } from './artifacts';
import { home } from './Home';
import { finder, screenList } from './screens';
import { SessionGate } from './SessionGate';
import { Shell } from './Shell';

// Every folder of src is a module of the UI; its module.tsx names its screens, the kinds of artifact it owns and what
// it finds for Quick open.
const modules = import.meta.glob('./*/module.tsx', { eager: true });
const screens = screenList(home, modules);
const kinds = artifactKinds(modules);
const find = finder(modules);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ArtifactKinds.Provider value={kinds}>
      <SessionGate>
        <Shell screens={screens} start={home.id} find={find} />
      </SessionGate>
    </ArtifactKinds.Provider>
  </StrictMode>,
);
