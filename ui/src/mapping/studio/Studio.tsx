import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useText } from '../../language';
import { ReadSpinner } from '../../ReadSpinner';
import type { UnsavedChanges } from '../../screens';
import { isBusy, type ApiClient, type StoredMapping } from '../api/client';
import { newAuthoringId } from '../model/authoring-id';
import { RefusedMapping } from './RefusedMapping';
import type { BrowserStateStore } from './browser-state/browser-state';
import { createWorkingCopyStore, type StudioTab } from './store/working-copy';

export interface StudioProps {
  /** The mapping's id: its workspace path without the file suffix. */
  mappingId: string;
  /** The port to the studio's API. */
  api: ApiClient;
  /** The port for viewer preferences. */
  browserState: BrowserStateStore;
  /** The tab to open on, as Create opens on Signature; otherwise the tab the mapping was last left on. */
  openOn?: StudioTab;
  /** Opens the mapping in edit mode, as Create does; otherwise it opens in view mode. */
  startEditing?: boolean;
  /** Told after each save the API accepted. */
  onSaved?: () => void;
  /** Given what saves the mapping while it has unsaved changes, so that its tab can save them before it closes, and null when it has none. */
  guardClose?: (changes: UnsavedChanges | null) => void;
}

/** The store of an open mapping, its ports wired to `api`. */
export function studioStore(api: ApiClient, mappingId: string, opened: StoredMapping) {
  return createWorkingCopyStore(opened, {
    newId: () => newAuthoringId(),
    save: (model, etag) => api.saveMapping(mappingId, model, etag),
    load: () => api.getMapping(mappingId),
    compile: (model, signal) => api.compile(mappingId, model, signal).then((compiled) => compiled.diagnostics),
  });
}

/**
 * The Mapping studio of one mapping, over injected ports with its own query client: it loads the mapping, shows a
 * refusal with Retry, and opens the loaded mapping on the tab `openOn` names, else on the one it was last left on.
 */
export function Studio(props: StudioProps) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            // A load the node answered 503 busy is sent again, up to 5 times, a quarter of a second to 2 s apart: an opening
            // studio asks for the mapping, the catalogue and both messages at once, which on a node that has just started
            // can take more calls of the mapping module than it runs together.
            retry: (failures, error) => failures < 5 && isBusy(error),
            retryDelay: (failures) => Math.min(250 * 2 ** failures, 2000),
            // the loaded mapping is the baseline's source; a refetch must not replace it behind the working copy
            staleTime: Infinity,
            refetchOnWindowFocus: false,
          },
        },
      }),
  );
  return (
    <QueryClientProvider client={queryClient}>
      <Loader {...props} />
    </QueryClientProvider>
  );
}

function Loader(props: StudioProps) {
  const { mappingId, api } = props;
  const { t } = useText();
  const mapping = useQuery({
    queryKey: ['mapping', mappingId],
    queryFn: async ({ signal }) => {
      const [opened, frame] = await Promise.all([api.getMapping(mappingId, signal), import('./StudioFrame')]);
      return { opened, frame };
    },
  });
  if (mapping.isSuccess) return <Opened {...props} {...mapping.data} />;
  return (
    <section>
      {mapping.isPending ? (
        <div role="status">
          <ReadSpinner size="small" /> {t('studio.opening', { mappingId })}
        </div>
      ) : (
        <RefusedMapping error={mapping.error} retrying={mapping.isFetching} onRetry={() => void mapping.refetch()} />
      )}
    </section>
  );
}

function Opened({ mappingId, api, browserState, openOn, startEditing, onSaved, guardClose, opened, frame }: StudioProps & { opened: StoredMapping; frame: typeof import('./StudioFrame') }) {
  const { StudioFrame, lastTabKey, openingTab } = frame;
  const [store] = useState(() => {
    const created = studioStore(api, mappingId, opened);
    created.getState().setTab(openOn ?? openingTab(browserState.read(lastTabKey(mappingId))));
    if (startEditing) created.getState().startEditing();
    return created;
  });
  return <StudioFrame mappingId={mappingId} api={api} store={store} browserState={browserState} onSaved={onSaved} guardClose={guardClose} />;
}
