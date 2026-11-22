import { Button, Card, Flex, Modal } from 'antd';
import { BranchesOutlined, CodeOutlined, FolderOpenOutlined, LinkOutlined, GroupOutlined, DeleteOutlined } from '@ant-design/icons';
import { useState } from 'react';
import type { BrowserStateStore } from '../browser-state/browser-state';
import type { FieldPath, MappingModel } from '../../generated/mapping-model';
import { localPath } from '../../model/field-path';
import { InstanceTools, type InstanceToolsProps } from '../mock/InstanceTools';
import { PaneSizeTools } from '../shared/PaneSizeTools';
import { readPanelPreference, savePanelPreference } from '../shared/panel-preferences';
import type { SplitView } from '../shared/SplitLayout';
import { Tool, ToolMenu, type ToolMenuItem } from '../shared/Tool';
import { expressionText } from './definition-model';

/** Which field connections the gutter between the panes shows: none, those into `targets`, or all (`null`). */
export interface DependencyView {
  visible: boolean;
  targets: FieldPath[] | null;
}

const isDependencyView = (value: unknown): value is DependencyView =>
  typeof value === 'object' &&
  value !== null &&
  typeof (value as DependencyView).visible === 'boolean' &&
  ((value as DependencyView).targets === null ||
    (Array.isArray((value as DependencyView).targets) && (value as DependencyView).targets!.every((p) => typeof p === 'string')));

/** The Dependencies view of a mapping, kept per mapping and user; `change` keeps a new one. */
export function useDependencies(browserState: BrowserStateStore, mappingId: string) {
  const key = `definition.${mappingId}.dependencies`;
  const [view, setView] = useState<DependencyView>(() => readPanelPreference(browserState, key, { visible: false, targets: null }, isDependencyView));
  const change = (visible: boolean, targets: FieldPath[] | null = null) => {
    const next = { visible, targets };
    setView(next);
    savePanelPreference(browserState, key, next);
  };
  /** The Dependencies menu over `targets`: Show those, Show All, Hide All. */
  const items = (targets: FieldPath[]): ToolMenuItem[] => [
    { label: 'Show', run: () => change(true, targets) },
    { label: 'Show All', run: () => change(true) },
    { label: 'Hide All', run: () => change(false), disabled: !view.visible },
  ];
  return { view, change, items };
}

export interface DefinitionBarProps {
  editing: boolean;
  dataFlow: boolean;
  onDataFlow: (dataFlow: boolean) => void;
  view: SplitView;
  onView: (view: SplitView) => void;
  /** Where the messages are chosen: the Signature tab. */
  onChooseMessages: () => void;
  onClearMapping: () => void;
  /** The Dependencies tool: show the connections into the selected target field. */
  onDependencies: () => void;
  dependencyItems: ToolMenuItem[];
}

/** The Definition tab's tool bar: messages, Clear Mapping, the data flow or text view, Dependencies and the split. */
export function DefinitionBar({ editing, dataFlow, onDataFlow, view, onView, onChooseMessages, onClearMapping, onDependencies, dependencyItems }: DefinitionBarProps) {
  return (
    <Flex wrap align="center" gap="var(--ant-size-xxs)" role="toolbar" aria-label="Definition actions">
      <ToolMenu
        label="Select Message"
        icon={FolderOpenOutlined}
        disabled={!editing}
        items={[
          { label: 'Source Message', run: onChooseMessages },
          { label: 'Target Message', run: onChooseMessages },
        ]}
      />
      <Tool label="Map Selected Fields and Substructures if Names Are Identical" icon={GroupOutlined} disabled onClick={() => undefined} />
      <Tool label="Clear Mapping" icon={DeleteOutlined} disabled={!editing} onClick={onClearMapping} />
      <Tool label="Data Flow Editor" icon={BranchesOutlined} pressed={dataFlow} onClick={() => onDataFlow(true)} />
      <Tool label="Text Preview" icon={CodeOutlined} pressed={!dataFlow} onClick={() => onDataFlow(false)} />
      <Tool label="Dependencies" icon={LinkOutlined} onClick={onDependencies} />
      <ToolMenu label="Dependencies options" items={dependencyItems} />
      {dataFlow && <PaneSizeTools upper pane="message trees" label="Message tree layout" view={view} onChange={onView} />}
    </Flex>
  );
}

/**
 * The Mapping expression header: its title and target field, the queue test input (the test instance tools, a preview,
 * without Generate; a file must parse) and the pane size tools.
 */
export function ExpressionHeader({
  targetField,
  view,
  onView,
  instances,
}: {
  targetField: FieldPath | null;
  view: SplitView;
  onView: (view: SplitView) => void;
  instances: Omit<InstanceToolsProps, 'validateFile' | 'selectLabel'>;
}) {
  return (
    <Flex wrap align="center" gap="middle" role="toolbar" aria-label="Mapping expression">
      <Flex align="center" gap="small">
        <div>
          <h2>
            <BranchesOutlined aria-hidden /> Mapping expression
          </h2>
        </div>
        <div>
          <code title={targetField ?? undefined}>{targetField === null ? '' : `/${localPath(targetField)}`}</code>
        </div>
      </Flex>
      <Flex role="group" aria-label="Queue test input" align="center" gap="small">
        <InstanceTools {...instances} selectLabel="Queue test input" validateFile />
      </Flex>
      <div style={{ marginInlineStart: 'auto' }}>
        <PaneSizeTools pane="mapping expression" label="Expression layout" view={view} onChange={onView} />
      </div>
    </Flex>
  );
}

/** Text Preview: each mapped target field and its expression; a click opens it in the data flow editor. */
export function TextPreview({ model, onOpen }: { model: MappingModel; onOpen: (targetField: FieldPath) => void }) {
  const mapped = Object.entries(model.targetFieldMappings ?? {}).filter(([, mapping]) => expressionText(mapping) !== '');
  return (
    <section aria-label="Text Preview">
      <Card size="small" title={<h2>Text Preview</h2>}>
        {mapped.length ? (
          mapped.map(([path, mapping]) => (
            <div key={path}>
              <Button type="link" onClick={() => onOpen(path)}>
                <strong>/{localPath(path)}</strong> = {expressionText(mapping)}
              </Button>
            </div>
          ))
        ) : (
          <p>No mapped fields.</p>
        )}
      </Card>
    </section>
  );
}

/** The Clear Mapping dialog: asks before it clears the expressions of all target fields. */
export function ClearMapping({ onClear, onClose }: { onClear: () => void; onClose: () => void }) {
  return (
    <Modal open title="Clear Mapping" onCancel={onClose} onOk={onClear} okText="Clear mapping" okButtonProps={{ danger: true, icon: <DeleteOutlined aria-hidden /> }}>
      Clear the expressions for all target fields in this local mapping?
    </Modal>
  );
}
