import { PartitionOutlined } from '@ant-design/icons';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Page } from '../Page';
import type { Screen, UnsavedChanges } from '../screens';
import type { ApiClient } from './api/client';
import { MappingList } from './list/MappingList';
import { Studio } from './studio/Studio';
import type { BrowserStateStore } from './studio/browser-state/browser-state';

export interface MappingTabOptions {
  /** Opens the mapping in edit mode on the Signature tab, as Create does. */
  editing?: boolean;
  /** Told after each save of the mapping. */
  onSaved?: () => void;
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
    group: 'Design',
    title: id.slice(id.lastIndexOf('/') + 1),
    render: () => (
      <Studio
        mappingId={id}
        api={api}
        browserState={browserState}
        openOn={options.editing ? 'signature' : undefined}
        startEditing={options.editing}
        onSaved={options.onSaved}
        guardClose={guardClose}
      />
    ),
    unsaved: () => guard,
  };
}

/**
 * The Mappings screen over `api`: the workspace's mappings, each one opening in a tab of its own, and a mapping just
 * created opening in edit mode on its Signature tab. The viewer's preferences are kept in `browserState`. A save in
 * any mapping's tab brings the table up to date.
 */
export function mappingsScreen(api: ApiClient, browserState: BrowserStateStore): Screen {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const onSaved = () => void queryClient.invalidateQueries({ queryKey: ['mappings'] });
  return {
    id: 'mappings',
    group: 'Design',
    icon: <PartitionOutlined />,
    description: 'Design and test message mappings in the working folder.',
    title: 'Mappings',
    render: (open) => (
      <QueryClientProvider client={queryClient}>
        <Page title={false}>
          <MappingList
            api={api}
            onOpen={(id) => open(mappingTab(api, browserState, id, { onSaved }))}
            onCreated={(id) => open(mappingTab(api, browserState, id, { editing: true, onSaved }))}
          />
        </Page>
      </QueryClientProvider>
    ),
  };
}
