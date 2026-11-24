import { Alert, Button, Descriptions, Flex, Spin, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { MountedModal } from '../MountedModal';
import { PackagesError, type Commit, type DeployAnswer, type NodeDeploy, type PackagesApi, type PackageState } from './api';
import { deployedStatus, short } from './parts';

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
  const list = (flows: string[]) => (flows.length === 0 ? '-' : flows.join(', '));
  if (deploy.added.length + deploy.swapped.length + deploy.removed.length === 0) {
    return <Typography.Text>{done ? 'No flow changed: every flow runs on as it was.' : 'No flow changes: every flow runs on as it is.'}</Typography.Text>;
  }
  return (
    <Descriptions
      size="small"
      bordered
      column={1}
      items={[
        { key: 'added', label: done ? 'Added' : 'Adds', children: list(deploy.added) },
        { key: 'swapped', label: done ? 'Swapped' : 'Swaps', children: list(deploy.swapped) },
        { key: 'removed', label: done ? 'Removed' : 'Removes', children: list(deploy.removed) },
      ]}
    />
  );
}

/** A refusal: its text, and every problem the node named. */
function RefusalAlert({ title, refused }: { title: string; refused: Refused }) {
  return (
    <Alert
      type="error"
      showIcon
      title={title}
      description={
        <Flex vertical gap="small">
          <span>{refused.error}</span>
          {refused.problems !== undefined && refused.problems.length > 0 && (
            <ul aria-label="Problems" style={{ margin: 0 }}>
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

/** What the commit the node runs now is, in words. */
const replacing = (replaces: string, host: string) =>
  replaces === 'none' ? `${host} runs nothing of this repository yet.` : `It replaces ${short(replaces)}, which ${host} runs now.`;

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
  const close = <Button key="close" onClick={onClose}>Close</Button>;
  const title = `Deploy ${short(commit.id)} to ${host}`;

  let body;
  let footer;
  switch (shown.kind) {
    case 'checking':
      body = (
        <Flex gap="small" align="center">
          <Spin size="small" />
          <Typography.Text>Checking the commit on {host}…</Typography.Text>
        </Flex>
      );
      footer = [close];
      break;
    case 'check-refused':
      body = <RefusalAlert title={shown.refused.problems === undefined ? 'The check was refused: nothing was deployed' : 'The check found problems: nothing was deployed'} refused={shown.refused} />;
      footer = [close];
      break;
    case 'checked':
    case 'deploying':
    case 'deploy-refused': {
      const { answer } = shown;
      body = (
        <Flex vertical gap="middle">
          {shown.kind === 'deploy-refused' && (
            <RefusalAlert title={shown.refused.status === 412 ? 'The node’s commit changed since the check: nothing was deployed' : 'The deploy was refused: nothing was deployed'} refused={shown.refused} />
          )}
          <Typography.Text>The check passed. {replacing(answer.replaces, host)}</Typography.Text>
          <FlowChanges deploy={answer.deploy} done={false} />
          {state.changes.length > 0 && <Typography.Text type="secondary">Uncommitted changes are never deployed: the node gets the commit alone.</Typography.Text>}
          {!writable && <Typography.Text type="secondary">The server shows the working folder read-only (RAIL8_READ_ONLY is true): the check runs, and nothing is deployed.</Typography.Text>}
        </Flex>
      );
      footer =
        shown.kind === 'deploy-refused'
          ? [close, <Button key="check" type="primary" onClick={checkAgain}>Check again</Button>]
          : [
              <Button key="cancel" disabled={shown.kind === 'deploying'} onClick={onClose}>
                Cancel
              </Button>,
              <Button key="deploy" type="primary" disabled={!writable} loading={shown.kind === 'deploying'} onClick={() => void deploy(answer)}>
                Deploy
              </Button>,
            ];
      break;
    }
    case 'lost': {
      const now = shown.now;
      body = (
        <Flex vertical gap="middle">
          <Alert type="warning" showIcon title="The outcome of the deploy is unknown" description={shown.refused.error} />
          {now === null && shown.problem === undefined && (
            <Flex gap="small" align="center">
              <Spin size="small" />
              <Typography.Text>Reading what {host} runs…</Typography.Text>
            </Flex>
          )}
          {shown.problem !== undefined && <Alert type="error" showIcon title={`What ${host} runs could not be read`} description={shown.problem} />}
          {now !== null && (
            <Typography.Text>
              {host} now reads: {deployedStatus(now).text}
              {now.deployed.node === 'answered' && now.deployed.relation !== 'none' && (
                <>
                  , running <Typography.Text code>{short(now.deployed.commit)}</Typography.Text>
                </>
              )}
              .
            </Typography.Text>
          )}
        </Flex>
      );
      footer = [close, <Button key="check" type="primary" disabled={now === null && shown.problem === undefined} onClick={checkAgain}>Check again</Button>];
      break;
    }
    case 'deployed': {
      const { answer } = shown;
      body = (
        <Flex vertical gap="middle">
          <Typography.Text>
            {host} runs <Typography.Text code>{short(commit.id)}</Typography.Text>
            {answer.replaces === 'none' ? '.' : <>, in place of <Typography.Text code>{short(answer.replaces)}</Typography.Text>.</>}
          </Typography.Text>
          <FlowChanges deploy={answer.deploy} done />
          {answer.package === null && <Alert type="warning" showIcon title={answer.problem ?? 'The package could not be read after the deploy'} />}
        </Flex>
      );
      footer = [<Button key="close" type="primary" onClick={onClose}>Close</Button>];
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
