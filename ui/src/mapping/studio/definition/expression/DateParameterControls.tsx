import { Col, Form, Row, Select } from 'antd';
import { MountedModal } from '../../../../MountedModal';
import { useState } from 'react';

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
  const result = [joinPattern(parts.date, parts.ds), joinPattern(parts.time, parts.ts)].filter(Boolean).join(parts.join);
  const select = (key: keyof Parts, label: string, choices: string[]) => (
    <Form.Item label={label} htmlFor={`date-format-${key}`}>
      <Select
        id={`date-format-${key}`}
        aria-label={label}
        value={parts[key]}
        onChange={(value) => setParts({ ...parts, [key]: value })}
        options={choices.map((v) => ({ value: v, label: v === ' ' ? 'Space' : v || 'None' }))}
      />
    </Form.Item>
  );
  return (
    <MountedModal title="SimpleDateFormat" onCancel={close} okText="Apply format" onOk={() => apply(result)}>
      <Form layout="vertical">
        <Row gutter={16}>
          <Col xs={24} sm={12}>
            {select('date', 'Date format', datePatterns)}
          </Col>
          <Col xs={24} sm={12}>
            {select('time', 'Time format', ['', 'HHmmss', 'HHmm'])}
          </Col>
        </Row>
        {select('ds', 'Date Separator', separators)}
        {select('ts', 'Time Separator', separators)}
        {select('join', 'Date/Time Separator', [' ', ','])}
        <Form.Item label="Format preview" htmlFor="date-format-preview">
          <output id="date-format-preview">{result || '—'}</output>
        </Form.Item>
      </Form>
    </MountedModal>
  );
}
