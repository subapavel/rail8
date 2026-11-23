import { ProTable } from '@ant-design/pro-components';
import { Alert, App, Button, Flex, Form, Input, Tag, Typography } from 'antd';
import { useId, useState } from 'react';
import { MountedModal } from '../MountedModal';
import { useWorkingFiles, type WorkingFile } from '../workingFiles';
import { PackagesError, type Change, type CommitAnswer, type PackagesApi, type PackageState } from './api';
import { short } from './PackageList';

export interface CommitDialogProps {
  api: PackagesApi;
  repository: string;
  /** The package as its tab read it last: the dialog lists its changes, and commits on its branch and `HEAD`. */
  state: PackageState;
  /** Given the package's state after a commit, or null when the tab should read it again. */
  onState: (state: PackageState | null) => void;
  onClose: () => void;
}

const STATUS: Record<Change['status'], { label: string; color: string }> = {
  added: { label: 'Added', color: 'success' },
  modified: { label: 'Modified', color: 'warning' },
  deleted: { label: 'Deleted', color: 'error' },
  renamed: { label: 'Renamed', color: 'processing' },
};

/** The path of `path` within its package: the working folder's path without its first segment. */
const inside = (path: string) => path.slice(path.indexOf('/') + 1);

/** Whether `change` is ticked until the author says otherwise: not while an open tab holds it unsaved, its index `differs`, or it is too large to compare. */
const tickedAtFirst = (change: Change, open: ReadonlyMap<string, WorkingFile>) =>
  change.index !== 'differs' && open.get(change.path)?.dirty !== true && change.large !== true;

/**
 * The paths a commit of `sent` did not take whose bytes on disk changed meanwhile, as a hook changes them: each one the
 * package's state lists with another tag `after` than `before`, or lists only in one of them. None when `after` is null.
 */
function changedBeside(before: PackageState, after: PackageState | null, sent: readonly { path: string }[]): string[] {
  if (after === null) return [];
  const taken = new Set(sent.map((p) => p.path));
  const tagsBefore = new Map(before.changes.map((c) => [c.path, c.tag]));
  const tagsAfter = new Map(after.changes.map((c) => [c.path, c.tag]));
  return [...new Set([...tagsBefore.keys(), ...tagsAfter.keys()])].filter(
    (path) => !taken.has(path) && !(tagsBefore.has(path) && tagsAfter.has(path) && tagsBefore.get(path) === tagsAfter.get(path)),
  );
}

type Shown = { kind: 'choosing' } | { kind: 'refused'; error: string; output?: string } | { kind: 'committed'; answer: CommitAnswer; head: string | null };

/**
 * The commit dialog of a package: the identity git commits as, a required message, and every change of the package,
 * each ticked except a path an open tab holds with unsaved changes (which offers Show tab), a path whose index
 * `differs` (which cannot be ticked) and a path too large to compare. Commit commits the ticked paths at the tags they
 * have in `state`, unless a ticked path got unsaved changes in an open tab since the dialog opened: that path is then
 * unticked, and a note says so. A refusal says why, with what git and the hooks said, and the tab reads the package
 * again. A ticked renamed file goes with the path it was renamed from. After a commit, the open tabs without unsaved
 * changes of the paths a hook staged, rewrote or changed on disk, chosen or not, read them again; a plain commit closes
 * the dialog, and one a hook or a terminal changed stays open to say how, with what git and the hooks said.
 */
export function CommitDialog({ api, repository, state, onState, onClose }: CommitDialogProps) {
  const working = useWorkingFiles();
  // the dialog is modal: the open tabs' files as they were when it opened, and as Commit found a ticked path since
  const [open, setOpen] = useState(() => working.files());
  const [dirtied, setDirtied] = useState<string[]>([]);
  const [choices, setChoices] = useState<ReadonlyMap<string, boolean>>(new Map());
  const [message, setMessage] = useState('');
  const [committing, setCommitting] = useState(false);
  const [shown, setShown] = useState<Shown>({ kind: 'choosing' });
  const { notification } = App.useApp();
  const messageId = useId();

  const ticked = (change: Change) => change.index !== 'differs' && (choices.get(change.path) ?? tickedAtFirst(change, open));
  const chosen = state.changes.filter(ticked);
  const identity = state.identity;

  const commit = async () => {
    if (state.branch === null) return;
    // a hidden tab can get unsaved changes of its own while the dialog is open, as a mapping refreshed on opening does
    const now = working.files();
    const got = chosen.filter((change) => now.get(change.path)?.dirty === true && open.get(change.path)?.dirty !== true).map((change) => change.path);
    setDirtied(got);
    if (got.length > 0) {
      setOpen(new Map([...open, ...got.map((path) => [path, now.get(path)!] as const)]));
      setChoices((current) => new Map([...current, ...got.map((path) => [path, false] as const)]));
      return;
    }
    const head = state.head?.id ?? null;
    // a renamed file goes with the path it was renamed from: not on disk, unless that path is ticked as a change of its own
    const paths = chosen.flatMap((change) => [
      { path: change.path, tag: change.tag },
      ...(change.renamedFrom === undefined || chosen.some((c) => c.path === change.renamedFrom) ? [] : [{ path: change.renamedFrom, tag: null }]),
    ]);
    setCommitting(true);
    try {
      const answer = await api.commit(repository, { message, branch: state.branch, head, paths });
      onState(answer.package);
      const made = answer.commit;
      working.reload([...(made === null ? [] : [...made.staged, ...made.rewritten, ...made.changedOnDisk]), ...changedBeside(state, answer.package, paths)]);
      const touched = made !== null && (made.staged.length > 0 || made.rewritten.length > 0 || made.changedOnDisk.length > 0);
      const plain = made !== null && !touched && (made.parents[0] ?? null) === head && answer.problem === undefined;
      if (plain) {
        notification.success({ key: `commit:${repository}`, title: `Committed ${short(made.id)} ${made.subject}`, duration: 8, role: 'status', closable: { 'aria-label': 'Dismiss commit notification' } });
        onClose();
      } else {
        setShown({ kind: 'committed', answer, head });
      }
    } catch (error) {
      onState(null);
      if (error instanceof PackagesError) setShown({ kind: 'refused', error: error.message, ...(error.output === undefined ? {} : { output: error.output }) });
      else setShown({ kind: 'refused', error: error instanceof Error ? error.message : String(error) });
    } finally {
      setCommitting(false);
    }
  };

  if (shown.kind === 'committed') {
    return (
      <MountedModal title="Committed" onCancel={onClose} footer={<Button type="primary" onClick={onClose}>Close</Button>} width={720}>
        <Committed answer={shown.answer} head={shown.head} />
      </MountedModal>
    );
  }

  return (
    <MountedModal
      title={`Commit to ${state.branch ?? 'a branch'}`}
      onCancel={committing ? undefined : onClose}
      width={880}
      footer={[
        <Button key="cancel" disabled={committing} onClick={onClose}>
          Cancel
        </Button>,
        <Button key="commit" type="primary" loading={committing} disabled={message.trim() === '' || chosen.length === 0} onClick={() => void commit()}>
          Commit
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        {identity !== null && (
          <Typography.Text>
            Committing as {identity.name} &lt;{identity.email}&gt;
          </Typography.Text>
        )}
        {dirtied.length > 0 && (
          <Alert
            type="warning"
            showIcon
            title={`${dirtied.map(inside).join(', ')} got unsaved changes in an open tab meanwhile, so ${dirtied.length === 1 ? 'it is' : 'they are'} unticked: tick ${dirtied.length === 1 ? 'it' : 'them'} to commit the version on disk.`}
          />
        )}
        {shown.kind === 'refused' && (
          <Alert
            type="error"
            showIcon
            title="Nothing was committed"
            description={
              <Flex vertical gap="small">
                <span>{shown.error}</span>
                {shown.output !== undefined && shown.output !== '' && <Output text={shown.output} />}
              </Flex>
            }
          />
        )}
        <Form layout="vertical" requiredMark>
          <Form.Item label="Message" htmlFor={messageId} required style={{ marginBottom: 0 }}>
            <Input.TextArea id={messageId} autoSize={{ minRows: 3, maxRows: 10 }} value={message} onChange={(event) => setMessage(event.target.value)} />
          </Form.Item>
        </Form>
        <ProTable<Change>
          aria-label="Changes to commit"
          rowKey="path"
          size="small"
          dataSource={state.changes}
          search={false}
          options={false}
          toolBarRender={false}
          tableAlertRender={false}
          pagination={false}
          rowSelection={{
            selectedRowKeys: chosen.map((change) => change.path),
            getCheckboxProps: (change) => ({ disabled: change.index === 'differs', 'aria-label': `Commit ${inside(change.path)}` }),
            onSelect: (change, selected) => setChoices((current) => new Map(current).set(change.path, selected)),
            onSelectAll: (selected, _, changed) =>
              setChoices((current) => {
                const next = new Map(current);
                for (const change of changed) next.set(change.path, selected);
                return next;
              }),
          }}
          columns={[
            {
              title: 'File',
              dataIndex: 'path',
              render: (_, c) => (
                <Flex vertical>
                  <Typography.Text>{inside(c.path)}</Typography.Text>
                  {c.renamedFrom !== undefined && <Typography.Text type="secondary">from {inside(c.renamedFrom)}</Typography.Text>}
                </Flex>
              ),
            },
            { title: 'Change', dataIndex: 'status', render: (_, c) => <Tag color={STATUS[c.status].color}>{STATUS[c.status].label}</Tag> },
            {
              title: 'Note',
              key: 'note',
              render: (_, c) => {
                const file = open.get(c.path);
                return (
                  <Flex vertical gap={4} align="start">
                    {file?.dirty === true && (
                      <Flex gap="small" align="center" wrap>
                        <Typography.Text type="secondary">unsaved in an open tab</Typography.Text>
                        <Button
                          size="small"
                          onClick={() => {
                            onClose();
                            file.show();
                          }}
                        >
                          Show tab
                        </Button>
                      </Flex>
                    )}
                    {c.index === 'differs' && <Typography.Text type="secondary">staged in a terminal with other content: commit or unstage it there</Typography.Text>}
                    {c.large === true && <Typography.Text type="secondary">too large to compare</Typography.Text>}
                  </Flex>
                );
              },
            },
          ]}
        />
      </Flex>
    </MountedModal>
  );
}

/** What git and the hooks said. */
function Output({ text }: { text: string }) {
  return (
    <Typography.Paragraph style={{ marginBottom: 0 }}>
      <pre aria-label="What git and the hooks said" style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
        {text}
      </pre>
    </Typography.Paragraph>
  );
}

/** A commit a hook or a terminal changed: how, and what git and the hooks said. */
function Committed({ answer, head }: { answer: CommitAnswer; head: string | null }) {
  const made = answer.commit;
  const list = (paths: string[]) => paths.map(inside).join(', ');
  return (
    <Flex vertical gap="middle">
      {made === null ? (
        <Alert type="warning" showIcon title={answer.problem ?? 'git committed, but its commit could not be read back'} />
      ) : (
        <>
          <Typography.Text>
            Committed <Typography.Text code>{short(made.id)}</Typography.Text> {made.subject}
          </Typography.Text>
          {(made.parents[0] ?? null) !== head && (
            <Alert
              type="info"
              showIcon
              title="A commit was made in a terminal in between"
              description={`The new commit's parent is ${made.parents[0] === undefined ? 'none' : short(made.parents[0])}, not ${head === null ? 'the empty branch' : short(head)}, which you reviewed. Both commits are on the branch; nothing is lost.`}
            />
          )}
          {made.staged.length > 0 && (
            <Alert
              type="warning"
              showIcon
              title="A hook added more files to the commit"
              description={`The commit also changes ${list(made.staged)}, which you did not choose.`}
            />
          )}
          {made.rewritten.length > 0 && (
            <Alert
              type="warning"
              showIcon
              title="A hook rewrote files you chose"
              description={`The commit holds the hook's version of ${list(made.rewritten)}, not the one you reviewed.`}
            />
          )}
          {made.changedOnDisk.length > 0 && (
            <Alert
              type="warning"
              showIcon
              title="A hook changed files you chose on disk"
              description={`The commit holds the version of ${list(made.changedOnDisk)} you reviewed; the file on disk holds the hook's, which is not committed.`}
            />
          )}
          {(made.staged.length > 0 || made.rewritten.length > 0 || made.changedOnDisk.length > 0) && (
            <Typography.Text type="secondary">
              Open tabs without unsaved changes show these files as they are now.
              {(made.staged.length > 0 || made.rewritten.length > 0) &&
                ' Until they are committed or unstaged in a terminal, the files a hook staged may read as staged in a terminal with other content.'}
            </Typography.Text>
          )}
          {answer.problem !== undefined && <Alert type="warning" showIcon title={answer.problem} />}
        </>
      )}
      {answer.output !== '' && <Output text={answer.output} />}
    </Flex>
  );
}
