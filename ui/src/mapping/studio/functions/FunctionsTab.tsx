import { Button, Card, Col, Divider, Flex, Form, Input, Row, Select, Tag } from 'antd';
import { CodeOutlined, DownloadOutlined, UploadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useId, useRef, useState, type ChangeEvent } from 'react';
import { useStore } from 'zustand';
import type { ApiClient } from '../../api/client';
import type { BrowserStateStore } from '../browser-state/browser-state';
import {
  AREAS,
  ATTRIBUTES,
  builtIn,
  declaration,
  EXECUTION_KINDS,
  metadata,
  moveRow,
  newFunction,
  unusedName,
  type AreaName,
  type ExecutionKind,
  type JavaArea,
  type Udf,
} from '../mock/function-model';
import { FUNCTIONS_ISSUE, functionsPreview } from '../mock/functions-preview';
import { PreviewBadge } from '../mock/PreviewBadge';
import { downloadFile } from '../shared/download';
import { LogDialog, ProcessingLog } from '../shared/ProcessingLog';
import { Tool } from '../shared/Tool';
import type { WorkingCopyStore } from '../store/working-copy';
import { exportSource, importSource } from './function-source';
import { Dependencies, NameEditor, RowTools, Variables } from './FunctionTables';

const JDK = 'JDK 25 (Default)';
const MONO = { fontFamily: 'var(--ant-font-family-code, monospace)' } as const;

export interface FunctionsTabProps {
  /** The mapping's id: its name names the class, its mapping package the Java package. */
  mappingId: string;
  /** The open mapping's store, read for edit mode and the messages only: the functions never enter the model. */
  store: WorkingCopyStore;
  /** Keeps the saved functions per mapping. */
  browserState: BrowserStateStore;
  /** Read for the standard functions' names, which a function may not take. */
  api: ApiClient;
}

/**
 * The Functions tab, a preview, as user-defined functions need the platform's message data model, which does not exist
 * yet: the class and package of the mapping's functions, Export and Import Source Text, the list of Java areas and
 * user-defined functions with their editor, and the import instructions and archives. The functions live in the browser
 * only (`functionsPreview`), never in the mapping file; Save keeps an edit and Discard drops it. They are edited in
 * edit mode once the mapping has both messages; otherwise they are read-only, and only Export Source Text works.
 */
export function FunctionsTab({ mappingId, store, browserState, api }: FunctionsTabProps) {
  const functions = functionsPreview(store, browserState, mappingId);
  const state = useStore(functions, (s) => s.value);
  const edit = useStore(functions, (s) => s.edit);
  const editing = useStore(store, (s) => s.editing);
  const hasMessages = useStore(store, (s) => s.workingCopy.source !== undefined && s.workingCopy.target !== undefined);
  const catalogue = useQuery({ queryKey: ['catalogue'], queryFn: () => api.getCatalogue() });
  const [selected, select] = useState<string>(ATTRIBUTES);
  const [log, setLog] = useState('');
  const [showLog, setShowLog] = useState(false);
  const file = useRef<HTMLInputElement>(null);
  const ids = useId();

  const list = state.functions;
  const index = list.findIndex((fn) => fn.id === selected);
  const isCustom = index >= 0;
  const custom: Udf | undefined = list[index];
  const active: Udf | (JavaArea & { name: AreaName; id?: undefined }) = custom ?? builtIn(state, selected as AreaName);
  const attributes = active.name === ATTRIBUTES;
  const lifecycle = !isCustom && !attributes;
  const ready = editing && hasMessages;
  const meta = metadata(mappingId);

  function change(changes: Partial<Udf>) {
    if (isCustom) edit((s) => ({ ...s, functions: s.functions.map((fn) => (fn.id === active.id ? { ...fn, ...changes } : fn)) }));
    else {
      const name = active.name as AreaName;
      edit((s) => {
        const { body, description, variables } = { ...builtIn(s, name), ...changes };
        return { ...s, javaAreas: { ...s.javaAreas, [name]: { body, description, variables } } };
      });
    }
  }
  function add() {
    const fn = newFunction(
      unusedName(
        'calculate',
        list.map((f) => f.name),
      ),
    );
    const at = index + 1;
    edit((s) => ({ ...s, functions: [...s.functions.slice(0, at), fn, ...s.functions.slice(at)] }));
    select(fn.id);
  }
  function remove() {
    edit((s) => ({ ...s, functions: s.functions.filter((fn) => fn.id !== active.id) }));
    select(list[index - 1]?.id ?? list[index + 1]?.id ?? ATTRIBUTES);
  }
  function download() {
    const filename = `${meta.className}.java`;
    downloadFile(filename, new Blob([exportSource(mappingId, state)], { type: 'text/plain;charset=utf-8' }));
    setLog(`Exported ${filename}`);
    setShowLog(true);
  }
  async function readFile(event: ChangeEvent<HTMLInputElement>) {
    const chosen = event.target.files![0]!;
    event.target.value = '';
    const text = await chosen.text();
    try {
      edit((s) => importSource(mappingId, s, text));
      setLog(`Imported source text from ${chosen.name}`);
    } catch (error) {
      setLog(`Import failed. ${(error as Error).message}`);
    }
    setShowLog(true);
  }

  const taken = [...AREAS, ...Object.keys(catalogue.data ?? {})];
  const field = (name: string) => `${ids}-${name}`;
  return (
    <Flex vertical gap="middle">
      <Card role="region" aria-label="Function class">
        <Flex align="center" gap="small" wrap>
          <dl className="studio-details">
            <div>
              <dt>Class Name</dt>
              <dd>
                <code>{meta.className}</code>
              </dd>
            </div>
            <div>
              <dt>Package Name</dt>
              <dd>
                <code>{meta.packageName || '(default package)'}</code>
              </dd>
            </div>
          </dl>
          <PreviewBadge feature="User-defined functions" issue={FUNCTIONS_ISSUE} />
          <Divider vertical />
          <Flex align="center" gap="small" wrap role="toolbar" aria-label="Java source actions">
            <Tool label="Export Source Text" icon={DownloadOutlined} onClick={download} />
            <Tool label="Import Source Text" icon={UploadOutlined} disabled={!ready} onClick={() => file.current!.click()} />
          </Flex>
          <Divider vertical />
          <Flex align="center" gap="small" wrap>
            <small aria-hidden="true">JDK Compliance</small>
            <Select virtual={false} aria-label="JDK Compliance" value={JDK} disabled={!ready} options={[{ value: JDK, label: JDK }]} />
          </Flex>
        </Flex>
        <input type="file" disabled={!ready} hidden accept=".java,text/plain" aria-label="Java source file" ref={file} onChange={(e) => void readFile(e)} />
      </Card>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={8}>
          <Flex vertical gap="middle">
            <Card role="region" aria-label="Functions and Java Areas">
              <h2>Functions and Java Areas</h2>

              <RowTools
                label="Function actions"
                disabled={!ready}
                onAdd={add}
                canRemove={isCustom}
                canUp={index > 0}
                canDown={isCustom && index < list.length - 1}
                onRemove={remove}
                onMove={(delta) => edit((s) => ({ ...s, functions: [...moveRow(s.functions, index, delta)] }))}
              />
              <Flex vertical role="list" aria-label="Functions">
                {AREAS.map((name) => (
                  <div key={name} role="listitem">
                    <Entry label={name} pressed={active.name === name} onClick={() => select(name)} />
                  </div>
                ))}
                {list.map((fn) => (
                  <div key={fn.id} role="listitem">
                    <Entry label={fn.name} name={`Select function ${fn.name}`} pressed={active.id === fn.id} onClick={() => select(fn.id)} />
                    {ready && fn.id === active.id && (
                      <Form.Item label="Method name" htmlFor={field('method')}>
                        <NameEditor
                          id={field('method')}
                          label="Method name"
                          value={fn.name}
                          taken={[...taken, ...list.filter((f) => f.id !== fn.id).map((f) => f.name)]}
                          onCommit={(name) => change({ name })}
                        />
                      </Form.Item>
                    )}
                  </div>
                ))}
              </Flex>
            </Card>

            <Dependencies state={state} edit={edit} disabled={!ready} />
          </Flex>
        </Col>
        <Col xs={24} lg={16}>
          <Card role="region" aria-label="Function editor">
            <Flex align="center" justify="space-between">
              <h2>
                <CodeOutlined aria-hidden /> {active.name}
              </h2>
              <Tag>Java</Tag>
            </Flex>

            <Flex vertical gap="middle">
              {(isCustom || active.name === 'init') && (
                <div>
                  <Form colon={false} layout="vertical" component="div" key={`properties-${selected}`}>
                    {custom && (
                      <Form.Item label="Title" htmlFor={field('title')}>
                        <Input
                          aria-label="Title"
                          id={field('title')}
                          value={custom.title}
                          disabled={!ready}
                          onChange={({ target: { value: title } }) => change({ title })}
                        />
                      </Form.Item>
                    )}
                    <Form.Item label="Description" htmlFor={field('description')}>
                      <Input
                        aria-label="Description"
                        id={field('description')}
                        value={active.description}
                        disabled={!ready}
                        onChange={({ target: { value: description } }) => change({ description })}
                      />
                    </Form.Item>
                    {custom && (
                      <>
                        <Form.Item label="Execution Kind" htmlFor={field('execution')}>
                          <Select
                            virtual={false}
                            aria-label="Execution Kind"
                            id={field('execution')}
                            value={custom.execution}
                            disabled={!ready}
                            onChange={(execution) => change({ execution: execution as ExecutionKind })}
                            options={EXECUTION_KINDS.map((m) => ({ value: m, label: m }))}
                          />
                        </Form.Item>
                        <Form.Item label="Category" htmlFor={field('category')}>
                          <Input
                            id={field('category')}
                            value={custom.category}
                            disabled={!ready}
                            onChange={({ target: { value: category } }) => change({ category })}
                          />
                        </Form.Item>
                      </>
                    )}
                  </Form>
                </div>
              )}
              {(isCustom || active.name === 'init') && (
                <div>
                  <Variables
                    disabled={!ready}
                    key={`variables-${selected}`}
                    fn={active}
                    lifecycle={lifecycle}
                    onChange={(variables) => change({ variables })}
                  />
                </div>
              )}
              <div>
                {attributes ? (
                  <p>Class attributes and shared methods are available to all functions in this mapping.</p>
                ) : (
                  <>
                    {lifecycle && <p>{active.name === 'init' ? 'Called before mapping.' : 'Called after mapping.'}</p>}
                    <pre>{declaration(active)}</pre>
                  </>
                )}
              </div>

              <Input.TextArea
                aria-label={`Source code for ${active.name}`}
                readOnly={!ready}
                spellCheck={false}
                rows={16}
                value={active.body}
                onChange={({ target: { value: body } }) => change({ body })}
                style={MONO}
              />

              {!attributes && (
                <div>
                  <pre>{'}'}</pre>
                </div>
              )}
            </Flex>
          </Card>
        </Col>
      </Row>

      {log !== '' && (
        <div>
          <ProcessingLog log={log} />
        </div>
      )}
      {showLog && <LogDialog log={log} onClose={() => setShowLog(false)} />}
    </Flex>
  );
}

/** An entry of the list of Java areas and functions: a button that selects it, pressed while it is the one shown. */
function Entry({ label, name, pressed, onClick }: { label: string; name?: string; pressed: boolean; onClick: () => void }) {
  return (
    <Button color={pressed ? 'primary' : 'default'} variant={pressed ? 'filled' : 'text'} aria-pressed={pressed} aria-label={name} icon={<CodeOutlined aria-hidden />} onClick={onClick}>
      {label}
    </Button>
  );
}
