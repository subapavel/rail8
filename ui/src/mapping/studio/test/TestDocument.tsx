import { Alert, Button, Card, Empty, Flex, Input, Table, Tag } from 'antd';
import {
  AlignLeftOutlined,
  BranchesOutlined,
  CodeOutlined,
  DownloadOutlined,
  DownOutlined,
  MinusSquareOutlined,
  PlusSquareOutlined,
  RightOutlined,
} from '@ant-design/icons';
import { useMemo, useState } from 'react';
import { msg, say, useText, type Label } from '../../../language';
import { PreviewBadge } from '../mock/PreviewBadge';
import { PARAMETERS_ISSUE } from '../mock/signature-mock';
import { zipInstance } from '../mock/test-instances';
import { CopyTextButton } from '../shared/CopyTextButton';
import { documentRows, prettyText, type DocumentFormat } from '../shared/documents';
import { downloadFile } from '../shared/download';
import { FieldTypeIcon } from '../shared/FieldTypeIcon';
import { Tool } from '../shared/Tool';


/** A pane's view of its document. */
export const DOCUMENT_MODES = ['Tree', 'Text'] as const;
export type DocumentMode = (typeof DOCUMENT_MODES)[number];

const SECTIONS = ['Document', 'Parameters'] as const;
type Section = (typeof SECTIONS)[number];

/** The id of the name of each view and section. */
const NAMES: Record<DocumentMode | Section, string> = {
  Tree: 'studio.view.tree',
  Text: 'studio.view.text',
  Document: 'studio.document.document',
  Parameters: 'studio.parameters',
};

/** A row of a pane's parameter tables (a preview): its id among the values, its label and its default. */
export interface ParameterRow {
  id: string;
  label: string;
  initial: string;
}

export interface TestDocumentProps {
  side: 'Input' | 'Output';
  format: DocumentFormat;
  /** The document; `null` for a result before a run, or after a failed one. */
  text: string | null;
  /** Whether the last run failed: the result says so instead of asking for a run. */
  failed?: boolean;
  mode: DocumentMode;
  onMode: (mode: DocumentMode) => void;
  /** Changes the source document; the result has none. */
  edit?: (text: string) => void;
  readOnly: boolean;
  /** Whether a stale trace stands behind the result: it shows a mark. */
  stale?: boolean;
  /** The parameter tables of the Parameters section, each with its rows (a preview). */
  parameters: { title: string; rows: ParameterRow[] }[];
  values: Readonly<Record<string, string>>;
  onParameter?: (id: string, value: string) => void;
  /** Whether the parameter values are read-only; as the document, unless told. */
  valuesReadOnly?: boolean;
}

/** A scrolling area that takes the height left in its pane. */
const SCROLL_AREA = { flex: '1 1 0', minHeight: 0, overflow: 'auto' } as const;
const MONO = { fontFamily: 'var(--ant-font-family-code, monospace)' } as const;

/**
 * A pane of the Test tab, a card: the source document, which the author types, or the result of the last run, each
 * as a tree of fields and values or as text, and a Parameters section, a preview. Pretty Print indents the source, and
 * the result for display only; Save Document Source downloads the document as it shows, as `instance.<format>` in
 * `schema.zip`.
 */
export function TestDocument({
  side,
  format,
  text,
  failed = false,
  mode,
  onMode,
  edit,
  readOnly,
  stale = false,
  parameters,
  values,
  onParameter,
  valuesReadOnly = readOnly,
}: TestDocumentProps) {
  const texts = useText();
  const { t } = texts;
  const input = side === 'Input';
  const title = t(input ? 'studio.document.source' : 'studio.document.result');
  const [section, setSection] = useState<Section>('Document');
  const [closed, setClosed] = useState<ReadonlySet<string>>(new Set());
  const [selection, select] = useState<string | null>(null);
  const [fullPath, setFullPath] = useState(false);
  const [formatted, setFormatted] = useState<{ original: string; text: string } | null>(null);
  // a message, so that it shows in the language chosen while it is up
  const [error, setError] = useState<Label>('');
  const available = text !== null;
  const displayed = !input && formatted?.original === text ? formatted.text : (text ?? '');
  const treeMode = mode === 'Tree';
  const rows = useMemo(() => {
    try {
      return available ? documentRows(displayed, format, title) : [];
    } catch {
      return null;
    }
  }, [available, displayed, format, title]);
  // a closed branch hides the rows below it
  const visible = useMemo(() => {
    const shown = [];
    let hiddenBelow = Infinity;
    for (const row of rows ?? []) {
      if (row.level > hiddenBelow) continue;
      hiddenBelow = closed.has(row.id) ? row.level : Infinity;
      shown.push(row);
    }
    return shown;
  }, [rows, closed]);
  const selectedPath = rows?.find((r) => r.id === selection)?.path ?? rows?.[0]?.path ?? '';

  const switchMode = (next: DocumentMode) => {
    setError('');
    onMode(next);
  };
  const toggleExpand = () => setClosed(closed.size > 0 ? new Set() : new Set((rows ?? []).filter((r) => r.branch).map((r) => r.id)));
  const toggleRow = (id: string) =>
    setClosed((old) => {
      const next = new Set(old);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  const prettyPrint = () => {
    try {
      const value = prettyText(displayed, format);
      if (edit) edit(value);
      else setFormatted({ original: text!, text: value });
      setError('');
    } catch (e) {
      setError(msg('studio.document.unprocessable', { format, reason: (e as Error).message }));
    }
  };
  const download = () => downloadFile('schema.zip', new Blob([zipInstance(displayed, format)], { type: 'application/zip' }));

  const empty = <Empty description={failed ? t('studio.document.failed') : t('studio.document.empty', { format })} />;
  const tree = !available ? (
    empty
  ) : rows === null ? (
    <p role="status">{t('studio.document.noTree', { format })}</p>
  ) : (
    <Table
      aria-label={t('studio.document.structure', { side })}
      size="small"
      pagination={false}
      rowKey="id"
      rowClassName={(row) => (row.id === selection ? 'studio-chosen' : '')}
      dataSource={visible}
      columns={[
        {
          title: t('studio.pane.structure'),
          key: 'structure',
          render: (_, row) => (
            <Flex align="center" gap="small" style={{ paddingInlineStart: `calc(${row.level} * var(--ant-padding, 16px))` }}>
              {row.branch ? (
                <Button
                  size="small"
                  type="text"
                  aria-expanded={!closed.has(row.id)}
                  aria-label={t(closed.has(row.id) ? 'studio.field.expand' : 'studio.field.collapse', { path: row.name })}
                  icon={closed.has(row.id) ? <RightOutlined /> : <DownOutlined />}
                  onClick={() => toggleRow(row.id)}
                />
              ) : (
                <span className="studio-tree-indent" />
              )}
              <Button
                type="link"
                size="small"
                aria-pressed={selection === row.id}
                icon={<FieldTypeIcon type={format === 'XML' && row.branch ? 'structure' : row.kind} />}
                onClick={() => select(row.id)}
              >
                {row.name}
              </Button>
            </Flex>
          ),
        },
        { title: t('studio.document.value'), dataIndex: 'value' },
      ]}
    />
  );
  const toggles = <T extends DocumentMode | Section>(label: string, names: readonly T[], chosen: T, change: (name: T) => void) => (
    <Flex role="group" aria-label={label} gap={0}>
      {names.map((name) => (
        <Button size="small" key={name} aria-pressed={chosen === name} type={chosen === name ? 'primary' : 'default'} onClick={() => change(name)}>
          {t(NAMES[name])}
        </Button>
      ))}
    </Flex>
  );
  return (
    <Card
      className="studio-document"
      id={input ? 'test-input-pane' : 'test-result-pane'}
      size="small"
      role="region"
      aria-label={title}
      title={
        <h2>
          <CodeOutlined aria-hidden /> {title}
        </h2>
      }
      extra={
        <>
          {stale && (
            <Tag color="orange" title={t('studio.queue.stale.why')}>
              {t('studio.queue.stale')}
            </Tag>
          )}
          <Tag>{format}</Tag>
        </>
      }
    >
      {section === 'Document' ? (
        <>
          <Flex gap="small" align="center" wrap>
            {toggles(t('studio.document.view', { side }), DOCUMENT_MODES, mode, switchMode)}
            <Flex role="toolbar" aria-label={t('studio.document.tools', { side })}>
              {treeMode ? (
                <>
                  <Tool
                    label={t(closed.size > 0 ? 'studio.tree.expand' : 'studio.tree.collapse')}
                    icon={closed.size > 0 ? PlusSquareOutlined : MinusSquareOutlined}
                    disabled={!rows?.length}
                    onClick={toggleExpand}
                  />
                  <Tool label={t('studio.tree.fullPath')} icon={BranchesOutlined} pressed={fullPath} onClick={() => setFullPath((v) => !v)} />
                </>
              ) : (
                <>
                  <Tool label={t('studio.document.pretty')} icon={AlignLeftOutlined} disabled={!available || readOnly} onClick={prettyPrint} />
                </>
              )}
              <Tool label={t('studio.tree.download')} icon={DownloadOutlined} disabled={!available} onClick={download} />
            </Flex>
          </Flex>
          {error && <Alert type="error" showIcon title={say(texts, error)} />}
          {treeMode ? (
            <div style={SCROLL_AREA}>{tree}</div>
          ) : input ? (
            <Flex gap="small" style={SCROLL_AREA}>
              <Input.TextArea
                aria-label={t('studio.document.input', { format })}
                spellCheck={false}
                readOnly={readOnly}
                value={displayed}
                onChange={(e) => edit?.(e.target.value)}
                style={{ ...MONO, resize: 'none' }}
              />
              <CopyTextButton label={t('studio.document.copyInput', { format })} text={displayed} />
            </Flex>
          ) : available ? (
            <div style={SCROLL_AREA}>
              <CopyTextButton label={t('studio.document.copyResult', { format })} text={displayed} />
              <pre>{displayed}</pre>
            </div>
          ) : (
            <div style={SCROLL_AREA}>{empty}</div>
          )}
          {treeMode && fullPath && (
            <div>
              <code>{selectedPath}</code>
              <CopyTextButton label={t('studio.document.copyPath', { side })} text={selectedPath} />
            </div>
          )}
        </>
      ) : (
        <div style={SCROLL_AREA}>
          <p>
            {t('studio.document.parameters')} <PreviewBadge feature={t('studio.preview.testParameters')} issue={PARAMETERS_ISSUE} />
          </p>
          {parameters.map(({ title: group, rows: entries }) => (
            <div key={group}>
              <h3>{group}</h3>
              <Table
                aria-label={group}
                size="small"
                rowKey="id"
                pagination={false}
                dataSource={entries}
                locale={{ emptyText: t(input ? 'studio.document.noImports' : 'studio.document.noExports') }}
                columns={[
                  { title: t('studio.name'), dataIndex: 'label' },
                  {
                    title: t('studio.document.value'),
                    key: 'value',
                    render: (_, r) =>
                      onParameter && (
                        <Input
                          aria-label={r.label}
                          readOnly={valuesReadOnly}
                          value={values[r.id] ?? r.initial}
                          onChange={(e) => onParameter(r.id, e.target.value)}
                        />
                      ),
                  },
                ]}
              />
            </div>
          ))}
        </div>
      )}
      {toggles(t('studio.document.panel', { side }), SECTIONS, section, setSection)}
    </Card>
  );
}
