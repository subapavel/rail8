import { Alert, Button, Flex, Input, Tabs, Typography } from 'antd';
import { useId, useRef, useState } from 'react';
import { useText } from '../../language';
import { MountedModal } from '../../MountedModal';

interface Props {
  open: boolean;
  startLabel: string;
  endLabel: string;
  stepCount: number;
  onClose: () => void;
}

type Pair = { id: number; name: string; value: string };
type PairTab = 'Properties' | 'Headers';

const MAX_PAIRS = 32;
const MAX_FILES = 8;
const MIB = 1024 * 1024;
const sections: PairTab[] = ['Properties', 'Headers'];
/** The kind of a row of each tab, as the texts name it. */
const kindOf = { Properties: 'property', Headers: 'header' } as const;

function PairEditor({ section, rows, change }: { section: PairTab; rows: Pair[]; change: (rows: Pair[]) => void }) {
  const { t } = useText();
  const kind = kindOf[section];
  const update = (id: number, field: 'name' | 'value', value: string) =>
    change(rows.map(row => row.id === id ? { ...row, [field]: value } : row));
  return <Flex vertical gap="small" align="stretch">
    {rows.map((row, index) => <Flex key={row.id} gap="small">
      <Input aria-label={t('flows.simulation.pair.name', { kind, number: index + 1 })} placeholder={t('flows.simulation.name')} maxLength={256}
        value={row.name} onChange={event => update(row.id, 'name', event.target.value)} />
      <Input aria-label={t('flows.simulation.pair.value', { kind, number: index + 1 })} placeholder={t('flows.simulation.value')} maxLength={4096}
        value={row.value} onChange={event => update(row.id, 'value', event.target.value)} />
      <Button aria-label={t('flows.simulation.pair.remove', { kind, number: index + 1 })}
        onClick={() => change(rows.filter(item => item.id !== row.id))}>{t('flows.simulation.remove')}</Button>
    </Flex>)}
    <div><Button disabled={rows.length >= MAX_PAIRS} onClick={() => {
      const id = Math.max(0, ...rows.map(row => row.id + 1));
      change([...rows, { id, name: '', value: '' }]);
    }}>{t('flows.simulation.pair.add', { kind })}</Button></div>
  </Flex>;
}

/** Keeps a draft for this flow tab while its input dialog is closed. */
export function SimulationDialog({ open, onClose, startLabel, endLabel, stepCount }: Props) {
  const { t } = useText();
  const [body, setBody] = useState('');
  const [files, setFiles] = useState<File[]>([]);
  const [pairs, setPairs] = useState<Record<PairTab, Pair[]>>({ Properties: [], Headers: [] });
  const [tab, setTab] = useState('Body');
  const [preview, setPreview] = useState(false);
  const [error, setError] = useState('');
  const bodyId = useId();
  const fileInput = useRef<HTMLInputElement>(null);
  if (!open) return null;

  function addFiles(selection: FileList | null) {
    if (!selection?.length) return;
    const added = Array.from(selection);
    const names = new Set(files.map(file => file.name));
    if (added.length + files.length > MAX_FILES) return setError(t('flows.simulation.tooManyFiles', { max: MAX_FILES }));
    for (const file of added) {
      if (file.size > MIB) return setError(t('flows.simulation.tooLarge', { name: file.name }));
      if (names.has(file.name)) return setError(t('flows.simulation.sameFile', { name: file.name }));
      names.add(file.name);
    }
    setFiles([...files, ...added]);
    setError('');
  }

  function previewInput() {
    for (const section of sections) {
      const names = new Set<string>();
      for (const row of pairs[section]) {
        const name = row.name.trim();
        const key = section === 'Headers' ? name.toLowerCase() : name;
        if (!key || names.has(key)) {
          setTab(section);
          setError(key ? t('flows.simulation.sameName', { kind: kindOf[section], name }) : t('flows.simulation.noName', { kind: kindOf[section] }));
          return;
        }
        names.add(key);
      }
    }
    setError('');
    setPreview(true);
  }

  function close() {
    setPreview(false);
    setError('');
    onClose();
  }

  const panelStyle = { minHeight: 220 };
  return <MountedModal title={t('flows.simulation.title')} width={680} onCancel={close}
    styles={{ body: { maxHeight: '65dvh', overflowY: 'auto' } }}
    footer={[
      <Button key="close" aria-label={t('flows.simulation.closeLabel')} onClick={close}>{t('flows.simulation.close')}</Button>,
      <Button key="preview" type="primary" onClick={preview ? () => setPreview(false) : previewInput}>
        {t(preview ? 'flows.simulation.back' : 'flows.simulation.preview')}
      </Button>,
    ]}>
    <Flex vertical gap="small">
      <Typography.Text style={{ overflowWrap: 'anywhere' }}>
        <strong>{startLabel}</strong>{stepCount > 1 && <> → <strong>{endLabel}</strong></>}
      </Typography.Text>
      <Typography.Text type="secondary">{t('flows.simulation.hint')}</Typography.Text>
      {error && <Alert type="error" title={error} showIcon />}
      {preview ? <section aria-label={t('flows.simulation.input')} style={{ overflowWrap: 'anywhere' }}>
        <Typography.Title level={5}>{t('flows.simulation.body')}</Typography.Title>
        {body ? <pre style={{ whiteSpace: 'pre-wrap', padding: 'var(--ant-padding-sm)', borderRadius: 'var(--ant-border-radius)',
          background: 'var(--ant-color-fill-quaternary)' }}>{body}</pre> : <Typography.Text type="secondary">{t('flows.simulation.emptyBody')}</Typography.Text>}
        <Typography.Title level={5}>{t('flows.simulation.attachments')}</Typography.Title>
        {files.length ? files.map(file => <div key={file.name}>{file.name} · {file.type || 'application/octet-stream'} · {t('flows.simulation.bytes', { size: file.size })}</div>)
          : <Typography.Text type="secondary">{t('flows.simulation.noAttachments')}</Typography.Text>}
        {sections.map(section => <div key={section}>
          <Typography.Title level={5}>{t('flows.simulation.tab', { kind: kindOf[section] })}</Typography.Title>
          {pairs[section].length ? <dl style={{ margin: 0 }}>
            {pairs[section].map(row => <Flex key={row.id} gap="small" align="baseline">
              <dt style={{ flex: '0 0 38%' }}>{row.name.trim()}</dt>
              <dd style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{row.value}</dd>
            </Flex>)}
          </dl> : <Typography.Text type="secondary">{t('flows.simulation.none', { kind: kindOf[section] })}</Typography.Text>}
        </div>)}
      </section> : <Tabs activeKey={tab} onChange={key => { setTab(key); setError(''); }} items={[
        { key: 'Body', label: t('flows.simulation.body'), children: <Flex vertical gap="small" style={panelStyle}>
          <label htmlFor={bodyId}>{t('flows.simulation.message')}</label>
          <Input.TextArea id={bodyId} value={body} onChange={event => setBody(event.target.value)}
            placeholder={t('flows.simulation.message.placeholder')} rows={8} maxLength={MIB} spellCheck={false}
            style={{ fontFamily: 'var(--ant-font-family-code)' }} />
        </Flex> },
        { key: 'Attachments', label: t('flows.simulation.attachments'), children: <Flex vertical gap="small" style={panelStyle}>
          <Typography.Text type="secondary">{t('flows.simulation.limits', { max: MAX_FILES })}</Typography.Text>
          {files.map(file => <Flex key={file.name} align="center" gap="small" justify="space-between">
            <Typography.Text style={{ overflowWrap: 'anywhere' }}>{file.name} · {t('flows.simulation.bytes', { size: file.size })}</Typography.Text>
            <Button aria-label={t('flows.simulation.removeFile', { name: file.name })} onClick={() => { setFiles(files.filter(item => item !== file)); setError(''); }}>{t('flows.simulation.remove')}</Button>
          </Flex>)}
          <input type="file" multiple hidden ref={fileInput} aria-label={t('flows.simulation.addFiles')}
            onChange={event => { addFiles(event.target.files); event.target.value = ''; }} />
          <div><Button disabled={files.length >= MAX_FILES} onClick={() => fileInput.current?.click()}>{t('flows.simulation.addFiles')}</Button></div>
        </Flex> },
        ...sections.map(section => ({ key: section, label: t('flows.simulation.tab', { kind: kindOf[section] }), children: <div style={panelStyle}>
          <PairEditor section={section} rows={pairs[section]} change={rows => { setPairs({ ...pairs, [section]: rows }); setError(''); }} />
        </div> })),
      ]} />}
    </Flex>
  </MountedModal>;
}
