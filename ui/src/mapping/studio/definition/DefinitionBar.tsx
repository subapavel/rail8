import { Button, Card, Flex } from 'antd';
import { MountedModal } from '../../../MountedModal';
import { BranchesOutlined, CodeOutlined, FolderOpenOutlined, LinkOutlined, GroupOutlined, DeleteOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useText } from '../../../language';
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
  const { t } = useText();
  const change = (visible: boolean, targets: FieldPath[] | null = null) => {
    const next = { visible, targets };
    setView(next);
    savePanelPreference(browserState, key, next);
  };
  /** The Dependencies menu over `targets`: Show those, Show All, Hide All. */
  const items = (targets: FieldPath[]): ToolMenuItem[] => [
    { label: t('studio.dependencies.show'), run: () => change(true, targets) },
    { label: t('studio.dependencies.showAll'), run: () => change(true) },
    { label: t('studio.dependencies.hideAll'), run: () => change(false), disabled: !view.visible },
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
  const { t } = useText();
  return (
    <Flex wrap align="center" gap="var(--ant-size-xxs)" role="toolbar" aria-label={t('studio.definition.actions')}>
      <ToolMenu
        label={t('studio.definition.selectMessage')}
        icon={FolderOpenOutlined}
        disabled={!editing}
        items={[
          { label: t('studio.definition.sourceMessage'), run: onChooseMessages },
          { label: t('studio.definition.targetMessage'), run: onChooseMessages },
        ]}
      />
      <Tool label={t('studio.definition.mapIdentical')} icon={GroupOutlined} disabled onClick={() => undefined} />
      <Tool label={t('studio.definition.clear')} icon={DeleteOutlined} disabled={!editing} onClick={onClearMapping} />
      <Tool label={t('studio.definition.dataFlow')} icon={BranchesOutlined} pressed={dataFlow} onClick={() => onDataFlow(true)} />
      <Tool label={t('studio.definition.textPreview')} icon={CodeOutlined} pressed={!dataFlow} onClick={() => onDataFlow(false)} />
      <Tool label={t('studio.dependencies')} icon={LinkOutlined} onClick={onDependencies} />
      <ToolMenu label={t('studio.dependencies.options')} items={dependencyItems} />
      {dataFlow && (
        <PaneSizeTools
          upper
          names={{ minimize: t('studio.definition.trees.minimize'), maximize: t('studio.definition.trees.maximize') }}
          label={t('studio.definition.trees.layout')}
          view={view}
          onChange={onView}
        />
      )}
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
  const { t } = useText();
  return (
    <Flex wrap align="center" gap="middle" role="toolbar" aria-label={t('studio.expression')}>
      <Flex align="center" gap="small">
        <div>
          <h2>
            <BranchesOutlined aria-hidden /> {t('studio.expression')}
          </h2>
        </div>
        <div>
          <code title={targetField ?? undefined}>{targetField === null ? '' : `/${localPath(targetField)}`}</code>
        </div>
      </Flex>
      <Flex role="group" aria-label={t('studio.expression.input')} align="center" gap="small">
        <InstanceTools {...instances} selectLabel={t('studio.expression.input')} validateFile />
      </Flex>
      <div style={{ marginInlineStart: 'auto' }}>
        <PaneSizeTools
          names={{ minimize: t('studio.expression.minimize'), maximize: t('studio.expression.maximize') }}
          label={t('studio.expression.layout')}
          view={view}
          onChange={onView}
        />
      </div>
    </Flex>
  );
}

/** Text Preview: each mapped target field and its expression; a click opens it in the data flow editor. */
export function TextPreview({ model, onOpen }: { model: MappingModel; onOpen: (targetField: FieldPath) => void }) {
  const mapped = Object.entries(model.targetFieldMappings ?? {}).filter(([, mapping]) => expressionText(mapping) !== '');
  const { t } = useText();
  return (
    <section aria-label={t('studio.definition.textPreview')}>
      <Card size="small" title={<h2>{t('studio.definition.textPreview')}</h2>}>
        {mapped.length ? (
          mapped.map(([path, mapping]) => (
            <div key={path}>
              <Button type="link" onClick={() => onOpen(path)}>
                <strong>/{localPath(path)}</strong> = {expressionText(mapping)}
              </Button>
            </div>
          ))
        ) : (
          <p>{t('studio.definition.noMapped')}</p>
        )}
      </Card>
    </section>
  );
}

/** The Clear Mapping dialog: asks before it clears the expressions of all target fields. */
export function ClearMapping({ onClear, onClose }: { onClear: () => void; onClose: () => void }) {
  const { t } = useText();
  return (
    <MountedModal
      title={t('studio.definition.clear')}
      onCancel={onClose}
      onOk={onClear}
      okText={t('studio.definition.clear.ok')}
      okButtonProps={{ danger: true, icon: <DeleteOutlined aria-hidden /> }}
    >
      {t('studio.definition.clear.question')}
    </MountedModal>
  );
}
