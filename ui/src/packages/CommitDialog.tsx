import { ProTable } from '@ant-design/pro-components';
import { Alert, App, Button, Flex, Form, Input, Tag, Typography } from 'antd';
import { useId, useState } from 'react';
import { say, useText } from '../language';
import { MountedModal } from '../MountedModal';
import { useWorkingFiles, type WorkingFile } from '../workingFiles';
import { PackagesError, type Change, type Commit, type CommitAnswer, type PackagesApi, type PackageState } from './api';
import { CHANGE } from './data';
import { code, short } from './parts';

export interface CommitDialogProps {
  api: PackagesApi;
  repository: string;
  /** The package as its tab read it last: the dialog lists its changes, and commits on its branch and `HEAD`. */
  state: PackageState;
  /** Given the package's state after a commit, or null when the tab should read it again. */
  onState: (state: PackageState | null) => void;
  onClose: () => void;
  /** Opens the deploy dialog of a commit the dialog made, on Commit and deploy. */
  onDeploy: (commit: Pick<Commit, 'id' | 'subject'>) => void;
}

/** The colour of the tag of each change. */
const COLOR: Record<Change['status'], string> = { added: 'success', modified: 'warning', deleted: 'error', renamed: 'processing' };

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

type Shown = { kind: 'choosing' } | { kind: 'refused'; error: string; output?: string } | { kind: 'committed'; answer: CommitAnswer; head: string | null; deploy: boolean };

/**
 * The commit dialog of a package: the identity git commits as, a required message, and every change of the package,
 * each ticked except a path an open tab holds with unsaved changes (which offers Show tab), a path whose index
 * `differs` (which cannot be ticked) and a path too large to compare. Commit commits the ticked paths at the tags they
 * have in `state`, unless a ticked path got unsaved changes in an open tab since the dialog opened: that path is then
 * unticked, and a note says so. A refusal says why, with what git and the hooks said, and the tab reads the package
 * again. A ticked renamed file goes with the path it was renamed from. After a commit, the open tabs without unsaved
 * changes of the paths a hook staged, rewrote or changed on disk, chosen or not, read them again; a plain commit closes
 * the dialog, and one a hook or a terminal changed stays open to say how, with what git and the hooks said. Commit and
 * deploy commits the same way, then goes on to the deploy dialog of the new commit: at once after a plain commit, else
 * from Deploy this commit… once the dialog has said how the commit was changed.
 */
export function CommitDialog({ api, repository, state, onState, onClose, onDeploy }: CommitDialogProps) {
  const working = useWorkingFiles();
  // the dialog is modal: the open tabs' files as they were when it opened, and as Commit found a ticked path since
  const [open, setOpen] = useState(() => working.files());
  const [dirtied, setDirtied] = useState<string[]>([]);
  const [choices, setChoices] = useState<ReadonlyMap<string, boolean>>(new Map());
  const [message, setMessage] = useState('');
  const [committing, setCommitting] = useState<'commit' | 'deploy' | null>(null);
  const [shown, setShown] = useState<Shown>({ kind: 'choosing' });
  const { notification } = App.useApp();
  const messageId = useId();
  const texts = useText();
  const { t } = texts;

  const ticked = (change: Change) => change.index !== 'differs' && (choices.get(change.path) ?? tickedAtFirst(change, open));
  const chosen = state.changes.filter(ticked);
  const identity = state.identity;

  const commit = async (deploy: boolean) => {
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
    setCommitting(deploy ? 'deploy' : 'commit');
    try {
      const answer = await api.commit(repository, { message, branch: state.branch, head, paths });
      onState(answer.package);
      const made = answer.commit;
      working.reload([...(made === null ? [] : [...made.staged, ...made.rewritten, ...made.changedOnDisk]), ...changedBeside(state, answer.package, paths)]);
      const touched = made !== null && (made.staged.length > 0 || made.rewritten.length > 0 || made.changedOnDisk.length > 0);
      const plain = made !== null && !touched && (made.parents[0] ?? null) === head && answer.problem === undefined;
      if (plain) {
        notification.success({
          key: `commit:${repository}`,
          title: t('packages.commit.done', { commit: short(made.id), subject: made.subject }),
          duration: 8,
          role: 'status',
          closable: { 'aria-label': t('packages.commit.dismiss') },
        });
        onClose();
        if (deploy) onDeploy(made);
      } else {
        setShown({ kind: 'committed', answer, head, deploy });
      }
    } catch (error) {
      onState(null);
      if (error instanceof PackagesError) setShown({ kind: 'refused', error: error.message, ...(error.output === undefined ? {} : { output: error.output }) });
      else setShown({ kind: 'refused', error: error instanceof Error ? error.message : String(error) });
    } finally {
      setCommitting(null);
    }
  };

  if (shown.kind === 'committed') {
    const made = shown.answer.commit;
    const footer =
      shown.deploy && made !== null
        ? [
            <Button key="close" onClick={onClose}>
              {t('packages.close')}
            </Button>,
            <Button
              key="deploy"
              type="primary"
              onClick={() => {
                onClose();
                onDeploy(made);
              }}
            >
              {t('packages.deploy.thisCommit')}
            </Button>,
          ]
        : [
            <Button key="close" type="primary" onClick={onClose}>
              {t('packages.close')}
            </Button>,
          ];
    return (
      <MountedModal title={t('packages.commit.committed')} onCancel={onClose} footer={footer} width={720}>
        <Committed answer={shown.answer} head={shown.head} />
      </MountedModal>
    );
  }

  return (
    <MountedModal
      title={state.branch === null ? t('packages.commit.title.noBranch') : t('packages.commit.title', { branch: state.branch })}
      onCancel={committing !== null ? undefined : onClose}
      width={880}
      footer={[
        <Button key="cancel" disabled={committing !== null} onClick={onClose}>
          {t('frame.cancel')}
        </Button>,
        <Button key="deploy" loading={committing === 'deploy'} disabled={committing === 'commit' || message.trim() === '' || chosen.length === 0} onClick={() => void commit(true)}>
          {t('packages.commit.andDeploy')}
        </Button>,
        <Button key="commit" type="primary" loading={committing === 'commit'} disabled={committing === 'deploy' || message.trim() === '' || chosen.length === 0} onClick={() => void commit(false)}>
          {t('packages.commit')}
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        {identity !== null && (
          <Typography.Text>{t('packages.commit.as', { identity: `${identity.name} <${identity.email}>` })}</Typography.Text>
        )}
        {dirtied.length > 0 && (
          <Alert
            type="warning"
            showIcon
            title={t('packages.commit.dirtied', { paths: dirtied.map(inside).join(', '), count: dirtied.length })}
          />
        )}
        {shown.kind === 'refused' && (
          <Alert
            type="error"
            showIcon
            title={t('packages.commit.refused')}
            description={
              <Flex vertical gap="small">
                <span>{shown.error}</span>
                {shown.output !== undefined && shown.output !== '' && <Output text={shown.output} />}
              </Flex>
            }
          />
        )}
        <Form layout="vertical" requiredMark>
          <Form.Item label={t('packages.commit.message')} htmlFor={messageId} required style={{ marginBottom: 0 }}>
            <Input.TextArea id={messageId} autoSize={{ minRows: 3, maxRows: 10 }} value={message} onChange={(event) => setMessage(event.target.value)} />
          </Form.Item>
        </Form>
        <ProTable<Change>
          aria-label={t('packages.commit.changes.chosen')}
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
            getCheckboxProps: (change) => ({ disabled: change.index === 'differs', 'aria-label': t('packages.commit.path', { path: inside(change.path) }) }),
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
              title: t('packages.file'),
              dataIndex: 'path',
              render: (_, c) => (
                <Flex vertical>
                  <Typography.Text>{inside(c.path)}</Typography.Text>
                  {c.renamedFrom !== undefined && <Typography.Text type="secondary">{t('packages.renamedFrom', { path: inside(c.renamedFrom) })}</Typography.Text>}
                </Flex>
              ),
            },
            { title: t('packages.change'), dataIndex: 'status', render: (_, c) => <Tag color={COLOR[c.status]}>{say(texts, CHANGE[c.status].label)}</Tag> },
            {
              title: t('packages.commit.note'),
              key: 'note',
              render: (_, c) => {
                const file = open.get(c.path);
                return (
                  <Flex vertical gap={4} align="start">
                    {file?.dirty === true && (
                      <Flex gap="small" align="center" wrap>
                        <Typography.Text type="secondary">{t('packages.note.unsaved')}</Typography.Text>
                        <Button
                          size="small"
                          onClick={() => {
                            onClose();
                            file.show();
                          }}
                        >
                          {t('packages.showTab')}
                        </Button>
                      </Flex>
                    )}
                    {c.index === 'differs' && <Typography.Text type="secondary">{t('packages.commit.note.differs')}</Typography.Text>}
                    {c.large === true && <Typography.Text type="secondary">{t('packages.note.large')}</Typography.Text>}
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
  const { t } = useText();
  return (
    <Typography.Paragraph style={{ marginBottom: 0 }}>
      <pre aria-label={t('packages.commit.output')} style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
        {text}
      </pre>
    </Typography.Paragraph>
  );
}

/** A commit a hook or a terminal changed: how, and what git and the hooks said. */
function Committed({ answer, head }: { answer: CommitAnswer; head: string | null }) {
  const made = answer.commit;
  const { t, rich } = useText();
  const list = (paths: string[]) => paths.map(inside).join(', ');
  return (
    <Flex vertical gap="middle">
      {made === null ? (
        <Alert type="warning" showIcon title={answer.problem ?? t('packages.commit.unreadBack')} />
      ) : (
        <>
          <Typography.Text>{rich('packages.commit.made', { code, commit: short(made.id), subject: made.subject })}</Typography.Text>
          {(made.parents[0] ?? null) !== head && (
            <Alert
              type="info"
              showIcon
              title={t('packages.commit.between')}
              description={t('packages.commit.between.description', {
                parent: made.parents[0] === undefined ? '-' : short(made.parents[0]),
                head: head === null ? '-' : short(head),
                parentKind: made.parents[0] === undefined ? 'none' : 'commit',
                headKind: head === null ? 'empty' : 'commit',
              })}
            />
          )}
          {made.staged.length > 0 && (
            <Alert type="warning" showIcon title={t('packages.commit.staged')} description={t('packages.commit.staged.description', { paths: list(made.staged) })} />
          )}
          {made.rewritten.length > 0 && (
            <Alert type="warning" showIcon title={t('packages.commit.rewritten')} description={t('packages.commit.rewritten.description', { paths: list(made.rewritten) })} />
          )}
          {made.changedOnDisk.length > 0 && (
            <Alert type="warning" showIcon title={t('packages.commit.changedOnDisk')} description={t('packages.commit.changedOnDisk.description', { paths: list(made.changedOnDisk) })} />
          )}
          {(made.staged.length > 0 || made.rewritten.length > 0 || made.changedOnDisk.length > 0) && (
            <Typography.Text type="secondary">
              {made.staged.length > 0 || made.rewritten.length > 0 ? t('packages.commit.reloaded.staged') : t('packages.commit.reloaded')}
            </Typography.Text>
          )}
          {answer.problem !== undefined && <Alert type="warning" showIcon title={answer.problem} />}
        </>
      )}
      {answer.output !== '' && <Output text={answer.output} />}
    </Flex>
  );
}
