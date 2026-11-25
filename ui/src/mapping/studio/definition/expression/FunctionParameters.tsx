import { Alert, Button, Form, Input, Radio, Select, Space, Switch, Table } from 'antd';
import { MountedModal } from '../../../../MountedModal';
import { PlusCircleOutlined, DeleteOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useText, type Texts } from '../../../../language';
import type { CatalogueParam } from '../../../generated/function-catalogue';
import type { ParamValue, Params } from '../../../generated/mapping-model';
import { Tool } from '../../shared/Tool';
import { DateFormatHelp } from './DateParameterControls';

/** The id of the label of each catalogue parameter that has one, by `<function>.<parameter>`; another one is named from its name. */
const LABELS: Record<string, string> = {
  'counter.initial': 'studio.param.initial',
  'counter.increment': 'studio.param.increment',
  'substring.start': 'studio.param.start',
  'substring.length': 'studio.param.count',
  'fixValues.defaultValue': 'studio.param.default',
  'fixValues.missing': 'studio.param.missing',
  'fixValues.table': 'studio.param.table',
  'formatNum.pattern': 'studio.param.numberFormat',
  'constant.value': 'studio.param.value',
  'currentDate.targetFormat': 'studio.param.targetFormat',
  'dateTrans.sourceFormat': 'studio.param.inputFormat',
  'dateTrans.targetFormat': 'studio.param.outputFormat',
  'dateBefore.format1': 'studio.param.format1',
  'dateBefore.format2': 'studio.param.format2',
  'dateAfter.format1': 'studio.param.format1',
  'dateAfter.format2': 'studio.param.format2',
  'compareDates.format1': 'studio.param.format1',
  'compareDates.format2': 'studio.param.format2',
  'copyValue.position': 'studio.param.index',
  'replaceValue.replacement': 'studio.param.replacement',
  'ifWithoutElse.keepSuppressValues': 'studio.param.keepSuppress',
  'splitByValue.mode': 'studio.param.splitMode',
  'index.initial': 'studio.param.initial',
  'index.increment': 'studio.param.increment',
  'index.reset': 'studio.param.reset',
  'sort.comparison': 'studio.param.comparison',
  'sort.order': 'studio.param.order',
  'sortByKey.comparison': 'studio.param.comparison',
  'sortByKey.order': 'studio.param.order',
  'mapWithDefault.default': 'studio.param.mapDefault',
};

/** The ids of the words for the values a choice offers. */
const VALUE_LABELS: Record<string, string> = {
  eachValue: 'studio.param.value.eachValue',
  emptyValue: 'studio.param.value.emptyValue',
  valueChange: 'studio.param.value.valueChange',
  eachContext: 'studio.param.value.eachContext',
  never: 'studio.param.value.never',
  key: 'studio.param.value.key',
  default: 'studio.param.value.default',
  error: 'studio.param.value.error',
  lexicographic: 'studio.param.value.lexicographic',
  caseInsensitive: 'studio.param.value.caseInsensitive',
  numeric: 'studio.param.value.numeric',
};

/** The parameters edited as integers. */
const INTEGERS = new Set(['counter.initial', 'counter.increment', 'substring.start', 'substring.length', 'copyValue.position', 'index.initial', 'index.increment']);

/** The choices offered in a select rather than as radio buttons. */
const SELECTS = new Set(['splitByValue.mode']);

/** A parameter's label in the language of `t`: its entry in `LABELS`, else its name in words (`defaultValue` is "Default value"). */
const paramLabel = (t: Texts['t'], fn: string, name: string) => {
  const id = LABELS[`${fn}.${name}`];
  return id === undefined ? nameInWords(name) : t(id);
};

/** A parameter's name from the catalogue in English words, as it is written. */
const nameInWords = (name: string) =>
  name
    .replace(/([A-Z])/g, ' $1')
    .replace(/^./, (c) => c.toUpperCase())
    .replace(/ (\w)/g, (_, c: string) => ` ${c.toLowerCase()}`);

/** The word for value `v` of a choice in the language of `t`, or `v` itself. */
const valueLabel = (t: Texts['t'], v: string) => {
  const id = VALUE_LABELS[v];
  return id === undefined ? v : t(id);
};

type Row = { key: string; value: string };
type Draft = Record<string, string | Row[]>;

const toDraft = (params: readonly CatalogueParam[], values: Params | undefined): Draft =>
  Object.fromEntries(
    params.map((p) => {
      const value = values?.[p.name] ?? p.default;
      return [p.name, typeof value === 'string' ? value : Object.entries(value).map(([key, v]) => ({ key, value: v }))];
    }),
  );

const toParams = (draft: Draft): Record<string, ParamValue> =>
  Object.fromEntries(Object.entries(draft).map(([name, v]) => [name, typeof v === 'string' ? v : Object.fromEntries(v.map((r) => [r.key, r.value]))]));

function FixedValues({ value, change, label, disabled }: { value: Row[]; change: (rows: Row[]) => void; label: string; disabled: boolean }) {
  const { t } = useText();
  const cell = (index: number, column: keyof Row, next: string) => change(value.map((row, i) => (i === index ? { ...row, [column]: next } : row)));
  return (
    <Form.Item label={label} htmlFor="param-table">
      <Button type="link" icon={<PlusCircleOutlined aria-hidden />} disabled={disabled} onClick={() => change([...value, { key: '', value: '' }])}>
        {t('studio.param.addPair')}
      </Button>
      <Table
        size="small"
        aria-label={label}
        pagination={false}
        rowKey="index"
        dataSource={value.map((row, index) => ({ ...row, index }))}
        columns={[
          ...(['key', 'value'] as const).map((col) => ({
            key: col,
            title: t(col === 'key' ? 'studio.param.key' : 'studio.param.pairValue'),
            render: (_: unknown, row: Row & { index: number }) => (
              <Input aria-label={t(col === 'key' ? 'studio.param.keyAt' : 'studio.param.valueAt', { n: row.index + 1 })} value={row[col]} disabled={disabled} onChange={(event) => cell(row.index, col, event.target.value)} />
            ),
          })),
          {
            key: 'actions',
            title: t('studio.param.rowActions'),
            render: (_, row) => (
              <Tool label={t('studio.param.remove', { n: row.index + 1 })} icon={DeleteOutlined} disabled={disabled} onClick={() => change(value.filter((_, i) => i !== row.index))} />
            ),
          },
        ]}
      />
    </Form.Item>
  );
}

interface FieldProps {
  fn: string;
  param: CatalogueParam;
  value: string | Row[];
  change: (value: string | Row[]) => void;
  chooseFormat: () => void;
  disabled: boolean;
}

function Field({ fn, param, value, change, chooseFormat, disabled }: FieldProps) {
  const { t } = useText();
  const label = paramLabel(t, fn, param.name);
  const id = `param-${param.name}`;
  if (param.type === 'table') return <FixedValues label={label} value={value as Row[]} change={change} disabled={disabled} />;
  const text = value as string;
  if (param.values?.length === 2 && param.values.includes('true') && param.values.includes('false')) {
    return (
      <Form.Item label={label} htmlFor={id}>
        <Switch id={id} aria-label={label} checked={text === 'true'} disabled={disabled} onChange={(checked) => change(String(checked))} />
      </Form.Item>
    );
  }
  if (param.values && SELECTS.has(`${fn}.${param.name}`)) {
    return (
      <Form.Item label={label} htmlFor={id}>
        <Select
          id={id}
          aria-label={label}
          value={text}
          disabled={disabled}
          onChange={change}
          options={param.values.map((v) => ({ value: v, label: valueLabel(t, v) }))}
        />
      </Form.Item>
    );
  }
  if (param.values) {
    return (
      <Form.Item label={label}>
        <Radio.Group
          aria-label={label}
          name={id}
          value={text}
          disabled={disabled}
          onChange={(event) => change(event.target.value)}
          options={param.values.map((v) => ({ value: v, label: valueLabel(t, v) }))}
        />
      </Form.Item>
    );
  }
  if (param.syntax === 'datePattern') {
    return (
      <Form.Item label={label} htmlFor={id}>
        <Space.Compact block>
          <Input id={id} value={text} disabled={disabled} onChange={(event) => change(event.target.value)} />

          <Button type="default" aria-label={t('studio.param.choose', { label })} disabled={disabled} onClick={chooseFormat}>
            {t('studio.param.chooseFormat')}
          </Button>
        </Space.Compact>
      </Form.Item>
    );
  }
  const integer = INTEGERS.has(`${fn}.${param.name}`);
  return (
    <Form.Item label={label} htmlFor={id}>
      <Input
        id={id}
        type={integer ? 'number' : 'text'}
        step={integer ? 1 : undefined}
        value={text}
        disabled={disabled}
        onChange={(event) => change(event.target.value)}
      />
    </Form.Item>
  );
}

export interface FunctionParametersProps {
  /** The catalogue name of the box's function. */
  fn: string;
  /** Its catalogue parameters. */
  params: readonly CatalogueParam[];
  /** The box's parameters as the model holds them; a missing one shows the catalogue's default. */
  values: Params | undefined;
  readOnly: boolean;
  save: (params: Record<string, ParamValue>) => void;
  close: () => void;
}

/**
 * The parameter dialog of a standard function box: one field per catalogue parameter, a choice as radio buttons (or a
 * select, or a switch for true and false), a date pattern with "Choose format…", a table as key and value pairs.
 * Apply checks the integers and the table's keys and saves all at once; in view mode it only shows them.
 */
export function FunctionParameters({ fn, params, values, readOnly, save, close }: FunctionParametersProps) {
  const [draft, setDraft] = useState<Draft>(() => toDraft(params, values));
  const [error, setError] = useState('');
  const [helper, setHelper] = useState<string | null>(null);
  const { t } = useText();
  const change = (name: string, value: string | Row[]) => {
    setDraft((d) => ({ ...d, [name]: value }));
    setError('');
  };
  if (helper) {
    return (
      <DateFormatHelp
        close={() => setHelper(null)}
        apply={(v) => {
          change(helper, v);
          setHelper(null);
        }}
      />
    );
  }
  const submit = () => {
    for (const p of params) {
      const value = draft[p.name]!;
      if (INTEGERS.has(`${fn}.${p.name}`) && !/^-?\d+$/.test(value as string)) {
        setError(t('studio.param.integer', { label: paramLabel(t, fn, p.name) }));
        return;
      }
      if (typeof value !== 'string' && new Set(value.map((r) => r.key)).size !== value.length) {
        setError(t('studio.param.unique'));
        return;
      }
    }
    save(toParams(draft));
  };
  const title = fn === 'fixValues' ? t('studio.param.fixValues') : t('studio.param.title', { fn });
  return (
    <MountedModal
      title={title}
      onCancel={close}
      width={fn === 'fixValues' ? 720 : undefined}
      okText={t('studio.apply')}
      okButtonProps={{ htmlType: 'submit', form: 'function-parameters' }}
      footer={readOnly ? <Button onClick={close}>{t('frame.tab.close')}</Button> : undefined}
    >
      <Form layout="vertical" id="function-parameters" onFinish={submit}>
        {params.map((p) => (
          <Field key={p.name} fn={fn} param={p} value={draft[p.name]!} change={(v) => change(p.name, v)} chooseFormat={() => setHelper(p.name)} disabled={readOnly} />
        ))}
        {error && <Alert type="error" showIcon role="alert" title={error} />}
      </Form>
    </MountedModal>
  );
}
