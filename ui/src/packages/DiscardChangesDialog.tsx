import { Alert, App, Button, Checkbox, Flex, Form, Input, Table, Typography } from 'antd';
import { useId, useState } from 'react';
import { msg, say, useText, type Message } from '../language';
import { MountedModal } from '../MountedModal';
import { useWorkingFiles } from '../workingFiles';
import { PackagesError, type Change, type DiscardAnswer, type PackagesApi, type PackageState } from './api';
import { ChangeBadge } from './parts';
import { chosenPaths } from './rules';

export interface DiscardChangesDialogProps {
  api: PackagesApi;
  repository: string;
  /** The package as its tab read it last: the dialog says what happens to each chosen file as this state shows it. */
  state: PackageState;
  /** The changes the author chose in the Changes table, in its order. */
  picked: readonly Change[];
  /** Given the package's state after a discard, or null when the tab should read it again. */
  onState: (state: PackageState | null) => void;
  /** Called once a discard has run, whether or not it left files; never on a refusal, after which the dialog stays to be confirmed again. */
  onDiscarded: () => void;
  onClose: () => void;
}

/** The path of `path` within its package: the working folder's path without its first segment. */
const inside = (path: string) => path.slice(path.indexOf('/') + 1);

/** What a discard does to a file: it gets the last commit's version, comes back from the last commit, or is deleted. */
type Action = 'reverted' | 'restored' | 'deleted';

/** What a discard does to a file, in words, and in words that add that the file is unstaged too. */
const ACTION: Record<Action, { alone: Message; unstaged: Message }> = {
  reverted: { alone: msg('packages.discard.reverted'), unstaged: msg('packages.discard.reverted.unstaged') },
  restored: { alone: msg('packages.discard.restored'), unstaged: msg('packages.discard.restored.unstaged') },
  deleted: { alone: msg('packages.discard.deleted'), unstaged: msg('packages.discard.deleted.unstaged') },
};

/** What a discard does to the file of `change`, a row of the package's changes. */
function actionOf(change: Change): Action {
  if (change.status === 'modified') return 'reverted';
  if (change.status === 'deleted') return 'restored';
  return 'deleted';
}

/**
 * One file the dialog lists: a picked change, and for a rename the path it was renamed from, which gets the last
 * commit's version when it is on disk as a change of its own, and comes back otherwise.
 */
interface Listed {
  change: Change;
  from?: { path: string; action: Exclude<Action, 'deleted'> };
}

/** The files a discard of `picked` changes, a rename as one file with the path it was renamed from. */
function listedOf(picked: readonly Change[], changes: readonly Change[]): Listed[] {
  const renamedFrom = new Set(picked.flatMap((c) => (c.renamedFrom === undefined ? [] : [c.renamedFrom])));
  return picked
    .filter((change) => !renamedFrom.has(change.path))
    .map((change) => {
      if (change.renamedFrom === undefined) return { change };
      const own = changes.some((c) => c.path === change.renamedFrom);
      return { change, from: { path: change.renamedFrom, action: own ? 'reverted' : 'restored' } };
    });
}

type Shown = { kind: 'choosing' } | { kind: 'refused'; error: string; failed: boolean; output?: string } | { kind: 'discarded'; answer: DiscardAnswer };

/**
 * The dialog of Discard changes…: every file the discard changes, each with what happens to it, and above them an alert
 * with Show tab for each listed file an open tab holds with unsaved edits. One file is confirmed with a tick box, more
 * than one by typing their count; Discard stays disabled until then, and the confirmation is dropped whenever a listed
 * file, what happens to it, its tag or an unsaved tab changes. Focus starts on Cancel, and Enter sends nothing. Discard
 * first asks the open tabs again: when a listed file got unsaved edits meanwhile, it is named, and nothing is sent.
 * After any answer the open tabs without unsaved edits of the listed files read them again; a discard that left nothing
 * closes the dialog and says what it discarded, one that left files says which and why, and a refusal says why while
 * the tab reads the package again.
 */
export function DiscardChangesDialog({ api, repository, state, picked, onState, onDiscarded, onClose }: DiscardChangesDialogProps) {
  const working = useWorkingFiles();
  // the dialog is modal: the open tabs' files as they were when it opened, and as Discard found them since
  const [open, setOpen] = useState(() => working.files());
  const [dirtied, setDirtied] = useState<string[]>([]);
  const [ticked, setTicked] = useState<string | null>(null);
  const [typed, setTyped] = useState<{ key: string; text: string }>({ key: '', text: '' });
  const [discarding, setDiscarding] = useState(false);
  const [shown, setShown] = useState<Shown>({ kind: 'choosing' });
  const { notification } = App.useApp();
  const countId = useId();
  const texts = useText();
  const { t } = texts;

  const paths = chosenPaths(picked, state.changes);
  const listed = listedOf(picked, state.changes);
  const unsaved = paths.filter((p) => open.get(p.path)?.dirty === true).map((p) => p.path);
  // what the author confirms: a confirmation given for another list, action, tag or set of unsaved tabs is no confirmation
  const key = JSON.stringify([paths, listed.map((l) => [l.change.status, l.change.large === true, l.from?.action]), unsaved]);
  const n = listed.length;
  const confirmed = n === 1 ? ticked === key : n > 1 && typed.key === key && typed.text.trim() === String(n);

  const discard = async () => {
    const now = working.files();
    const got = paths.filter((p) => now.get(p.path)?.dirty === true && open.get(p.path)?.dirty !== true).map((p) => p.path);
    if (got.length > 0) {
      setDirtied(got);
      setOpen(now);
      return;
    }
    setDiscarding(true);
    try {
      const answer = await api.discard(repository, { head: state.head?.id ?? null, paths });
      onState(answer.package);
      onDiscarded();
      if (answer.left.length === 0 && answer.problem === undefined) {
        notification.success({
          key: `discard:${repository}`,
          title: t('packages.discard.done', { paths: answer.discarded.map(inside).join(', ') }),
          duration: 8,
          role: 'status',
          closable: { 'aria-label': t('packages.discard.dismiss') },
        });
        onClose();
      } else {
        setShown({ kind: 'discarded', answer });
      }
    } catch (error) {
      onState(null);
      if (error instanceof PackagesError) setShown({ kind: 'refused', error: error.message, failed: error.status >= 500, ...(error.output === undefined ? {} : { output: error.output }) });
      else setShown({ kind: 'refused', error: error instanceof Error ? error.message : String(error), failed: true });
    } finally {
      working.reload(paths.map((p) => p.path));
      setDiscarding(false);
    }
  };

  if (shown.kind === 'discarded') {
    const { answer } = shown;
    return (
      <MountedModal
        title={answer.left.length > 0 ? t('packages.discard.inPart') : t('packages.discard.discarded')}
        onCancel={onClose}
        width={720}
        footer={[
          <Button key="close" type="primary" onClick={onClose}>
            {t('packages.close')}
          </Button>,
        ]}
      >
        <Flex vertical gap="middle">
          <Typography.Text>
            {answer.discarded.length === 0 ? t('packages.discard.noneDone') : t('packages.discard.doneSentence', { paths: answer.discarded.map(inside).join(', ') })}
          </Typography.Text>
          {answer.left.length > 0 && (
            <Alert type="warning" showIcon title={t('packages.discard.left', { paths: answer.left.map(inside).join(', ') })} description={answer.problem !== undefined && <Output text={answer.problem} />} />
          )}
          {answer.left.length === 0 && answer.problem !== undefined && <Alert type="warning" showIcon title={answer.problem} />}
        </Flex>
      </MountedModal>
    );
  }

  return (
    <MountedModal
      title={t('frame.discard.discard')}
      onCancel={discarding ? undefined : onClose}
      width={720}
      footer={[
        <Button key="cancel" autoFocus disabled={discarding} onClick={onClose}>
          {t('frame.cancel')}
        </Button>,
        <Button key="discard" type="primary" danger loading={discarding} disabled={!confirmed} onClick={() => void discard()}>
          {t('packages.discard.files', { count: n })}
        </Button>,
      ]}
    >
      <Flex vertical gap="middle">
        {dirtied.length > 0 && (
          <Alert
            type="warning"
            showIcon
            title={t('packages.discard.dirtied', { paths: dirtied.map(inside).join(', ') })}
          />
        )}
        {shown.kind === 'refused' && (
          <Alert
            type="error"
            showIcon
            title={shown.failed ? t('packages.discard.failed') : t('packages.discard.refused')}
            description={
              <Flex vertical gap="small">
                <span>{shown.error}</span>
                {shown.output !== undefined && shown.output !== '' && <Output text={shown.output} />}
              </Flex>
            }
          />
        )}
        {unsaved.map((path) => (
          <Alert
            key={path}
            type="warning"
            showIcon
            title={t('packages.discard.unsaved', { path: inside(path) })}
            description={t('packages.discard.unsaved.description')}
            action={
              <Button
                size="small"
                onClick={() => {
                  onClose();
                  open.get(path)?.show();
                }}
              >
                {t('packages.showTab')}
              </Button>
            }
          />
        ))}
        <Typography.Text>{t('packages.discard.intro')}</Typography.Text>
        <Table<Listed>
          aria-label={t('packages.discard.list')}
          rowKey={(l) => l.change.path}
          size="small"
          dataSource={listed}
          pagination={false}
          columns={[
            {
              title: t('packages.file'),
              key: 'file',
              render: (_, { change, from }) => (
                <Flex vertical>
                  <Typography.Text>{inside(change.path)}</Typography.Text>
                  {from !== undefined && <Typography.Text type="secondary">{t('packages.renamedFrom', { path: inside(from.path) })}</Typography.Text>}
                </Flex>
              ),
            },
            { title: t('packages.change'), key: 'change', width: 112, render: (_, { change }) => <ChangeBadge status={change.status} /> },
            {
              title: t('packages.discard.action'),
              key: 'action',
              render: (_, { change, from }) => (
                <Flex vertical>
                  <Typography.Text>{say(texts, ACTION[actionOf(change)][change.index === 'staged' ? 'unstaged' : 'alone'])}</Typography.Text>
                  {from !== undefined && (
                    <Typography.Text>
                      {from.action === 'reverted' ? t('packages.discard.from.reverted', { path: inside(from.path) }) : t('packages.discard.from.restored', { path: inside(from.path) })}
                    </Typography.Text>
                  )}
                  {change.large === true && <Typography.Text type="warning">{t('packages.discard.large')}</Typography.Text>}
                </Flex>
              ),
            },
          ]}
        />
        {n === 1 && (
          <Checkbox checked={ticked === key} onChange={(event) => setTicked(event.target.checked ? key : null)}>
            {t('packages.discard.confirm.one')}
          </Checkbox>
        )}
        {n > 1 && (
          <Form layout="vertical" onFinish={() => undefined}>
            <Form.Item label={t('packages.discard.confirm.many', { typed: String(n), count: n })} htmlFor={countId} style={{ marginBottom: 0 }}>
              <Input id={countId} inputMode="numeric" autoComplete="off" value={typed.key === key ? typed.text : ''} onChange={(event) => setTyped({ key, text: event.target.value })} />
            </Form.Item>
          </Form>
        )}
      </Flex>
    </MountedModal>
  );
}

/** What git said. */
function Output({ text }: { text: string }) {
  const { t } = useText();
  return (
    <Typography.Paragraph style={{ marginBottom: 0 }}>
      <pre aria-label={t('packages.gitSaid')} style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
        {text}
      </pre>
    </Typography.Paragraph>
  );
}
