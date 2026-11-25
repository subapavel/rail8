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
import { useText } from '../../../language';
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
  const { t } = useText();
  return (
    <Flex align="center" gap="small" wrap role="toolbar" aria-label={label}>
      <Tool label={t('studio.signature.insertRow')} icon={PlusOutlined} disabled={disabled} onClick={onAdd} />
      <Tool label={t('studio.signature.deleteRow')} icon={DeleteOutlined} disabled={disabled || !canRemove} onClick={onRemove} />
      <Divider vertical />
      <Tool label={t('studio.moveUp')} icon={ArrowUpOutlined} disabled={disabled || !canUp} onClick={() => onMove(-1)} />
      <Tool label={t('studio.moveDown')} icon={ArrowDownOutlined} disabled={disabled || !canDown} onClick={() => onMove(1)} />
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
  const texts = useText();
  const [draft, setDraft] = useState<string | null>(null);
  const [error, setError] = useState('');
  const helper = useId();
  function commit() {
    if (draft === null) return;
    const name = draft.trim();
    const issue = nameIssue(name, taken, texts);
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
  const { t } = useText();
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
  const headings = [t('studio.signature.type'), t('studio.name'), t('studio.functions.javaType'), t('studio.functions.title'), ...(lifecycle ? [] : [t('studio.description')])];
  return (
    <Flex vertical role="group" aria-label={t('studio.functions.variables')}>
      <h3>{t('studio.functions.variables')}</h3>

      <RowTools
        label={t('studio.functions.variableActions')}
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

      <table className="studio-edit-grid" aria-label={t('studio.functions.variables.table')} role="table">
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
                  t('studio.functions.kind.Parameter')
                ) : (
                  <Select
                    virtual={false}
                    aria-label={t('studio.functions.variableKind')}
                    value={v.kind}
                    disabled={disabled}
                    onChange={(value) => {
                      const kind = value as VariableKind;
                      patch(v.id, { kind, javaType: (JAVA_TYPES[kind] as readonly string[]).includes(v.javaType) ? v.javaType : 'String' });
                    }}
                    options={(Object.keys(JAVA_TYPES) as VariableKind[]).map((k) => ({ value: k, label: t(VARIABLE_KINDS[k]) }))}
                  />
                )}
              </td>
              <td>
                <NameEditor
                  label={t('studio.variable.name')}
                  value={v.name}
                  disabled={disabled}
                  taken={['run', 'out', ...names.filter((n) => n !== v.name)]}
                  onCommit={(name) => patch(v.id, { name })}
                />
              </td>
              <td>
                <Select
                  virtual={false}
                  aria-label={t('studio.functions.javaType.label')}
                  value={v.javaType}
                  disabled={disabled}
                  onChange={(javaType) => patch(v.id, { javaType })}
                  options={JAVA_TYPES[v.kind].map((type) => ({ value: type, label: type }))}
                />
              </td>
              <td>
                <Input aria-label={t('studio.functions.variableTitle')} value={v.title} disabled={disabled} onChange={({ target: { value: title } }) => patch(v.id, { title })} />
              </td>
              {!lifecycle && (
                <td>
                  <Input
                    aria-label={t('studio.functions.variableDescription')}
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

/** The id of the name of each kind of signature variable. */
const VARIABLE_KINDS: Record<VariableKind, string> = { Argument: 'studio.functions.kind.Argument', Parameter: 'studio.functions.kind.Parameter' };

/** The views of the dependencies, with the ids of their names. */
const DEPENDENCY_VIEWS = { imports: 'studio.functions.imports', archives: 'studio.functions.archives' } as const;
type DependencyView = keyof typeof DEPENDENCY_VIEWS;
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
  const { t } = useText();
  const [view, setView] = useState<DependencyView>('imports');
  const [index, select] = useState(-1);
  const [picker, setPicker] = useState<{ row: number; kind: 'archive' | 'namespace' } | null>(null);
  const [clip, setClip] = useState<Archive | null>(null);
  const archive = view === 'archives';
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
    const label = t('studio.functions.instruction', { n: at + 1 });
    if (!archive) requestAnimationFrame(() => document.querySelector<HTMLElement>(`[aria-label="${CSS.escape(label)}"]`)?.focus());
  }
  function move(delta: number) {
    write(moveRow(rows, index, delta));
    select(index + delta);
  }
  const current = state.archives[index];
  return (
    <Card role="region" aria-label={t('studio.functions.dependencies')}>
      <Flex vertical gap="middle">
        <Flex role="group" aria-label={t('studio.functions.dependencies.view')}>
          {(Object.keys(DEPENDENCY_VIEWS) as DependencyView[]).map((each) => (
            <Button
              key={each}
              size="small"
              type={view === each ? 'primary' : 'default'}
              aria-pressed={view === each}
              onClick={() => {
                setView(each);
                select(-1);
              }}
            >
              {t(DEPENDENCY_VIEWS[each])}
            </Button>
          ))}
        </Flex>

        <RowTools
          label={t(archive ? 'studio.functions.archiveActions' : 'studio.functions.importActions')}
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
                label={t('studio.signature.cut')}
                icon={ScissorOutlined}
                disabled={disabled || !current?.name}
                onClick={() => {
                  setClip(current!);
                  replace(index, EMPTY_ARCHIVE);
                }}
              />
              <Tool label={t('studio.signature.copy')} icon={CopyOutlined} disabled={!current?.name} onClick={() => setClip(current!)} />
              <Tool label={t('studio.signature.paste')} icon={SnippetsOutlined} disabled={disabled || !current || !clip} onClick={() => replace(index, { ...clip! })} />
            </>
          )}
        </RowTools>

        <table className="studio-edit-grid" aria-label={t(DEPENDENCY_VIEWS[view])} role="table">
          <thead>
            <tr>
              <th scope="col">{archive ? t('studio.name') : 'import'}</th>
              {archive && <th scope="col">{t('studio.lookup.namespace')}</th>}
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
                        aria-label={t('studio.functions.instruction', { n: i + 1 })}
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
                        aria-label={t(field === 'name' ? 'studio.functions.chooseArchive' : 'studio.functions.chooseNamespace', { n: i + 1 })}
                        onClick={() => setPicker({ row: i, kind: field === 'name' ? 'archive' : 'namespace' })}
                      >
                        {r[field] || t('studio.signature.chooseEllipsis')}
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
