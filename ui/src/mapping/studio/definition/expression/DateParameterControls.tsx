import { Col, Form, Row, Select } from 'antd';
import { MountedModal } from '../../../../MountedModal';
import { useState } from 'react';
import { useText } from '../../../../language';

const datePatterns = ['ddMMyyyy', 'MMddyyyy', 'yyyyMMdd', 'MMyyyydd', 'ddyyyyMM', 'yyyyddMM', 'yyddMM', 'ddMMyy', 'MMddyy', ''];
const separators = ['/', ':', '-', ' '];

interface Parts {
  date: string;
  time: string;
  ds: string;
  ts: string;
  join: string;
}

/** A date or time pattern's letter runs joined by `separator` (`ddMMyyyy` with `/` is `dd/MM/yyyy`). */
export const joinPattern = (pattern: string, separator: string) => (pattern.match(/y+|M+|d+|H+|m+|s+/g) ?? []).join(separator);

/**
 * The SimpleDateFormat helper behind "Choose format…": a date and a time pattern with their separators, the result
 * previewed, applied to the parameter it was opened for.
 */
export function DateFormatHelp({ apply, close }: { apply: (format: string) => void; close: () => void }) {
  const [parts, setParts] = useState<Parts>({ date: datePatterns[0]!, time: '', ds: '/', ts: '/', join: ' ' });
  const { t } = useText();
  const result = [joinPattern(parts.date, parts.ds), joinPattern(parts.time, parts.ts)].filter(Boolean).join(parts.join);
  const select = (key: keyof Parts, label: string, choices: string[]) => (
    <Form.Item label={label} htmlFor={`date-format-${key}`}>
      <Select
        id={`date-format-${key}`}
        aria-label={label}
        value={parts[key]}
        onChange={(value) => setParts({ ...parts, [key]: value })}
        options={choices.map((v) => ({ value: v, label: v === ' ' ? t('studio.date.space') : v || t('studio.date.none') }))}
      />
    </Form.Item>
  );
  return (
    <MountedModal title="SimpleDateFormat" onCancel={close} okText={t('studio.date.apply')} onOk={() => apply(result)}>
      <Form layout="vertical">
        <Row gutter={16}>
          <Col xs={24} sm={12}>
            {select('date', t('studio.date.date'), datePatterns)}
          </Col>
          <Col xs={24} sm={12}>
            {select('time', t('studio.date.time'), ['', 'HHmmss', 'HHmm'])}
          </Col>
        </Row>
        {select('ds', t('studio.date.dateSeparator'), separators)}
        {select('ts', t('studio.date.timeSeparator'), separators)}
        {select('join', t('studio.date.separator'), [' ', ','])}
        <Form.Item label={t('studio.date.preview')} htmlFor="date-format-preview">
          <output id="date-format-preview">{result || '—'}</output>
        </Form.Item>
      </Form>
    </MountedModal>
  );
}
