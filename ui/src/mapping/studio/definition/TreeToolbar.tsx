import { Button, Flex, Space } from 'antd';
import {
  DownOutlined,
  UpOutlined,
  TableOutlined,
  DownloadOutlined,
  FilterOutlined,
  NodeIndexOutlined,
  SearchOutlined,
  BorderOutlined,
  CheckSquareOutlined,
} from '@ant-design/icons';
import { useText } from '../../../language';
import { FILTERS, type Side } from './definition-model';
import { Tool, ToolMenu, type ToolMenuItem } from '../shared/Tool';

export type ViewMode = 'Tree' | 'Table' | 'Text';

export const VIEW_MODES: readonly ViewMode[] = ['Tree', 'Table', 'Text'];

/** The id of the name of each view. */
const VIEW_NAMES: Readonly<Record<ViewMode, string>> = { Tree: 'studio.view.tree', Table: 'studio.view.table', Text: 'studio.view.text' };

/** The id of the name of each change of the selection. */
const SELECTION_NAMES: Readonly<Record<SelectionChange, string>> = {
  all: 'studio.selection.all',
  none: 'studio.selection.none',
  inverse: 'studio.selection.inverse',
};

/** Whether the tree filter is on, and which of `FILTERS` it applies. */
export interface TreeFilter {
  active: boolean;
  kind: number;
}

/** How Select All, Select None and Inverse Selection change the selected fields. */
export type SelectionChange = 'all' | 'none' | 'inverse';

interface TreeToolbarProps {
  side: Side;
  mode: ViewMode;
  onMode: (mode: ViewMode) => void;
  fullPath: boolean;
  onPath: () => void;
  /** Expand Tree, then Collapse Tree after it: what `expand` does next. */
  collapseNext: boolean;
  expand: () => void;
  filter: TreeFilter;
  chooseFilter: (kind: number) => void;
  onToggleFilter: () => void;
  searchOpen: boolean;
  onSearch: () => void;
  onDownload: () => void;
  mappingColumn: boolean;
  onMapping: () => void;
  selectionColumn: boolean;
  onSelection: (shown: boolean) => void;
  onSelectionChange: (change: SelectionChange) => void;
}

/**
 * A message pane's tools: the view switch; Expand or Collapse Tree, Show Full Path and Filter Tree with its options in
 * the tree and table views; on the target, the mapping column and the selection column with its options; and in every
 * view the search and Save Document Source.
 */
export function TreeToolbar(props: TreeToolbarProps) {
  const { side, mode, onMode, fullPath, onPath, collapseNext, expand, filter, chooseFilter, onToggleFilter, searchOpen, onSearch, onDownload } = props;
  const { mappingColumn, onMapping, selectionColumn, onSelection, onSelectionChange } = props;
  const target = side === 'target';
  const { t } = useText();
  const filterItems: ToolMenuItem[] = [
    { label: filter.active ? t('studio.filter.off') : t('studio.filter.on'), run: onToggleFilter },
    ...(target ? FILTERS : FILTERS.slice(0, 2)).map((name, i) => ({ label: t(name), run: () => chooseFilter(i), checked: i === filter.kind })),
  ];
  const selectionItems: ToolMenuItem[] = [
    { label: selectionColumn ? t('studio.selection.hide') : t('studio.selection.show'), run: () => onSelection(!selectionColumn) },
    ...(['all', 'none', 'inverse'] as const).map((change) => ({ label: t(SELECTION_NAMES[change]), run: () => onSelectionChange(change) })),
  ];
  return (
    <Flex wrap align="center" gap="small">
      <Space.Compact role="group" aria-label={t('studio.tree.view', { side })}>
        {VIEW_MODES.map((name) => (
          <Button key={name} size="small" type={mode === name ? 'primary' : 'default'} aria-pressed={mode === name} onClick={() => onMode(name)}>
            {t(VIEW_NAMES[name])}
          </Button>
        ))}
      </Space.Compact>
      <Flex wrap align="center" role="toolbar" aria-label={t('studio.tree.tools', { side })}>
        {mode !== 'Text' && (
          <>
            <Tool label={collapseNext ? t('studio.tree.collapse') : t('studio.tree.expand')} icon={collapseNext ? UpOutlined : DownOutlined} onClick={expand} />
            <Tool label={t('studio.tree.fullPath')} icon={NodeIndexOutlined} pressed={fullPath} onClick={onPath} />
            <Tool label={t('studio.filter')} icon={FilterOutlined} pressed={filter.active} onClick={onToggleFilter} />
            <ToolMenu label={t('studio.filter.options')} items={filterItems} />
            {target && (
              <>
                <Tool label={t('studio.tree.mappingColumn')} icon={TableOutlined} pressed={mappingColumn} onClick={onMapping} />
                <Tool
                  label={t('studio.selection.show')}
                  icon={selectionColumn ? CheckSquareOutlined : BorderOutlined}
                  pressed={selectionColumn}
                  onClick={() => onSelection(!selectionColumn)}
                />
                <ToolMenu label={t('studio.selection.options')} items={selectionItems} />
              </>
            )}
          </>
        )}
        <Tool label={searchOpen ? t('studio.search.close') : t('studio.search.open')} icon={SearchOutlined} pressed={searchOpen} onClick={onSearch} />
        <Tool label={t('studio.tree.download')} icon={DownloadOutlined} onClick={onDownload} />
      </Flex>
    </Flex>
  );
}
