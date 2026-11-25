import { Badge, Button, Flex, Input, Tag } from 'antd';
import { SettingOutlined } from '@ant-design/icons';
import { useMemo, useState } from 'react';
import { useText } from '../../../../language';
import type { FunctionCatalogue as Catalogue } from '../../../api/client';
import { deviationNote } from './fidelity';
import { drilldown, type DrilldownFunction, type Group } from './function-groups';

/** The drag type of a function dragged from the drilldown onto the canvas; its data is the catalogue name. */
export const functionDragType = 'application/x-mapping-function';

/** The id of what a function the catalogue lacks says, with the follow-up issue that adds it: a preview, or not available yet. */
const unavailable = (fn: DrilldownFunction) => (fn.preview === undefined ? 'studio.catalogue.unavailable' : 'studio.catalogue.preview');

/** The id of each group's name. */
const GROUP_NAMES: Record<Group, string> = {
  arithmetic: 'studio.group.arithmetic',
  boolean: 'studio.group.boolean',
  text: 'studio.group.text',
  conversions: 'studio.group.conversions',
  date: 'studio.group.date',
  constants: 'studio.group.constants',
  node: 'studio.group.node',
  statistic: 'studio.group.statistic',
};

/**
 * The functions drilldown over the catalogue: its groups open one by one, or all that match while a search is typed;
 * a function is added by a click or dragged onto the canvas. A catalogue function with a deviation has its note as
 * its tooltip and description, and no mark. A function the catalogue lacks is a preview function, added by a click
 * through `addPreview` when given, or else is shown disabled with "Not available yet" and its follow-up issue.
 */
export function FunctionCatalogue({
  catalogue,
  add,
  addPreview,
  disabled = false,
}: {
  catalogue: Catalogue;
  add: (fn: string) => void;
  addPreview?: (key: string) => void;
  disabled?: boolean;
}) {
  const [query, setQuery] = useState('');
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [collapsedResults, setCollapsedResults] = useState<Record<string, boolean>>({});
  const { t } = useText();
  const groups = useMemo(() => drilldown(catalogue), [catalogue]);
  const filter = query.trim().toLowerCase();
  const shown = groups.map(({ group, functions }) => ({
    group,
    name: t(GROUP_NAMES[group]),
    entries: functions.filter((fn) => [t(GROUP_NAMES[group]), fn.name].some((value) => value.toLowerCase().includes(filter))),
  }));
  return (
    <Flex vertical gap="small" role="region" aria-label={t('studio.catalogue')}>
      <div>
        <Input
          allowClear
          aria-label={t('studio.catalogue.search')}
          placeholder={t('studio.catalogue.search')}
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setCollapsedResults({});
          }}
        />
      </div>
      <div>
        {shown.map(({ group, name, entries }) => {
          if (entries.length === 0) return null;
          const open = filter ? !collapsedResults[group] : Boolean(expanded[group]);
          return (
            <section key={group}>
              <Button
                type="text"
                aria-expanded={open}
                onClick={() => {
                  if (filter) setCollapsedResults((old) => ({ ...old, [group]: open }));
                  else setExpanded((old) => ({ ...old, [group]: !open }));
                }}
              >
                {name} <Badge count={entries.length} overflowCount={Infinity} color="var(--ant-color-text-secondary)" />
              </Button>
              {open && (
                <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                  {entries.map((fn) => {
                    const preview = fn.preview !== undefined && addPreview !== undefined;
                    const lacking = fn.fn === undefined && !preview;
                    const off = disabled || lacking;
                    // a catalogue function is described by its deviation note, if any; one the catalogue lacks by why it is not there
                    const description = fn.fn === undefined ? t(unavailable(fn), { issue: fn.issue }) : deviationNote(catalogue[fn.fn]!.fidelity);
                    return (
                      <li key={fn.key}>
                        <Button
                          type="link"
                          style={lacking ? { color: 'var(--ant-color-text-disabled)', cursor: 'not-allowed' } : undefined}
                          aria-disabled={lacking || undefined}
                          disabled={disabled && !lacking}
                          title={description}
                          aria-description={description}
                          icon={<span aria-hidden="true">ƒ</span>}
                          draggable={!off && !preview}
                          onDragStart={(event) => {
                            event.dataTransfer.setData(functionDragType, fn.fn!);
                            event.dataTransfer.effectAllowed = 'copy';
                          }}
                          onClick={() => {
                            if (off) return;
                            if (preview) addPreview(fn.preview!);
                            else add(fn.fn!);
                          }}
                        >
                          {fn.name}
                          {fn.overload !== undefined && <small> {t('studio.catalogue.inputs', { count: fn.overload })}</small>}
                          {(fn.configurable || preview) && (
                            <>
                              {' '}
                              <SettingOutlined aria-label={t('studio.catalogue.configurable')} />
                            </>
                          )}
                          {preview && (
                            <>
                              {' '}
                              <Tag color="purple">{t('studio.preview')}</Tag>
                            </>
                          )}
                        </Button>
                      </li>
                    );
                  })}
                </ul>
              )}
            </section>
          );
        })}
        {shown.every(({ entries }) => entries.length === 0) && <p>{t('studio.catalogue.noMatch')}</p>}
      </div>
    </Flex>
  );
}
