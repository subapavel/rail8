import { Button, Flex } from 'antd';
import { CheckCircleOutlined, SettingOutlined, ExclamationCircleOutlined, WarningOutlined, MinusCircleOutlined } from '@ant-design/icons';
import { Handle, Position, useUpdateNodeInternals, type Node, type NodeProps } from '@xyflow/react';
import { useEffect, useRef, type CSSProperties } from 'react';
import type { FieldPath } from '../../../generated/mapping-model';
import { localPath, stepName } from '../../../model/field-path';
import { properAncestors } from '../../../model/set-context';
import { PreviewBadge } from '../../mock/PreviewBadge';
import { FieldTypeIcon } from '../../shared/FieldTypeIcon';
import { SOURCE_FIELD_DRAG } from '../../store/drag';
import { NODE_WIDTH, nodeHeight, type ExpressionNode, type NodeState, type Port } from './expression-graph';

const ROLES = { source: 'Source field', function: 'Node function', target: 'Target field' } as const;

/** What a node is, in words: a mapping variable says so. */
export const nodeRole = (node: ExpressionNode) => node.role ?? ROLES[node.kind];

/** A node state's mark and its colour: complete, incomplete, or with no path to the target. */
export const STATE_MARKS = {
  complete: { icon: CheckCircleOutlined, color: 'var(--ant-color-success)', label: 'Complete' },
  incomplete: { icon: ExclamationCircleOutlined, color: 'var(--ant-color-warning)', label: 'Incomplete' },
  detached: { icon: MinusCircleOutlined, color: 'var(--ant-color-text-secondary)', label: 'No target path' },
} as const;

/** The data of a canvas node: the graph node, its state, its field's type and the opener of its parameters. */
export type MappingNodeData = {
  node: ExpressionNode;
  state: NodeState;
  /** The type icon of a field node: `structure` or a value type's local name. */
  fieldType?: string;
  /** Opens the parameter dialog of a standard function box with parameters. */
  editParameters?: () => void;
  /** The parameters a function card shows below its name, as text. */
  settings?: string;
  /** The deviation note of a standard function box, shown in its hint only; absent for any other node. */
  note?: string;
  /** A source-field box that reads a field the source structure lacks. */
  orphaned?: boolean;
  /** Points an orphaned box at a source field dropped on it; absent for any other box and in view mode. */
  remap?: (field: FieldPath) => void;
};

/** What an orphaned box's mark says. */
const ORPHANED_HINT = 'Orphaned reference: the source structure has no such field. Drop a source field here, or choose Remap in the node menu.';

export type MappingFlowNode = Node<MappingNodeData, 'mapping'>;

/** The colour of each kind of node's symbol: its background and its text. */
const SYMBOL_COLOURS = {
  source: ['var(--ant-cyan-1)', 'var(--ant-cyan-8)'],
  function: ['var(--ant-purple-1)', 'var(--ant-purple-8)'],
  target: ['var(--ant-blue-1)', 'var(--ant-blue-8)'],
} as const;

function portHint(node: ExpressionNode, port: Port | undefined, input: boolean, index: number): string[] {
  const direction = input ? 'Input' : 'Output';
  if (node.kind !== 'function') return [direction, `/${localPath(node.path!)}`];
  return [`${direction} ${index + 1}: ${port ? port.label : 'out'}`, node.name];
}

function PortHandle({ node, state, port, index, count, input }: { node: ExpressionNode; state: NodeState; port?: Port; index: number; count: number; input: boolean }) {
  const hint = portHint(node, port, input, index);
  const missing = input && state.missing.includes(port!.id);
  if (missing) hint.push('Missing connection');
  return (
    <Handle
      id={port ? port.id : 'out'}
      type={input ? 'target' : 'source'}
      position={input ? Position.Left : Position.Right}
      tabIndex={0}
      role="group"
      aria-label={hint.join(' · ')}
      data-missing={missing || undefined}
      data-mapping-hint={[...hint, state.description].join('\n')}
      style={{
        top: `calc(var(--node-height) * ${(index + 0.5) / count} - 1px)`,
        width: 'var(--ant-size-xs)',
        height: 'var(--ant-size-xs)',
        ...(missing && { background: 'var(--ant-color-warning)', borderColor: 'var(--ant-color-warning)' }),
      }}
    />
  );
}

/**
 * A box or the target on the canvas: its symbol with the state mark, its name, a changed context or the missing
 * inputs below it, a gear for a function with parameters, and its ports; a deviation note is in its hint only. A
 * source row dropped on a box that takes a remap remaps it; any other drop, such as a catalogue function's, goes on
 * to the canvas.
 */
export function MappingNode({ id, data, selected }: NodeProps<MappingFlowNode>) {
  const { node, state, fieldType, editParameters, settings, note, orphaned, remap } = data;
  const refresh = useUpdateNodeInternals();
  const ports = JSON.stringify(node.inputs.map((p) => p.id));
  const previousPorts = useRef(ports);
  useEffect(() => {
    // React Flow measures a new node on mount; only rescan when its ports change
    if (previousPorts.current !== ports) refresh(id);
    previousPorts.current = ports;
  }, [id, ports, refresh]);
  const isFunction = node.kind === 'function';
  // a variable has no context
  const isSource = node.kind === 'source' && node.role === undefined;
  const defaultContext = isSource ? (properAncestors(node.path!)[0] ?? '') : '';
  const context = node.context ?? defaultContext;
  const changedContext = isSource && context !== defaultContext;
  const contextLabel = isSource ? `Context · ${context ? stepName(context) : 'root'}` : '';
  const openInputs = isFunction ? node.inputs.filter((p) => state.missing.includes(p.id)) : [];
  const portNote = openInputs.length ? `Missing: ${openInputs.map((p) => p.label).join(', ')}` : '';
  const path = node.path ? `/${localPath(node.path)}` : '';
  const details = [node.name, nodeRole(node) + (fieldType ? ` · ${fieldType}` : ''), path, contextLabel, settings, note, orphaned && ORPHANED_HINT, state.description]
    .filter(Boolean)
    .join('\n');
  const mark = STATE_MARKS[state.kind];
  const border = selected ? 'var(--ant-color-primary)' : orphaned ? 'var(--ant-color-warning-border)' : 'var(--ant-color-border)';
  const card: CSSProperties = {
    '--node-height': `${nodeHeight(node)}px`,
    height: 'var(--node-height)',
    width: NODE_WIDTH,
    boxSizing: 'border-box',
    position: 'relative',
    display: 'flex',
    alignItems: 'center',
    padding: '0 var(--ant-size-xs)',
    background: 'var(--ant-color-bg-container)',
    border: `${selected ? 'var(--ant-line-width-bold)' : 'var(--ant-line-width)'} ${orphaned && !selected ? 'dashed' : 'solid'} ${border}`,
    borderRadius: 'var(--ant-border-radius-sm)',
    boxShadow: selected ? 'var(--ant-box-shadow)' : 'var(--ant-box-shadow-secondary)',
    color: 'var(--ant-color-text)',
    fontSize: 'var(--ant-font-size-sm)',
  } as CSSProperties;
  return (
    <div
      data-state={state.kind}
      data-orphaned={orphaned || undefined}
      data-mapping-hint={details}
      style={card}
      onDrop={
        remap &&
        ((e) => {
          const field = e.dataTransfer.getData(SOURCE_FIELD_DRAG);
          if (field !== '') remap(field);
        })
      }
    >
      {node.inputs.map((port, i) => (
        <PortHandle key={port.id} node={node} state={state} port={port} index={i} count={node.inputs.length} input />
      ))}
      <Flex align="center" gap="small" style={{ minWidth: 0, flex: 1 }}>
        <div
          style={{
            position: 'relative',
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: 'var(--ant-size-lg)',
            height: 'var(--ant-size-lg)',
            borderRadius: 'var(--ant-border-radius-sm)',
            background: SYMBOL_COLOURS[node.kind][0],
            color: SYMBOL_COLOURS[node.kind][1],
            fontStyle: isFunction ? 'italic' : undefined,
          }}
        >
          {isFunction ? 'ƒ' : <FieldTypeIcon type={fieldType ?? ''} />}
          <mark.icon
            aria-hidden
            style={{
              color: mark.color,
              position: 'absolute',
              right: 'calc(-1 * var(--ant-size-xxs))',
              bottom: 'calc(-1 * var(--ant-size-xxs))',
              borderRadius: 'var(--ant-border-radius-lg)',
              background: 'var(--ant-color-bg-container)',
            }}
          />
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <Flex gap="var(--ant-size-xxs)" align="center">
            <strong
              style={{
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
                ...(changedContext && { color: 'var(--ant-color-link)' }),
              }}
            >
              {node.name}
            </strong>
            {orphaned && <WarningOutlined aria-label="Orphaned reference" title={ORPHANED_HINT} style={{ color: 'var(--ant-color-warning)' }} />}
            {node.preview && <PreviewBadge {...node.preview} />}
          </Flex>
          {(portNote || changedContext || settings) && (
            <small style={{ display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {portNote || [changedContext && contextLabel, settings].filter(Boolean).join(' · ')}
            </small>
          )}
        </div>
        {editParameters && (
          <div>
            <Button
              type="text"
              size="small"
              className="nodrag nopan"
              aria-label={`Parameters for ${node.name}`}
              data-mapping-hint={`Function parameters\n${details}`}
              icon={<SettingOutlined aria-hidden />}
              onDoubleClick={(e) => e.stopPropagation()}
              onClick={(e) => {
                e.stopPropagation();
                editParameters();
              }}
            />
          </div>
        )}
      </Flex>
      {node.output && <PortHandle node={node} state={state} index={0} count={1} input={false} />}
    </div>
  );
}
