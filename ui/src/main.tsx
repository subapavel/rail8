import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { ArtifactKinds, artifactKinds } from './artifacts';
import { home } from './Home';
import { screenList } from './screens';
import { Shell } from './Shell';

// Every folder of src is a module of the UI; its module.tsx names its screens and the kinds of artifact it owns.
const modules = import.meta.glob('./*/module.tsx', { eager: true });
const screens = screenList(home, modules);
const kinds = artifactKinds(modules);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ArtifactKinds.Provider value={kinds}>
      <Shell screens={screens} start={home.id} />
    </ArtifactKinds.Provider>
  </StrictMode>,
);
