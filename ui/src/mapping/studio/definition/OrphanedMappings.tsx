import { Button, Flex } from 'antd';
import { WarningOutlined, DeleteOutlined } from '@ant-design/icons';
import { useText } from '../../../language';
import type { FieldPath } from '../../generated/mapping-model';
import { localPath } from '../../model/field-path';

export interface OrphanedMappingsProps {
  /** The orphaned target field mappings' field paths, in the model's order. */
  paths: readonly FieldPath[];
  /** The selected target field: its entry is marked as open. */
  selected: FieldPath | null;
  editing: boolean;
  /** Shows the expression of a mapping. */
  onOpen: (path: FieldPath) => void;
  /** Deletes a mapping with all its boxes, one undo step. */
  onDelete: (path: FieldPath) => void;
}

/**
 * The orphaned target field mappings (those for a field the target structure lacks) in a group of their own below the
 * target tree, as they have no row in it: each with Open, which shows its expression, and Delete, enabled in edit mode
 * only.
 */
export function OrphanedMappings({ paths, selected, editing, onOpen, onDelete }: OrphanedMappingsProps) {
  const { t } = useText();
  return (
    <section aria-label={t('studio.orphaned')}>
      <h3>
        <WarningOutlined aria-hidden style={{ color: 'var(--ant-color-warning)' }} /> {t('studio.orphaned.count', { count: paths.length })}
      </h3>
      <p>{t('studio.orphaned.why')}</p>
      <ul aria-label={t('studio.orphaned.list')}>
        {paths.map((path) => {
          const shown = `/${localPath(path)}`;
          return (
            <li key={path} aria-label={shown}>
              <Flex align="center" gap="small">
                <code title={path}>{shown}</code>
                <Button type="link" size="small" aria-label={t('studio.orphaned.open', { path: shown })} aria-pressed={selected === path} onClick={() => onOpen(path)}>
                  {t('studio.open')}
                </Button>
                <Button
                  type="link"
                  danger
                  size="small"
                  icon={<DeleteOutlined aria-hidden />}
                  aria-label={t('studio.orphaned.delete', { path: shown })}
                  disabled={!editing}
                  onClick={() => onDelete(path)}
                >
                  {t('studio.delete')}
                </Button>
              </Flex>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
