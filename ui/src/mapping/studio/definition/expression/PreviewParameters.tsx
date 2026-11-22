import { Button, Col, Descriptions, Empty, Flex, Form, Input, Radio, Row, Select, Switch, Table } from 'antd';
import { MountedModal } from '../../../../MountedModal';
import { ArrowDownOutlined, ArrowUpOutlined, DeleteOutlined } from '@ant-design/icons';
import { useState } from 'react';
import type { PreviewParam } from '../../mock/definition-preview';
import { initialParams, RFC_MODULE, RFC_NAMESPACE, type PreviewFunction, type PreviewFunctionParameter } from '../../mock/preview-functions';
import { moveRow } from '../../mock/signature-mock';
import { Tool } from '../../shared/Tool';

type Values = Record<string, PreviewParam>;

/** The choices of Behaviour if lookup fails. */
const STRATEGIES = [
  ['2', 'Use Key'],
  ['1', 'Use Default Value'],
  ['0', 'Throw Exception'],
] as const;

const TITLES: Record<PreviewFunction['key'], string> = { valuemap: 'Value Mapping Properties', tableLookup: 'JDBC Lookup', rfclookup: 'RFC Lookup' };

/** A field of the Value Mapping Properties: a text, an agency with its default offered, or the strategy. */
function Field({ param, value, change, disabled }: { param: PreviewFunctionParameter; value: string; change: (value: string) => void; disabled: boolean }) {
  const { key, label, type } = param;
  const id = `preview-param-${key}`;
  if (type === 'vmstrategy') {
    return (
      <Form.Item label={label}>
        <Radio.Group
          aria-label={label}
          name={id}
          value={value}
          disabled={disabled}
          onChange={(event) => change(event.target.value)}
          options={STRATEGIES.map(([v, name]) => ({ value: v, label: name }))}
        />
      </Form.Item>
    );
  }
  const agency = type === 'vmsagency' ? 'SenderParty' : type === 'vmtagency' ? 'ReceiverParty' : null;
  return (
    <Form.Item label={label} htmlFor={id}>
      <Input id={id} aria-label={label} value={value} disabled={disabled} list={agency ? `${id}-agency` : undefined} onChange={(event) => change(event.target.value)} />
      {agency && (
        <datalist id={`${id}-agency`}>
          <option value={agency} />
        </datalist>
      )}
    </Form.Item>
  );
}

/** A list of a lookup's fields: reorder or remove the selected field. */
function FieldList({ title, fields, change, disabled }: { title: string; fields: string[]; change: (fields: string[]) => void; disabled: boolean }) {
  const [selected, setSelected] = useState('');
  const index = fields.indexOf(selected);
  return (
    <Form.Item label={title} htmlFor={`field-list-${title}`}>
      {fields.length === 0 ? (
        <small>No fields</small>
      ) : (
        <ul aria-label={title}>
          {fields.map((name) => (
            <li key={name}>
              <Button type={selected === name ? 'primary' : 'text'} size="small" aria-pressed={name === selected} onClick={() => setSelected(name)}>
                {name}
              </Button>
            </li>
          ))}
        </ul>
      )}
      <Flex gap={0}>
        <Tool label={`Move ${title} up`} icon={ArrowUpOutlined} disabled={disabled || index <= 0} onClick={() => change([...moveRow(fields, index, -1)])} />
        <Tool
          label={`Move ${title} down`}
          icon={ArrowDownOutlined}
          disabled={disabled || index < 0 || index >= fields.length - 1}
          onClick={() => change([...moveRow(fields, index, 1)])}
        />
        <Tool
          label={`Remove selected field from ${title}`}
          icon={DeleteOutlined}
          disabled={disabled || index < 0}
          onClick={() => change(fields.filter((n) => n !== selected))}
        />
      </Flex>
    </Form.Item>
  );
}

/** The lookup picker: the lookup catalogue, the one RFC module it offers, no database table. */
function LookupPicker({ rfc, current, apply, close }: { rfc: boolean; current: string; apply: (ref: string) => void; close: () => void }) {
  const [selected, setSelected] = useState('');
  const [query, setQuery] = useState('');
  const entries = [...new Set([...(rfc ? [RFC_MODULE] : []), ...(current ? [current] : [])])];
  const rows = entries.filter((n) => n.toLowerCase().includes(query.trim().toLowerCase()));
  const title = rfc ? 'Choose Function Module' : 'Choose External Definition';
  return (
    <MountedModal width={720} title={title} onCancel={close} onOk={() => apply(selected)} okText="Apply" okButtonProps={{ disabled: !rows.includes(selected) }}>
      <h3>Search Criteria</h3>
      <Descriptions size="small" items={[{ key: 'category', label: 'Category', children: rfc ? 'rfc' : 'dbtab' }]} />
      <h3>Search Result</h3>
      <Input aria-label="Search" placeholder="Search" value={query} allowClear onChange={(event) => setQuery(event.target.value)} />
      <Table
        aria-label="Search Result"
        size="small"
        pagination={false}
        rowKey={(name) => name}
        rowClassName={(name) => (name === selected ? 'studio-chosen' : '')}
        dataSource={rows}
        locale={{ emptyText: <Empty description="No Suitable Data Found" /> }}
        columns={[
          {
            title: 'Name',
            key: 'name',
            render: (_, name) => (
              <Button type="link" aria-pressed={selected === name} onClick={() => setSelected(name)}>
                {name}
              </Button>
            ),
          },
          { title: 'Namespace', key: 'namespace', render: (_, name) => (name === RFC_MODULE ? RFC_NAMESPACE : '—') },
        ]}
      />
    </MountedModal>
  );
}

interface LookupParametersProps {
  fn: PreviewFunction;
  draft: Values;
  change: (key: string, value: PreviewParam) => void;
  openPicker: () => void;
  disabled: boolean;
}

/** A lookup's parameters: the channel, the table or module, its field lists and its switch. */
function LookupParameters({ fn, draft, change, openPicker, disabled }: LookupParametersProps) {
  const rfc = fn.key === 'rfclookup';
  const flag = rfc ? 'useExc' : 'split';
  const flagSwitch = (
    <Form.Item label={rfc ? 'Use Exception' : 'Insert Context Change After Each Result Value'} htmlFor={`lookup-${flag}`}>
      <Switch
        id={`lookup-${flag}`}
        aria-label={rfc ? 'Use Exception' : 'Insert Context Change After Each Result Value'}
        checked={draft[flag] as boolean}
        disabled={disabled}
        onChange={(checked) => change(flag, checked)}
      />
    </Form.Item>
  );
  const ref = draft.ref as string;
  return (
    <>
      <Form.Item label="Communication Channel" htmlFor="lookup-channel">
        {/* the mapping parameters have no Adapter category yet: no channel to offer */}
        <Select
          id="lookup-channel"
          aria-label="Communication Channel"
          value=""
          disabled
          options={[{ value: '', label: "No suitable parameter found; define new parameter of type 'Channel' first" }]}
        />
      </Form.Item>
      <Flex align="center">
        <div>{ref || (rfc ? 'Select an RFC module' : 'Select a database table')}</div>
        <div>
          <Button type="default" size="small" disabled={disabled} onClick={openPicker}>
            {rfc ? 'Choose RFC module' : 'Choose database table'}
          </Button>
        </div>
      </Flex>
      {!rfc && flagSwitch}
      <Row gutter={16}>
        <Col xs={24} md={rfc ? 12 : 8}>
          <FieldList title={rfc ? 'Import fields' : 'Input fields'} fields={draft.ins as string[]} change={(v) => change('ins', v)} disabled={disabled} />
        </Col>
        {!rfc && (
          <Col xs={24} md={rfc ? 12 : 8}>
            <Form.Item label="Database table fields" htmlFor="lookup-table-fields">
              <small>No fields available</small>
            </Form.Item>
          </Col>
        )}
        <Col xs={24} md={rfc ? 12 : 8}>
          <FieldList title={rfc ? 'Export fields' : 'Output fields'} fields={draft.outs as string[]} change={(v) => change('outs', v)} disabled={disabled} />
        </Col>
      </Row>
      {rfc && flagSwitch}
    </>
  );
}

export interface PreviewParametersProps {
  fn: PreviewFunction;
  /** The node's parameters as the preview keeps them: those that differ from the initial values. */
  values: Values | undefined;
  readOnly: boolean;
  /** Saves the parameters that differ from the initial values. */
  save: (params: Values) => void;
  close: () => void;
}

/**
 * The parameter dialog of a preview function (Value mapping and the lookups): the Value Mapping Properties, or a
 * lookup's channel, table or module and fields. Apply saves them to the preview; in view mode it only shows them.
 */
export function PreviewParameters({ fn, values, readOnly, save, close }: PreviewParametersProps) {
  const initial = initialParams(fn);
  const [draft, setDraft] = useState<Values>(() => ({ ...initial, ...values }));
  const [picking, setPicking] = useState(false);
  const change = (key: string, value: PreviewParam) => setDraft((d) => ({ ...d, [key]: value }));
  const lookup = fn.key !== 'valuemap';
  if (picking) {
    return (
      <LookupPicker
        rfc={fn.key === 'rfclookup'}
        current={draft.ref as string}
        close={() => setPicking(false)}
        apply={(ref) => {
          setDraft((d) => ({ ...d, ref, ins: [], outs: [] }));
          setPicking(false);
        }}
      />
    );
  }
  const submit = () => {
    save(Object.fromEntries(Object.entries(draft).filter(([key, value]) => JSON.stringify(value) !== JSON.stringify(initial[key]))));
  };
  return (
    <MountedModal
      width={lookup ? 720 : undefined}
      title={TITLES[fn.key]}
      onCancel={close}
      okText="Apply"
      okButtonProps={{ htmlType: 'submit', form: 'preview-parameters' }}
      footer={readOnly ? <Button onClick={close}>Close</Button> : undefined}
    >
      <Form layout="vertical" id="preview-parameters" onFinish={submit}>
        {lookup ? (
          <LookupParameters fn={fn} draft={draft} change={change} openPicker={() => setPicking(true)} disabled={readOnly} />
        ) : (
          fn.parameters.map((p) => <Field key={p.key} param={p} value={draft[p.key] as string} change={(v) => change(p.key, v)} disabled={readOnly} />)
        )}
      </Form>
    </MountedModal>
  );
}
