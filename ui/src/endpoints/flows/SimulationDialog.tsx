import { Alert, Button, Flex, Input, Tabs, Typography } from 'antd';
import { useId, useRef, useState } from 'react';
import { MountedModal } from '../../MountedModal';

interface Props {
  open: boolean;
  startLabel: string;
  endLabel: string;
  onClose: () => void;
}

type Pair = { id: number; name: string; value: string };
type PairTab = 'Properties' | 'Headers';

const MAX_PAIRS = 32;
const MAX_FILES = 8;
const MIB = 1024 * 1024;
const sections: PairTab[] = ['Properties', 'Headers'];
const singular = { Properties: 'Property', Headers: 'Header' };

function PairEditor({ section, rows, change }: { section: PairTab; rows: Pair[]; change: (rows: Pair[]) => void }) {
  const label = singular[section];
  const update = (id: number, field: 'name' | 'value', value: string) =>
    change(rows.map(row => row.id === id ? { ...row, [field]: value } : row));
  return <Flex vertical gap="small" align="stretch">
    {rows.map((row, index) => <Flex key={row.id} gap="small">
      <Input aria-label={`${label} name ${index + 1}`} placeholder="Name" maxLength={256}
        value={row.name} onChange={event => update(row.id, 'name', event.target.value)} />
      <Input aria-label={`${label} value ${index + 1}`} placeholder="Value" maxLength={4096}
        value={row.value} onChange={event => update(row.id, 'value', event.target.value)} />
      <Button aria-label={`Remove ${label.toLowerCase()} ${index + 1}`}
        onClick={() => change(rows.filter(item => item.id !== row.id))}>Remove</Button>
    </Flex>)}
    <div><Button disabled={rows.length >= MAX_PAIRS} onClick={() => {
      const id = Math.max(0, ...rows.map(row => row.id + 1));
      change([...rows, { id, name: '', value: '' }]);
    }}>Add {label.toLowerCase()}</Button></div>
  </Flex>;
}

/** Keeps a draft for this flow tab while its input dialog is closed. */
export function SimulationDialog({ open, onClose, startLabel, endLabel }: Props) {
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
    if (added.length + files.length > MAX_FILES) return setError(`Choose up to ${MAX_FILES} attachments.`);
    for (const file of added) {
      if (file.size > MIB) return setError(`${file.name} exceeds the 1 MiB attachment limit.`);
      if (names.has(file.name)) return setError(`An attachment named “${file.name}” already exists.`);
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
          setError(key ? `Duplicate ${singular[section].toLowerCase()} name “${name}”.` : `Enter a name for each ${singular[section].toLowerCase()}.`);
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
  return <MountedModal title="Simulation preview" width={680} onCancel={close}
    styles={{ body: { maxHeight: '65dvh', overflowY: 'auto' } }}
    footer={[
      <Button key="close" aria-label="Close preview" onClick={close}>Close</Button>,
      <Button key="preview" type="primary" onClick={preview ? () => setPreview(false) : previewInput}>
        {preview ? 'Back to input' : 'Preview input'}
      </Button>,
    ]}>
    <Flex vertical gap="small">
      <Typography.Text style={{ overflowWrap: 'anywhere' }}>
        <strong>{startLabel}</strong>{endLabel !== startLabel && <> → <strong>{endLabel}</strong></>}
      </Typography.Text>
      <Typography.Text type="secondary">Preview the input for this range. No steps will run.</Typography.Text>
      {error && <Alert type="error" title={error} showIcon />}
      {preview ? <section aria-label="Input preview" style={{ overflowWrap: 'anywhere' }}>
        <Typography.Title level={5}>Body</Typography.Title>
        {body ? <pre style={{ whiteSpace: 'pre-wrap', padding: 'var(--ant-padding-sm)', borderRadius: 'var(--ant-border-radius)',
          background: 'var(--ant-color-fill-quaternary)' }}>{body}</pre> : <Typography.Text type="secondary">Empty body</Typography.Text>}
        <Typography.Title level={5}>Attachments</Typography.Title>
        {files.length ? files.map(file => <div key={file.name}>{file.name} · {file.type || 'application/octet-stream'} · {file.size.toLocaleString()} bytes</div>)
          : <Typography.Text type="secondary">No attachments</Typography.Text>}
        {sections.map(section => <div key={section}>
          <Typography.Title level={5}>{section}</Typography.Title>
          {pairs[section].length ? <dl style={{ margin: 0 }}>
            {pairs[section].map(row => <Flex key={row.id} gap="small" align="baseline">
              <dt style={{ flex: '0 0 38%' }}>{row.name.trim()}</dt>
              <dd style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{row.value}</dd>
            </Flex>)}
          </dl> : <Typography.Text type="secondary">No {section.toLowerCase()}</Typography.Text>}
        </div>)}
      </section> : <Tabs activeKey={tab} onChange={key => { setTab(key); setError(''); }} items={[
        { key: 'Body', label: 'Body', children: <Flex vertical gap="small" style={panelStyle}>
          <label htmlFor={bodyId}>Message body</label>
          <Input.TextArea id={bodyId} value={body} onChange={event => setBody(event.target.value)}
            placeholder="Enter a message body" rows={8} maxLength={MIB} spellCheck={false}
            style={{ fontFamily: 'var(--ant-font-family-code)' }} />
        </Flex> },
        { key: 'Attachments', label: 'Attachments', children: <Flex vertical gap="small" style={panelStyle}>
          <Typography.Text type="secondary">Up to 8 files, 1 MiB each.</Typography.Text>
          {files.map(file => <Flex key={file.name} align="center" gap="small" justify="space-between">
            <Typography.Text style={{ overflowWrap: 'anywhere' }}>{file.name} · {file.size.toLocaleString()} bytes</Typography.Text>
            <Button aria-label={`Remove ${file.name}`} onClick={() => { setFiles(files.filter(item => item !== file)); setError(''); }}>Remove</Button>
          </Flex>)}
          <input type="file" multiple hidden ref={fileInput} aria-label="Add attachments"
            onChange={event => { addFiles(event.target.files); event.target.value = ''; }} />
          <div><Button disabled={files.length >= MAX_FILES} onClick={() => fileInput.current?.click()}>Add attachments</Button></div>
        </Flex> },
        ...sections.map(section => ({ key: section, label: section, children: <div style={panelStyle}>
          <PairEditor section={section} rows={pairs[section]} change={rows => { setPairs({ ...pairs, [section]: rows }); setError(''); }} />
        </div> })),
      ]} />}
    </Flex>
  </MountedModal>;
}
