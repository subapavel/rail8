import { Button, Flex } from 'antd';
import { WarningOutlined, DeleteOutlined } from '@ant-design/icons';
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
  return (
    <section aria-label="Orphaned target field mappings">
      <h3>
        <WarningOutlined aria-hidden style={{ color: 'var(--ant-color-warning)' }} /> Orphaned target field mappings ({paths.length})
      </h3>
      <p>The target structure has no such fields. Open one to see its expression, or delete it in edit mode.</p>
      <ul aria-label="Orphaned target field mappings list">
        {paths.map((path) => {
          const shown = `/${localPath(path)}`;
          return (
            <li key={path} aria-label={shown}>
              <Flex align="center" gap="small">
                <code title={path}>{shown}</code>
                <Button type="link" size="small" aria-label={`Open ${shown}`} aria-pressed={selected === path} onClick={() => onOpen(path)}>
                  Open
                </Button>
                <Button
                  type="link"
                  danger
                  size="small"
                  icon={<DeleteOutlined aria-hidden />}
                  aria-label={`Delete ${shown}`}
                  disabled={!editing}
                  onClick={() => onDelete(path)}
                >
                  Delete
                </Button>
              </Flex>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
