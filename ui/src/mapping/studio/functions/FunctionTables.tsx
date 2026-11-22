import { Button, Card, Divider, Flex, Input, Select } from 'antd';
import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  CopyOutlined,
  ScissorOutlined,
  SnippetsOutlined,
  PlusOutlined,
  SearchOutlined,
  DeleteOutlined,
} from '@ant-design/icons';
import { useId, useState, type ReactNode } from 'react';
import {
  INITIAL_IMPORTS,
  JAVA_TYPES,
  moveRow,
  nameIssue,
  variable,
  type Archive,
  type FunctionsState,
  type Udf,
  type Variable,
  type VariableKind,
} from '../mock/function-model';
import { Tool } from '../shared/Tool';
import { ArchiveCatalog } from './ArchiveCatalog';

/** Insert, Delete, Move up and Move down for the rows of a table, and any further tool items after a separator. */
export function RowTools(props: {
  label: string;
  onAdd: () => void;
  onRemove: () => void;
  onMove: (delta: number) => void;
  canRemove: boolean;
  canUp: boolean;
  canDown: boolean;
  disabled?: boolean;
  children?: ReactNode;
}) {
  const { label, onAdd, onRemove, onMove, canRemove, canUp, canDown, disabled = false, children } = props;
  return (
    <Flex align="center" gap="small" wrap role="toolbar" aria-label={label}>
      <Tool label="Insert row" icon={PlusOutlined} disabled={disabled} onClick={onAdd} />
      <Tool label="Delete row" icon={DeleteOutlined} disabled={disabled || !canRemove} onClick={onRemove} />
      <Divider vertical />
      <Tool label="Move up" icon={ArrowUpOutlined} disabled={disabled || !canUp} onClick={() => onMove(-1)} />
      <Tool label="Move down" icon={ArrowDownOutlined} disabled={disabled || !canDown} onClick={() => onMove(1)} />
      {children && (
        <>
          <Divider vertical />
          {children}
        </>
      )}
    </Flex>
  );
}

/**
 * A name field that commits on blur or Enter when the name is a Java identifier not in `taken`, and says why not
 * otherwise; Escape drops the draft.
 */
export function NameEditor(props: {
  id?: string;
  value: string;
  label: string;
  taken: readonly string[];
  disabled?: boolean;
  onCommit: (name: string) => void;
}) {
  const { id, value, label, taken, disabled, onCommit } = props;
  const [draft, setDraft] = useState<string | null>(null);
  const [error, setError] = useState('');
  const helper = useId();
  function commit() {
    if (draft === null) return;
    const name = draft.trim();
    const issue = nameIssue(name, taken);
    if (issue) {
      setError(issue);
      return;
    }
    onCommit(name);
    setDraft(null);
    setError('');
  }
  return (
    <>
      <Input
        id={id}
        aria-label={label}
        status={error ? 'error' : undefined}
        aria-invalid={!!error}
        aria-describedby={error ? helper : undefined}
        disabled={disabled}
        value={draft ?? value}
        onChange={(event) => {
          const next = event.target.value;
          setError('');
          setDraft(next);
        }}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') e.currentTarget.blur();
          if (e.key === 'Escape') {
            setDraft(null);
            setError('');
          }
        }}
      />
      {error && (
        <span className="studio-field-error" id={helper} role="alert">
          {error}
        </span>
      )}
    </>
  );
}

/** The signature variables of a function or lifecycle area; a lifecycle area has box parameters only. */
export function Variables({
  fn,
  onChange,
  lifecycle,
  disabled,
}: {
  fn: Pick<Udf, 'variables'>;
  onChange: (variables: Variable[]) => void;
  lifecycle: boolean;
  disabled: boolean;
}) {
  const [selected, select] = useState<string | null>(null);
  const rows = fn.variables;
  const index = rows.findIndex((v) => v.id === selected);
  const patch = (id: string, changes: Partial<Variable>) => onChange(rows.map((v) => (v.id === id ? { ...v, ...changes } : v)));
  function add() {
    const v = variable(lifecycle ? 'Parameter' : 'Argument', rows);
    const at = index < 0 ? rows.length : index + 1;
    onChange([...rows.slice(0, at), v, ...rows.slice(at)]);
    select(v.id);
  }
  const names = rows.map((r) => r.name);
  const headings = ['Type', 'Name', 'Java Type', 'Title', ...(lifecycle ? [] : ['Description'])];
  return (
    <Flex vertical role="group" aria-label="Signature Variables">
      <h3>Signature Variables</h3>

      <RowTools
        label="Variable actions"
        disabled={disabled}
        onAdd={add}
        canRemove={index >= 0}
        canUp={index > 0}
        canDown={index >= 0 && index < rows.length - 1}
        onRemove={() => {
          onChange(rows.filter((v) => v.id !== selected));
          select(null);
        }}
        onMove={(delta) => onChange([...moveRow(rows, index, delta)])}
      />

      <table className="studio-edit-grid" aria-label="Signature variables" role="table">
        <thead>
          <tr>
            {headings.map((s) => (
              <th scope="col" key={s}>
                {s}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((v) => (
            <tr key={v.id} data-selected={v.id === selected} onFocus={() => select(v.id)} onClick={() => select(v.id)}>
              <td>
                {lifecycle ? (
                  'Parameter'
                ) : (
                  <Select
                    virtual={false}
                    aria-label="Variable kind"
                    value={v.kind}
                    disabled={disabled}
                    onChange={(value) => {
                      const kind = value as VariableKind;
                      patch(v.id, { kind, javaType: (JAVA_TYPES[kind] as readonly string[]).includes(v.javaType) ? v.javaType : 'String' });
                    }}
                    options={Object.keys(JAVA_TYPES).map((k) => ({ value: k, label: k }))}
                  />
                )}
              </td>
              <td>
                <NameEditor
                  label="Variable name"
                  value={v.name}
                  disabled={disabled}
                  taken={['run', 'out', ...names.filter((n) => n !== v.name)]}
                  onCommit={(name) => patch(v.id, { name })}
                />
              </td>
              <td>
                <Select
                  virtual={false}
                  aria-label="Java type"
                  value={v.javaType}
                  disabled={disabled}
                  onChange={(javaType) => patch(v.id, { javaType })}
                  options={JAVA_TYPES[v.kind].map((t) => ({ value: t, label: t }))}
                />
              </td>
              <td>
                <Input aria-label="Variable title" value={v.title} disabled={disabled} onChange={({ target: { value: title } }) => patch(v.id, { title })} />
              </td>
              {!lifecycle && (
                <td>
                  <Input
                    aria-label="Variable description"
                    value={v.description ?? ''}
                    disabled={disabled}
                    onChange={({ target: { value: description } }) => patch(v.id, { description })}
                  />
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </Flex>
  );
}

const DEPENDENCY_VIEWS = ['Import Instructions', 'Archives Used'] as const;
const EMPTY_ARCHIVE: Archive = { name: '', namespace: '' };

/**
 * The import instructions and archives of the mapping's functions: the initial import instructions are fixed, the ones
 * added after them editable; an archive and its namespace are chosen from the catalogue, and an archive row can be cut,
 * copied and pasted.
 */
export function Dependencies({
  state,
  edit,
  disabled,
}: {
  state: FunctionsState;
  edit: (change: (current: FunctionsState) => FunctionsState) => void;
  disabled: boolean;
}) {
  const [view, setView] = useState<(typeof DEPENDENCY_VIEWS)[number]>('Import Instructions');
  const [index, select] = useState(-1);
  const [picker, setPicker] = useState<{ row: number; kind: 'archive' | 'namespace' } | null>(null);
  const [clip, setClip] = useState<Archive | null>(null);
  const archive = view === 'Archives Used';
  const rows: readonly (string | Archive)[] = archive ? state.archives : state.imports;
  const valid = index >= 0 && index < rows.length;
  const editable = valid && (archive || index >= INITIAL_IMPORTS.length);
  const write = (list: readonly (string | Archive)[]) =>
    edit((current) => (archive ? { ...current, archives: list as Archive[] } : { ...current, imports: list as string[] }));
  const replace = (at: number, value: string | Archive) => write(rows.map((r, i) => (i === at ? value : r)));
  function add() {
    const at = editable ? index + 1 : rows.length;
    write([...rows.slice(0, at), archive ? EMPTY_ARCHIVE : '', ...rows.slice(at)]);
    select(at);
    if (!archive) requestAnimationFrame(() => document.querySelector<HTMLElement>(`[aria-label="Import instruction ${at + 1}"]`)?.focus());
  }
  function move(delta: number) {
    write(moveRow(rows, index, delta));
    select(index + delta);
  }
  const current = state.archives[index];
  return (
    <Card role="region" aria-label="Function dependencies">
      <Flex vertical gap="middle">
        <Flex role="group" aria-label="Function dependencies view">
          {DEPENDENCY_VIEWS.map((t) => (
            <Button
              key={t}
              size="small"
              type={view === t ? 'primary' : 'default'}
              aria-pressed={view === t}
              onClick={() => {
                setView(t);
                select(-1);
              }}
            >
              {t}
            </Button>
          ))}
        </Flex>

        <RowTools
          label={archive ? 'Archive actions' : 'Import actions'}
          disabled={disabled}
          onAdd={add}
          canRemove={editable}
          canUp={editable && index > (archive ? 0 : INITIAL_IMPORTS.length)}
          canDown={editable && index < rows.length - 1}
          onRemove={() => {
            write(rows.filter((_, i) => i !== index));
            select(-1);
          }}
          onMove={move}
        >
          {archive && (
            <>
              <Tool
                label="Cut Object"
                icon={ScissorOutlined}
                disabled={disabled || !current?.name}
                onClick={() => {
                  setClip(current!);
                  replace(index, EMPTY_ARCHIVE);
                }}
              />
              <Tool label="Copy Object" icon={CopyOutlined} disabled={!current?.name} onClick={() => setClip(current!)} />
              <Tool label="Paste Object" icon={SnippetsOutlined} disabled={disabled || !current || !clip} onClick={() => replace(index, { ...clip! })} />
            </>
          )}
        </RowTools>

        <table className="studio-edit-grid" aria-label={view} role="table">
          <thead>
            <tr>
              <th scope="col">{archive ? 'Name' : 'import'}</th>
              {archive && <th scope="col">Namespace</th>}
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i} data-selected={index === i} onClick={() => select(i)} onFocus={() => select(i)}>
                {typeof r === 'string' ? (
                  <td>
                    {i < INITIAL_IMPORTS.length ? (
                      <code>{r}</code>
                    ) : (
                      <Input
                        readOnly={disabled}
                        aria-label={`Import instruction ${i + 1}`}
                        placeholder="package.Class"
                        value={r}
                        onChange={({ target: { value: value } }) => replace(i, value)}
                      />
                    )}
                  </td>
                ) : (
                  (['name', 'namespace'] as const).map((field) => (
                    <td key={field}>
                      <Button
                        type="link"
                        icon={<SearchOutlined />}
                        iconPlacement="end"
                        disabled={disabled}
                        aria-label={`Choose archive ${field} ${i + 1}`}
                        onClick={() => setPicker({ row: i, kind: field === 'name' ? 'archive' : 'namespace' })}
                      >
                        {r[field] || 'Choose…'}
                      </Button>
                    </td>
                  ))
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </Flex>

      {picker && (
        <ArchiveCatalog
          kind={picker.kind}
          close={() => setPicker(null)}
          onApply={(entry) => {
            replace(picker.row, picker.kind === 'namespace' ? { ...state.archives[picker.row]!, namespace: entry.name } : entry);
            setPicker(null);
          }}
        />
      )}
    </Card>
  );
}
