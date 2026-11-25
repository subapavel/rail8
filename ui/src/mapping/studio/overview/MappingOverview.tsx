import { Card, Col, Form, Input, Row } from 'antd';
import { useId, type ReactNode } from 'react';
import { useStore } from 'zustand';
import { useText } from '../../../language';
import type { StructureRef } from '../../generated/mapping-model';
import { messageName } from '../shared/structure-names';
import type { WorkingCopyStore } from '../store/working-copy';

export interface MappingOverviewProps {
  /** The mapping's id, its workspace path: its folder is the package and its last segment the name. */
  mappingId: string;
  store: WorkingCopyStore;
}

/** What a detail shows for an absent value, as the mapping list does: a package of the top folder, a message not chosen. */
const orDash = (value: string | undefined) => (value ? value : '—');

const nameOf = (ref: StructureRef | undefined) => orDash(ref && messageName(ref));

/**
 * The Overview tab: two cards side by side, one below the other on a narrow screen. The first holds the mapping
 * details, Name, Mapping ID, Package, Source and Target; the second the working copy's description, read-only in
 * view mode.
 */
export function MappingOverview({ mappingId, store }: MappingOverviewProps) {
  const editing = useStore(store, (s) => s.editing);
  const description = useStore(store, (s) => s.workingCopy.description ?? '');
  const source = useStore(store, (s) => s.workingCopy.source);
  const target = useStore(store, (s) => s.workingCopy.target);
  const descriptionId = useId();
  const { t } = useText();
  const slash = mappingId.lastIndexOf('/');
  const fields: [string, ReactNode][] = [
    [t('studio.name'), mappingId.slice(slash + 1)],
    [t('studio.overview.id'), <code key="id">{mappingId}</code>],
    [t('frame.package'), orDash(mappingId.slice(0, Math.max(slash, 0)))],
    [t('mapping.list.source'), nameOf(source)],
    [t('mapping.list.target'), nameOf(target)],
  ];

  return (
    <section aria-label={t('studio.tab.overview')}>
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={10}>
          <Card title={<h2>{t('studio.overview.details')}</h2>}>
            <dl className="studio-details">
              {fields.map(([term, value]) => (
                <div key={term}>
                  <dt>{term}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
          </Card>
        </Col>
        <Col xs={24} lg={14}>
          <Card>
            <Form layout="vertical" component="div">
              <Form.Item label={t('studio.description')} htmlFor={descriptionId}>
                <Input.TextArea
                  aria-label={t('studio.description')}
                  id={descriptionId}
                  readOnly={!editing}
                  rows={8}
                  value={description}
                  onChange={(event) => store.getState().setDescription(event.target.value)}
                />
              </Form.Item>
            </Form>
          </Card>
        </Col>
      </Row>
    </section>
  );
}
