import { Alert, Button, Descriptions, Flex, Spin, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useText, type Texts } from '../language';
import { MountedModal } from '../MountedModal';
import { PackagesError, type Commit, type DeployAnswer, type NodeDeploy, type PackagesApi, type PackageState } from './api';
import { code, deployedStatus, short } from './parts';

export interface DeployDialogProps {
  api: PackagesApi;
  repository: string;
  /** The commit to deploy, from the package's state or its history. */
  commit: Pick<Commit, 'id' | 'subject'>;
  /** The package as its tab read it last: the node it names, and its uncommitted changes. */
  state: PackageState;
  /** Whether the server takes writes; false under RAIL8_READ_ONLY=true, where only the check runs. */
  writable: boolean;
  /** Given the package's state after a check or a deploy, or null when the tab should read it again. */
  onState: (state: PackageState | null) => void;
  onClose: () => void;
}

/** A refusal as the dialog shows it: its status, its text and the problems the node named. */
interface Refused {
  status: number;
  error: string;
  problems?: string[];
}

type Shown =
  | { kind: 'checking' }
  | { kind: 'check-refused'; refused: Refused }
  | { kind: 'checked'; answer: DeployAnswer }
  | { kind: 'deploying'; answer: DeployAnswer }
  | { kind: 'deploy-refused'; answer: DeployAnswer; refused: Refused }
  | { kind: 'lost'; refused: Refused; now: PackageState | null; problem?: string }
  | { kind: 'deployed'; answer: DeployAnswer };

const refusedOf = (error: unknown): Refused =>
  error instanceof PackagesError
    ? { status: error.status, error: error.message, ...(error.problems === undefined ? {} : { problems: error.problems }) }
    : { status: 0, error: error instanceof Error ? error.message : String(error) };

/** The flows a deploy adds, swaps and removes, each list in a row of its own, or that it changes none. */
function FlowChanges({ deploy, done }: { deploy: NodeDeploy; done: boolean }) {
  const { t } = useText();
  const list = (flows: string[]) => (flows.length === 0 ? '-' : flows.join(', '));
  if (deploy.added.length + deploy.swapped.length + deploy.removed.length === 0) {
    return <Typography.Text>{done ? t('packages.deploy.flows.unchanged') : t('packages.deploy.flows.none')}</Typography.Text>;
  }
  return (
    <Descriptions
      size="small"
      bordered
      column={1}
      items={[
        { key: 'added', label: done ? t('packages.deploy.added') : t('packages.deploy.adds'), children: list(deploy.added) },
        { key: 'swapped', label: done ? t('packages.deploy.swapped') : t('packages.deploy.swaps'), children: list(deploy.swapped) },
        { key: 'removed', label: done ? t('packages.deploy.removed') : t('packages.deploy.removes'), children: list(deploy.removed) },
      ]}
    />
  );
}

/** A refusal: its text, and every problem the node named. */
function RefusalAlert({ title, refused }: { title: string; refused: Refused }) {
  const { t } = useText();
  return (
    <Alert
      type="error"
      showIcon
      title={title}
      description={
        <Flex vertical gap="small">
          <span>{refused.error}</span>
          {refused.problems !== undefined && refused.problems.length > 0 && (
            <ul aria-label={t('packages.deploy.problems')} style={{ margin: 0 }}>
              {refused.problems.map((problem, n) => (
                <li key={n}>{problem}</li>
              ))}
            </ul>
          )}
        </Flex>
      }
    />
  );
}

/** That the check passed, and what the commit the node runs now is, in `texts`' language. */
const passed = ({ t }: Texts, replaces: string, host: string) =>
  replaces === 'none' ? t('packages.deploy.passed.first', { host }) : t('packages.deploy.passed', { commit: short(replaces), host });

/**
 * The deploy dialog of a commit of a package: it checks the commit on the node first, and shows what the deploy would
 * add, swap and remove, and what it replaces, or every problem, which ends it with nothing changed on the node. Deploy
 * then deploys the commit, expecting the node to still run what the check found, and shows the node's answer. When
 * the node's commit changed since the check, it says so and offers to check again. When the outcome is unknown (504,
 * or no answer from the server at all), the dialog reads what the node runs before it offers to check again. Under
 * RAIL8_READ_ONLY=true only the check runs. Every answer that carries the package's state redraws the tab.
 */
export function DeployDialog({ api, repository, commit, state, writable, onState, onClose }: DeployDialogProps) {
  const [shown, setShown] = useState<Shown>({ kind: 'checking' });
  const [checks, setChecks] = useState(0);
  const host = state.deployed.host;
  const texts = useText();
  const { t, rich } = texts;
  // the tab's latest onState, so that a new one on each of its renders starts no new check
  const latest = useRef(onState);
  useEffect(() => {
    latest.current = onState;
  });
  const redraw = (next: PackageState | null) => latest.current(next);
  // the check under way and what it asked: an effect run again for the same ask, as StrictMode does, waits for that
  // check, since the node refuses a second one while the first holds its deploy lock
  const started = useRef<{ api: PackagesApi; repository: string; commit: string; checks: number; answer: Promise<DeployAnswer> } | null>(null);

  useEffect(() => {
    let current = true;
    const asked = started.current;
    const same = asked !== null && asked.api === api && asked.repository === repository && asked.commit === commit.id && asked.checks === checks;
    const answer = same ? asked.answer : api.deploy(repository, { commit: commit.id, check: true });
    started.current = { api, repository, commit: commit.id, checks, answer };
    answer.then(
      (answer) => {
        if (!current) return;
        latest.current(answer.package);
        setShown({ kind: 'checked', answer });
      },
      (error: unknown) => current && setShown({ kind: 'check-refused', refused: refusedOf(error) }),
    );
    return () => {
      current = false;
    };
  }, [api, repository, commit.id, checks]);

  const deploy = async (checked: DeployAnswer) => {
    setShown({ kind: 'deploying', answer: checked });
    try {
      const answer = await api.deploy(repository, { commit: commit.id, check: false, expect: checked.replaces });
      redraw(answer.package);
      setShown({ kind: 'deployed', answer });
    } catch (error) {
      const refused = refusedOf(error);
      // 504, or no answer from the server at all (0): the node may have deployed, so the outcome is unknown
      if (refused.status !== 504 && refused.status !== 0) {
        redraw(null);
        setShown({ kind: 'deploy-refused', answer: checked, refused });
        return;
      }
      // the outcome is unknown: what the node runs now is read before a new try is offered
      setShown({ kind: 'lost', refused, now: null });
      try {
        const now = await api.get(repository);
        redraw(now);
        setShown({ kind: 'lost', refused, now });
      } catch (failed) {
        setShown({ kind: 'lost', refused, now: null, problem: refusedOf(failed).error });
      }
    }
  };

  const checkAgain = () => {
    setShown({ kind: 'checking' });
    setChecks((n) => n + 1);
  };
  const close = <Button key="close" onClick={onClose}>{t('packages.close')}</Button>;
  const title = t('packages.deploy.title', { commit: short(commit.id), host });

  let body;
  let footer;
  switch (shown.kind) {
    case 'checking':
      body = (
        <Flex gap="small" align="center">
          <Spin size="small" />
          <Typography.Text>{t('packages.deploy.checking', { host })}</Typography.Text>
        </Flex>
      );
      footer = [close];
      break;
    case 'check-refused':
      body = <RefusalAlert title={shown.refused.problems === undefined ? t('packages.deploy.check.refused') : t('packages.deploy.check.problems')} refused={shown.refused} />;
      footer = [close];
      break;
    case 'checked':
    case 'deploying':
    case 'deploy-refused': {
      const { answer } = shown;
      body = (
        <Flex vertical gap="middle">
          {shown.kind === 'deploy-refused' && (
            <RefusalAlert title={shown.refused.status === 412 ? t('packages.deploy.stale') : t('packages.deploy.refused')} refused={shown.refused} />
          )}
          <Typography.Text>{passed(texts, answer.replaces, host)}</Typography.Text>
          <FlowChanges deploy={answer.deploy} done={false} />
          {state.changes.length > 0 && <Typography.Text type="secondary">{t('packages.deploy.uncommitted')}</Typography.Text>}
          {!writable && <Typography.Text type="secondary">{t('packages.deploy.readOnly')}</Typography.Text>}
        </Flex>
      );
      footer =
        shown.kind === 'deploy-refused'
          ? [close, <Button key="check" type="primary" onClick={checkAgain}>{t('packages.deploy.checkAgain')}</Button>]
          : [
              <Button key="cancel" disabled={shown.kind === 'deploying'} onClick={onClose}>
                {t('frame.cancel')}
              </Button>,
              <Button key="deploy" type="primary" disabled={!writable} loading={shown.kind === 'deploying'} onClick={() => void deploy(answer)}>
                {t('packages.deploy')}
              </Button>,
            ];
      break;
    }
    case 'lost': {
      const now = shown.now;
      body = (
        <Flex vertical gap="middle">
          <Alert type="warning" showIcon title={t('packages.deploy.lost')} description={shown.refused.error} />
          {now === null && shown.problem === undefined && (
            <Flex gap="small" align="center">
              <Spin size="small" />
              <Typography.Text>{t('packages.deploy.lost.reading', { host })}</Typography.Text>
            </Flex>
          )}
          {shown.problem !== undefined && <Alert type="error" showIcon title={t('packages.deploy.lost.unread', { host })} description={shown.problem} />}
          {now !== null && (
            <Typography.Text>
              {now.deployed.node === 'answered' && now.deployed.relation !== 'none'
                ? rich('packages.deploy.lost.running', { code, host, status: deployedStatus(texts, now).text, commit: short(now.deployed.commit) })
                : t('packages.deploy.lost.reads', { host, status: deployedStatus(texts, now).text })}
            </Typography.Text>
          )}
        </Flex>
      );
      footer = [close, <Button key="check" type="primary" disabled={now === null && shown.problem === undefined} onClick={checkAgain}>{t('packages.deploy.checkAgain')}</Button>];
      break;
    }
    case 'deployed': {
      const { answer } = shown;
      body = (
        <Flex vertical gap="middle">
          <Typography.Text>
            {answer.replaces === 'none'
              ? rich('packages.deploy.runs', { code, host, commit: short(commit.id) })
              : rich('packages.deploy.runsInPlace', { code, host, commit: short(commit.id), replaced: short(answer.replaces) })}
          </Typography.Text>
          <FlowChanges deploy={answer.deploy} done />
          {answer.package === null && <Alert type="warning" showIcon title={answer.problem ?? t('packages.deploy.unreadAfter')} />}
        </Flex>
      );
      footer = [<Button key="close" type="primary" onClick={onClose}>{t('packages.close')}</Button>];
      break;
    }
  }

  return (
    <MountedModal title={title} onCancel={shown.kind === 'deploying' ? undefined : onClose} width={720} footer={footer}>
      <Flex vertical gap="middle">
        <Typography.Text type="secondary">{commit.subject}</Typography.Text>
        {body}
      </Flex>
    </MountedModal>
  );
}
