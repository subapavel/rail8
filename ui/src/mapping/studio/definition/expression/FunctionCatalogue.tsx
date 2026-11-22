import { Badge, Button, Flex, Input, Tag } from 'antd';
import { SettingOutlined } from '@ant-design/icons';
import { useMemo, useState } from 'react';
import type { FunctionCatalogue as Catalogue } from '../../../api/client';
import { deviationNote } from './fidelity';
import { drilldown, type DrilldownFunction } from './function-groups';

/** The drag type of a function dragged from the drilldown onto the canvas; its data is the catalogue name. */
export const functionDragType = 'application/x-mapping-function';

/** What a function the catalogue lacks says, and the follow-up issue that adds it: a preview, or not available yet. */
const unavailable = (fn: DrilldownFunction) =>
  fn.preview === undefined ? `Not available yet (#${fn.issue})` : `Preview: kept in this browser only, never in the mapping file (#${fn.issue})`;

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
  const groups = useMemo(() => drilldown(catalogue), [catalogue]);
  const filter = query.trim().toLowerCase();
  const shown = groups.map(({ group, functions }) => ({
    group,
    entries: functions.filter((fn) => [group, fn.name].some((value) => value.toLowerCase().includes(filter))),
  }));
  return (
    <Flex vertical gap="small" role="region" aria-label="Function catalogue">
      <div>
        <Input
          allowClear
          aria-label="Search functions"
          placeholder="Search functions"
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setCollapsedResults({});
          }}
        />
      </div>
      <div>
        {shown.map(({ group, entries }) => {
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
                {group} <Badge count={entries.length} overflowCount={Infinity} color="var(--ant-color-text-secondary)" />
              </Button>
              {open && (
                <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                  {entries.map((fn) => {
                    const preview = fn.preview !== undefined && addPreview !== undefined;
                    const lacking = fn.fn === undefined && !preview;
                    const off = disabled || lacking;
                    // a catalogue function is described by its deviation note, if any; one the catalogue lacks by why it is not there
                    const description = fn.fn === undefined ? unavailable(fn) : deviationNote(catalogue[fn.fn]!.fidelity);
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
                          {fn.overload !== undefined && <small> {fn.overload} inputs</small>}
                          {(fn.configurable || preview) && (
                            <>
                              {' '}
                              <SettingOutlined aria-label="Configurable" />
                            </>
                          )}
                          {preview && (
                            <>
                              {' '}
                              <Tag color="purple">Preview</Tag>
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
        {shown.every(({ entries }) => entries.length === 0) && <p>No matching functions.</p>}
      </div>
    </Flex>
  );
}
