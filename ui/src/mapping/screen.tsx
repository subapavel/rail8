import type { ArtifactKind, NewArtifactProps } from '../artifacts';
import type { Screen, UnsavedChanges } from '../screens';
import { msg } from '../language';
import type { ApiClient } from './api/client';
import { MappingChange } from './changes/MappingChange';
import { NewMapping } from './NewMapping';
import { Studio } from './studio/Studio';
import type { BrowserStateStore } from './studio/browser-state/browser-state';

export interface MappingTabOptions {
  /** Opens the mapping in edit mode on the Signature tab, as Create does. */
  editing?: boolean;
}

/**
 * The tab of one mapping in the studio: its id is `mapping:` and the mapping's id, its title the mapping's name.
 * Closing it while the mapping has unsaved changes asks first whether to save them.
 */
export function mappingTab(api: ApiClient, browserState: BrowserStateStore, id: string, options: MappingTabOptions = {}): Screen {
  let guard: UnsavedChanges | null = null;
  const guardClose = (next: UnsavedChanges | null) => {
    guard = next;
  };
  return {
    id: `mapping:${id}`,
    group: msg('frame.group.design'),
    title: id.slice(id.lastIndexOf('/') + 1),
    action: 'module.call',
    render: () => (
      <Studio
        mappingId={id}
        api={api}
        browserState={browserState}
        openOn={options.editing ? 'signature' : undefined}
        startEditing={options.editing}
        guardClose={guardClose}
      />
    ),
    unsaved: () => guard,
  };
}

/** The id of the mapping in the file at `path`: the path without `.mapping.json`. */
const idOf = (path: string) => path.replace(/\.mapping\.json$/, '');

/**
 * The kind of artifact mapping over the studio's API, keeping the viewer's preferences in `browserState`: a mapping
 * opens in the studio's tab by its id, and its change is read by its target fields; New mapping creates one and opens
 * it in edit mode on its Signature tab; the list is every mapping file of the working folder, as the service lists it.
 */
export function mappingKind(api: ApiClient, browserState: BrowserStateStore): ArtifactKind {
  function NewMappingFile({ open, ...props }: NewArtifactProps) {
    return <NewMapping api={api} {...props} openMapping={(id) => open(mappingTab(api, browserState, id, { editing: true }))} />;
  }
  return {
    kind: 'mapping',
    open: (path) => mappingTab(api, browserState, idOf(path)),
    Change: MappingChange,
    New: NewMappingFile,
    list: async (signal) =>
      (await api.listMappings(signal)).mappings.map(({ id }) => ({ path: `${id}.mapping.json`, kind: 'mapping', id: id.slice(id.lastIndexOf('/') + 1) })),
  };
}
