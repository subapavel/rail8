import { Alert, Button, Card, Col, Divider, Flex, Input, Radio, Row, Select, Tag } from 'antd';
import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  CodeOutlined,
  CloseOutlined,
  CopyOutlined,
  BlockOutlined,
  ScissorOutlined,
  SnippetsOutlined,
  PlusOutlined,
  PartitionOutlined,
  ReloadOutlined,
  SearchOutlined,
  DeleteOutlined,
} from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { Fragment, useId, useRef, useState, type ComponentType, type ReactNode } from 'react';
import { useStore } from 'zustand';
import { msg, say, useText, type Label } from '../../../language';
import { describeError, type ApiClient } from '../../api/client';
import type { BrowserStateStore } from '../browser-state/browser-state';
import { PreviewBadge } from '../mock/PreviewBadge';
import {
  OCCURRENCES,
  PARAMETERS_ISSUE,
  SEVERAL_MESSAGES_ISSUE,
  signatureActions,
  signatureMock,
  type Occurrence,
  type PreviewParameter,
  type Side,
  type SignatureMockStore,
} from '../mock/signature-mock';
import { LogDialog, ProcessingLog } from '../shared/ProcessingLog';
import { messageName, messageNamespace, rootKind, type MessageChoice, type RootKind } from '../shared/structure-names';
import { refreshMutation, useLoadStructure } from '../shared/structure-query';
import type { WorkingCopyStore } from '../store/working-copy';
import { ROOT_KINDS } from './message-choice';
import { MessageChoiceModal, type ChoiceFailure } from './MessageChoiceModal';

/** The row id of a side's message in the mapping file, the first row of its table. */
const PRIMARY = 'message';

/** A tool of a section's toolbar: an icon button named by its label, disabled with the tab in view mode. */
interface ToolProps {
  label: string;
  icon: ComponentType;
  disabled?: boolean;
  onClick: () => void;
}

/** A toolbar of tools, in groups split by separators. */
function Tools({ label, editing, groups }: { label: string; editing: boolean; groups: ToolProps[][] }) {
  return (
    <Flex align="center" gap="small" wrap role="toolbar" aria-label={label}>
      {groups.map((group, g) => (
        <Fragment key={g}>
          {g > 0 && <Divider vertical />}
          {group.map(({ label: name, icon: Icon, disabled, onClick }) => (
            <Button key={name} type="text" aria-label={name} title={name} icon={<Icon />} disabled={!editing || disabled} onClick={onClick} />
          ))}
        </Fragment>
      ))}
    </Flex>
  );
}

/** A select of a table cell. */
function FieldSelect<T extends string>({
  label,
  value,
  options,
  disabled,
  onChange,
}: {
  label: string;
  value: T;
  options: readonly (readonly [T, string])[];
  disabled: boolean;
  onChange: (value: T) => void;
}) {
  return (
    <Select
      virtual={false}
      aria-label={label}
      value={value}
      disabled={disabled}
      onChange={(next) => onChange(next as T)}
      options={options.map(([option, text]) => ({ value: option, label: text }))}
    />
  );
}

/** A section's title with its icon, and its count on the right. */
function SectionHeader({ icon: Icon, title, count, countLabel }: { icon: ComponentType; title: ReactNode; count: number; countLabel: string }) {
  return (
    <Flex align="center" justify="space-between" gap="small">
      <h2>
        <span aria-hidden>
          <Icon />
        </span>{' '}
        {title}
      </h2>
      <Tag aria-label={countLabel}>{count}</Tag>
    </Flex>
  );
}

/** A row of a message table: the side's message in the mapping file, or a preview message after it. */
interface MessageRow {
  id: string;
  preview: boolean;
  choice?: MessageChoice;
  occurrence: Occurrence;
}

/** What the open message choice is for: a side's row, and the root kind its Type select asked for. */
interface Choosing {
  side: Side;
  row: string;
  kind?: RootKind;
}

export interface SignatureProps {
  /** The mapping's id, its workspace path; its folder is the package the bundles resolve against. */
  mappingId: string;
  api: ApiClient;
  store: WorkingCopyStore;
  browserState: BrowserStateStore;
}

/**
 * The Signature tab: the source and target message tables, side by side on a wide screen, then the parameters, all
 * editable in edit mode only. The first row of a side is its message in the mapping file; choosing it loads the structure, sets the
 * side on the working copy and refreshes it. Further messages and the parameters are preview features, a draft of the
 * edit session that Save keeps in the browser per mapping, never in the file.
 */
export function Signature({ mappingId, api, store, browserState }: SignatureProps) {
  const editing = useStore(store, (s) => s.editing);
  const mock = signatureMock(store, browserState, mappingId);
  const [clipboard, setClipboard] = useState<MessageChoice | null>(null);
  const [choosing, setChoosing] = useState<Choosing | null>(null);
  const [notice, setNotice] = useState<Label | null>(null);
  const texts = useText();
  const { t } = texts;
  const [log, setLog] = useState('');
  const [showLog, setShowLog] = useState(false);
  const pkg = mappingId.slice(0, Math.max(mappingId.lastIndexOf('/'), 0));
  const loadStructure = useLoadStructure(mappingId, api);
  // the answer is taken here, not in mutate's callbacks, so that it still applies once another tab is open
  const refresh = useMutation({ ...refreshMutation(api, mappingId, store), onSuccess: ({ sent, answer }) => void store.getState().applyRefresh(sent, answer) });

  /** Loads the structure, sets the side and refreshes the working copy; a refused or failed load is the dialog's. */
  const choosePrimary = async (side: Side, choice: MessageChoice): Promise<ChoiceFailure | null> => {
    const bundle = [...choice.bundle] as [string, ...string[]];
    try {
      const loaded = await loadStructure({ bundle, root: choice.root });
      if (loaded.kind === 'refused') return { title: t('studio.signature.notLoaded'), diagnostics: loaded.diagnostics };
      store.getState().setStructure(side, { bundle, root: choice.root, fingerprint: loaded.fingerprint });
    } catch (error) {
      const { title, diagnostics } = describeError(error);
      return { title, diagnostics };
    }
    setChoosing(null);
    refresh.mutate(undefined, { onError: (error) => setNotice(msg('studio.notice.notRefreshed', { reason: describeError(error).title })) });
    return null;
  };

  const apply = async (choice: MessageChoice): Promise<ChoiceFailure | null> => {
    const { side, row } = choosing!;
    if (row === PRIMARY) return choosePrimary(side, choice);
    signatureActions(mock).patchMessage(side, row, { choice });
    setChoosing(null);
    return null;
  };

  const chosenRow =
    choosing &&
    (choosing.row === PRIMARY ? store.getState().workingCopy[choosing.side] : mock.getState().value[choosing.side].find((m) => m.id === choosing.row)?.choice);

  return (
    <Flex vertical gap="middle">
      {notice !== null && (
        <div>
          <Alert
            type="warning"
            showIcon
            role="status"
            title={<h3>{say(texts, notice)}</h3>}
            action={<Button size="small" onClick={() => setNotice(null)} aria-label={t('studio.signature.dismiss')} type="text" icon={<CloseOutlined />} />}
          />
        </div>
      )}

      <Row gutter={[16, 16]}>
        {(['source', 'target'] as const).map((side) => (
          <Col key={side} xs={24} xxl={12}>
            <Messages
              side={side}
              pkg={pkg}
              editing={editing}
              store={store}
              mock={mock}
              clipboard={clipboard}
              setClipboard={setClipboard}
              choose={(row, kind) => setChoosing({ side, row, kind })}
            />
          </Col>
        ))}
      </Row>

      <Parameters
        store={store}
        mock={mock}
        editing={editing}
        onLog={(text) => {
          setLog(text);
          setShowLog(true);
        }}
      />

      {log !== '' && (
        <div>
          <ProcessingLog log={log} />
        </div>
      )}
      {showLog && <LogDialog log={log} onClose={() => setShowLog(false)} />}
      {choosing && (
        <MessageChoiceModal
          title={t('studio.signature.choose', { side: choosing.side })}
          description={choosing.row === PRIMARY ? t('studio.signature.choose.primary', { side: choosing.side }) : t('studio.signature.choose.preview')}
          initial={chosenRow ?? undefined}
          kind={choosing.kind}
          onApply={apply}
          onClose={() => setChoosing(null)}
        />
      )}
    </Flex>
  );
}

interface MessagesProps {
  side: Side;
  pkg: string;
  editing: boolean;
  store: WorkingCopyStore;
  mock: SignatureMockStore;
  clipboard: MessageChoice | null;
  setClipboard: (choice: MessageChoice) => void;
  /** Opens the message choice for a row, with the root kind its Type select asked for. */
  choose: (row: string, kind?: RootKind) => void;
}

/** One side's message table: its message in the mapping file, then the preview messages. */
function Messages({ side, pkg, editing, store, mock, clipboard, setClipboard, choose }: MessagesProps) {
  const primary = useStore(store, (s) => s.workingCopy[side]);
  const previews = useStore(mock, (s) => s.value[side]);
  const [selected, select] = useState('');
  const radios = useId();
  const rows: MessageRow[] = [
    ...(primary ? [{ id: PRIMARY, preview: false, choice: primary, occurrence: '1' as const }] : []),
    ...previews.map((m) => ({ ...m, preview: true })),
  ];
  const row = rows.find((r) => r.id === selected);
  const at = previews.findIndex((m) => m.id === selected);
  const actions = signatureActions(mock);
  const { t } = useText();

  // without the side's message, Insert row chooses it; with it, a preview message goes after the selected row, or last
  const insert = () => {
    if (!primary) {
      choose(PRIMARY);
      return;
    }
    const id = crypto.randomUUID();
    actions.insertMessage(side, at >= 0 ? at + 1 : selected === PRIMARY ? 0 : previews.length, { id, occurrence: '1' });
    select(id);
  };
  const remove = () => {
    const next = previews.filter((m) => m.id !== selected);
    actions.removeMessage(side, selected);
    select(next[Math.min(at, next.length - 1)]?.id ?? '');
  };
  const copy = (cut: boolean) => {
    setClipboard(row!.choice!);
    if (cut) actions.patchMessage(side, selected, { choice: undefined });
  };

  return (
    <Card role="region" aria-label={t('studio.signature.messages', { side })}>
      <SectionHeader
        icon={BlockOutlined}
        title={
          <>
            {t('studio.signature.messages.title', { side })} <span aria-hidden="true">*</span>
            <span className="studio-sr-only">{t('studio.signature.required')}</span>
          </>
        }
        count={rows.filter((r) => r.choice).length}
        countLabel={t('studio.signature.messages.chosen', { side })}
      />

      <dl className="studio-details">
        <div>
          <dt>{t('frame.package')}</dt>
          <dd>{pkg || '—'}</dd>
        </div>
      </dl>
      <Tools
        label={t('studio.signature.messages.actions', { side })}
        editing={editing}
        groups={[
          [
            { label: t('studio.signature.insertRow'), icon: PlusOutlined, onClick: insert },
            { label: t('studio.signature.deleteRow'), icon: DeleteOutlined, disabled: !row?.preview, onClick: remove },
          ],
          [
            { label: t('studio.moveUp'), icon: ArrowUpOutlined, disabled: at < 1, onClick: () => actions.moveMessage(side, selected, -1) },
            {
              label: t('studio.moveDown'),
              icon: ArrowDownOutlined,
              disabled: at < 0 || at === previews.length - 1,
              onClick: () => actions.moveMessage(side, selected, 1),
            },
          ],
          [
            { label: t('studio.signature.cut'), icon: ScissorOutlined, disabled: !row?.preview || !row.choice, onClick: () => copy(true) },
            { label: t('studio.signature.copy'), icon: CopyOutlined, disabled: !row?.choice, onClick: () => copy(false) },
            {
              label: t('studio.signature.paste'),
              icon: SnippetsOutlined,
              disabled: !clipboard || !row?.preview,
              onClick: () => actions.patchMessage(side, selected, { choice: clipboard! }),
            },
          ],
        ]}
      />

      <table className="studio-edit-grid" aria-label={t('studio.signature.table', { side })} role="table">
        <thead>
          <tr>
            <th scope="col" aria-label={t('studio.signature.selectRow')}>
              #
            </th>
            <th scope="col">{t('studio.signature.type')}</th>
            <th scope="col">{t('studio.name')}</th>
            <th scope="col">{t('studio.signature.namespace')}</th>
            <th scope="col">{t('studio.signature.occurrence')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={r.id} data-selected={selected === r.id} onFocus={() => select(r.id)} onClick={() => select(r.id)}>
              <td>
                <Flex align="center" gap="small">
                  <Radio
                    id={`${radios}-${r.id}`}
                    name={radios}
                    aria-label={t('studio.signature.selectRow.number', { row: i + 1 })}
                    checked={selected === r.id}
                    disabled={!editing}
                    onChange={() => select(r.id)}
                  />

                  <div>{i + 1}</div>
                </Flex>
              </td>
              <td>
                <FieldSelect
                  label={t('studio.signature.messageType')}
                  value={r.choice ? rootKind(r.choice.root) : 'element'}
                  options={ROOT_KINDS.map(([kind, id]) => [kind, t(id)] as const)}
                  disabled={!editing}
                  onChange={(kind) => choose(r.id, kind)}
                />
              </td>
              <td>
                <Button
                  type="link"
                  icon={<SearchOutlined />}
                  iconPlacement="end"
                  aria-label={t('studio.signature.chooseMessage.number', { row: i + 1 })}
                  title={r.choice ? r.choice.bundle.join(', ') : t('studio.signature.chooseMessage')}
                  disabled={!editing}
                  onClick={() => choose(r.id)}
                >
                  {r.choice ? messageName(r.choice) : t('studio.signature.chooseEllipsis')}
                </Button>{' '}
                {r.preview && <PreviewBadge feature={t('studio.preview.severalMessages')} issue={SEVERAL_MESSAGES_ISSUE} />}
              </td>
              <td>
                <Button
                  type="link"
                  icon={<SearchOutlined />}
                  iconPlacement="end"
                  aria-label={t('studio.signature.chooseNamespace.number', { row: i + 1 })}
                  disabled={!editing}
                  onClick={() => choose(r.id)}
                >
                  {(r.choice && messageNamespace(r.choice)) ?? '—'}
                </Button>
              </td>
              <td>
                <FieldSelect
                  label={t('studio.signature.occurrence')}
                  value={r.occurrence}
                  options={OCCURRENCES.map((o) => [o, o] as const)}
                  disabled={!editing || !r.preview}
                  onChange={(occurrence) => actions.patchMessage(side, r.id, { occurrence })}
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {!primary && (
        <p>
          {t('studio.signature.none', { side })}{' '}
          <Button type="link" disabled={!editing} onClick={() => choose(PRIMARY)}>
            {t('studio.signature.add', { side })}
          </Button>
        </p>
      )}
    </Card>
  );
}

const newParameter = (id: string, name = ''): PreviewParameter => ({ id, name, type: 'string', direction: 'Import', description: '' });

/** The parameters table: a preview feature, kept in the browser. */
function Parameters({ store, mock, editing, onLog }: { store: WorkingCopyStore; mock: SignatureMockStore; editing: boolean; onLog: (log: string) => void }) {
  const parameters = useStore(mock, (s) => s.value.parameters);
  // an Import parameter is typed in the source's words, an Export one in the target's: JSON has no xsd: prefix
  const json = {
    Import: useStore(store, (s) => s.workingCopy.source !== undefined && rootKind(s.workingCopy.source.root) === 'json'),
    Export: useStore(store, (s) => s.workingCopy.target !== undefined && rootKind(s.workingCopy.target.root) === 'json'),
  };
  const [selected, select] = useState('');
  const [blankId, setBlankId] = useState(() => crypto.randomUUID());
  const table = useRef<HTMLTableElement>(null);
  const actions = signatureActions(mock);
  const parameter = parameters.find((p) => p.id === selected);
  const { t } = useText();

  const add = (name = '') => {
    const p = newParameter(blankId, name);
    actions.addParameter(p);
    select(p.id);
    setBlankId(crypto.randomUUID());
    return p;
  };
  const addAndFocus = () => {
    const p = add();
    requestAnimationFrame(() => table.current?.querySelector<HTMLInputElement>(`[data-param="${p.id}"] input`)?.focus());
  };
  // the new row's name is empty, so a change gives it one: the row becomes a parameter, and a new row follows
  const changeName = (p: PreviewParameter, name: string) => {
    if (p.id === blankId) add(name);
    else actions.patchParameter(p.id, { name });
  };
  const rows = editing ? [...parameters, newParameter(blankId)] : parameters;

  return (
    <Card role="region" aria-label={t('studio.parameters.region')}>
      <SectionHeader
        icon={CodeOutlined}
        title={
          <>
            {t('studio.parameters')} <PreviewBadge feature={t('studio.preview.parameters')} issue={PARAMETERS_ISSUE} />
          </>
        }
        count={parameters.length}
        countLabel={t('studio.parameters')}
      />

      <Tools
        label={t('studio.parameters.actions')}
        editing={editing}
        groups={[
          [
            { label: t('studio.parameters.add'), icon: PlusOutlined, onClick: addAndFocus },
            {
              label: t('studio.parameters.delete'),
              icon: DeleteOutlined,
              disabled: !parameter,
              onClick: () => {
                actions.removeParameter(selected);
                select('');
              },
            },
          ],
          [
            { label: t('studio.parameters.whereUsed'), icon: PartitionOutlined, disabled: !parameter?.name, onClick: () => onLog(t('studio.parameters.unused', { name: parameter!.name })) },
            {
              label: t('studio.parameters.refresh'),
              icon: ReloadOutlined,
              onClick: () => {
                actions.sortParameters();
                select('');
              },
            },
          ],
        ]}
      />
      <table className="studio-edit-grid" ref={table} aria-label={t('studio.preview.parameters')} role="table">
        <thead>
          <tr>
            {['studio.name', 'studio.parameters.category', 'studio.signature.type', 'studio.parameters.parameter', 'studio.description'].map((h) => (
              <th scope="col" key={h}>
                {t(h)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((p) => {
            const blank = p.id === blankId;
            const prefix = json[p.direction] ? '' : 'xsd:';
            return (
              <tr key={p.id} data-param={p.id} data-selected={selected === p.id} onFocus={() => select(p.id)} onClick={() => select(p.id)}>
                <td>
                  <Input
                    aria-label={t('studio.parameters.name')}
                    placeholder={blank ? t('studio.parameters.new') : t('studio.name')}
                    value={p.name}
                    disabled={!editing}
                    onChange={({ target: { value: name } }) => changeName(p, name)}
                  />
                </td>
                {blank ? (
                  <td colSpan={4} />
                ) : (
                  <>
                    <td>
                      <FieldSelect
                        label={t('studio.parameters.category.label')}
                        value="simple"
                        options={[['simple', t('studio.parameters.simpleType')]]}
                        disabled={!editing}
                        onChange={() => undefined}
                      />
                    </td>
                    <td>
                      <FieldSelect
                        label={t('studio.parameters.type')}
                        value={p.type}
                        options={[
                          ['string', `${prefix}string`],
                          ['integer', `${prefix}integer`],
                        ]}
                        disabled={!editing}
                        onChange={(type) => actions.patchParameter(p.id, { type })}
                      />
                    </td>
                    <td>
                      <FieldSelect
                        label={t('studio.parameters.direction')}
                        value={p.direction}
                        options={[
                          ['Import', t('studio.parameters.import')],
                          ['Export', t('studio.parameters.export')],
                        ]}
                        disabled={!editing}
                        onChange={(direction) => actions.patchParameter(p.id, { direction })}
                      />
                    </td>
                    <td>
                      <Input
                        aria-label={t('studio.parameters.description')}
                        value={p.description}
                        disabled={!editing}
                        onChange={({ target: { value: description } }) => actions.patchParameter(p.id, { description })}
                      />
                    </td>
                  </>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
    </Card>
  );
}
