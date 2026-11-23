import { spawn as nodeSpawn } from 'node:child_process';
import type { Readable, Writable } from 'node:stream';
import { Refusal } from '../../../server/workingFolder.ts';
import type { Timer } from './timer.ts';

/** A read changes nothing; a write may change the clone, and runs with the author's locale for their hooks. */
export type Mode = 'read' | 'write';

/** How long one call may run, and how many bytes it may write on each of its two output streams. */
export interface Limits {
  ms: number;
  bytes: number;
}

/** The limits of a git read: 10 s, and 4 MiB on each stream. */
export const READ_LIMITS: Limits = { ms: 10_000, bytes: 4 * 1024 * 1024 };

/** From TERM to KILL. */
export const KILL_AFTER_MS = 2_000;

/** How long a call waits, after its process exited, for a descendant to release its output streams. */
export const DRAIN_MS = 100;

/** At most this many processes run at once, and at most this many reads and writes wait. */
export const MAX_RUNNING = 4;
export const MAX_WAITING: Readonly<Record<Mode, number>> = { read: 32, write: 8 };

/** One git call. */
export interface Call {
  /** git's arguments, after `--no-pager` and `-C <clone>`. A path comes after `--`, a revision after `--end-of-options`. */
  args: readonly string[];
  /** The clone's real path, given to git as `-C`; undefined for a call outside a clone, such as `--version`. */
  clone?: string;
  mode: Mode;
  /** What git reads on its stdin, such as a commit message; none by default. */
  stdin?: string;
  /** {@link READ_LIMITS} by default. */
  limits?: Limits;
}

/** How a call's process ended: its exit code (null when a signal ended it) and what it wrote, each stream at most its limit. */
export interface Ran {
  code: number | null;
  stdout: Buffer;
  stderr: Buffer;
}

/** The part of a child process the runner uses. */
export interface Child {
  pid?: number | undefined;
  stdin: Writable | null;
  stdout: Readable | null;
  stderr: Readable | null;
  once(event: 'exit', listener: (code: number | null, signal: NodeJS.Signals | null) => void): unknown;
  once(event: 'error', listener: (error: NodeJS.ErrnoException) => void): unknown;
}

/** What starts a child process: `child_process.spawn`, or a fake one in a test. */
export type Spawn = (
  command: string,
  args: string[],
  options: { env: Record<string, string>; detached: true; shell: false; windowsHide: true; stdio: ['pipe' | 'ignore', 'pipe', 'pipe'] },
) => Child;

/** The process that receives the signals a terminal sends: the development server's own, or a fake one in a test. */
export interface Signals {
  readonly pid: number;
  once(signal: NodeJS.Signals, listener: () => void): unknown;
  off(signal: NodeJS.Signals, listener: () => void): unknown;
  kill(pid: number, signal: NodeJS.Signals): unknown;
}

export interface RunnerOptions {
  /** The development server's environment; each call gets it without its `GIT_*` and `RAIL8_*` variables. */
  env: Readonly<Record<string, string | undefined>>;
  timer: Timer;
  spawn?: Spawn;
  /** Sends `signal` to the process group `pid` leads. */
  kill?: (pid: number, signal: NodeJS.Signals) => void;
  signals?: Signals;
}

/** Starts git, one call at a time per process slot, and stops every process it started when it closes. */
export interface Runner {
  /**
   * Runs `call` once a slot is free, and answers how its process ended, whatever its exit code. Rejects with a
   * {@link Refusal}: 503 when git cannot be found, when too many calls wait or when the runner closes; 504 when the
   * call passed its deadline and 500 when it wrote more than its limit, both after its process was stopped.
   */
  run(call: Call): Promise<Ran>;
  /** Refuses every call from now on, and stops every process it started: TERM, then KILL 2 s later. Resolves once each has exited. */
  close(): Promise<void>;
}

/** The terminal signals that end the development server without its close hooks; the runner stops its processes on them first. */
const ENDING: readonly NodeJS.Signals[] = ['SIGINT', 'SIGHUP'];

/**
 * The environment of a call: `env` without any `GIT_*` or `RAIL8_*` variable, with no terminal prompt from git, the Git
 * Credential Manager or ssh, and for a read the C locale and no optional locks, so that it never blocks the author's
 * own git.
 */
export function environmentOf(env: Readonly<Record<string, string | undefined>>, mode: Mode): Record<string, string> {
  const kept: Record<string, string> = {};
  for (const [name, value] of Object.entries(env)) {
    const upper = name.toUpperCase();
    if (value !== undefined && !upper.startsWith('GIT_') && !upper.startsWith('RAIL8_')) kept[name] = value;
  }
  kept.GIT_TERMINAL_PROMPT = '0';
  kept.GCM_INTERACTIVE = 'never';
  kept.SSH_ASKPASS_REQUIRE = 'never';
  if (mode === 'read') {
    kept.LC_ALL = 'C';
    kept.GIT_OPTIONAL_LOCKS = '0';
  }
  return kept;
}

/** Sends `signal` to the process group `pid` leads; a group that is gone already is no failure. */
function killGroup(pid: number, signal: NodeJS.Signals) {
  try {
    process.kill(process.platform === 'win32' ? pid : -pid, signal);
  } catch {
    // the group has exited
  }
}

const mib = (bytes: number) => `${bytes / (1024 * 1024)} MiB`;

/** What a call is while its process runs: how to stop it, and when it has exited. */
interface Live {
  stop(why: Refusal): void;
  exited: Promise<void>;
}

/**
 * A runner over `options.spawn` (Node's own by default). Each process is git with `--no-pager`, started without a
 * shell, in a session and process group of its own with no terminal, so that a prompt in a terminal fails at once.
 * At its deadline, or when one of its output streams passes its limit while it is read, its process group gets TERM,
 * and KILL 2 s later. A call ends when its process has exited and both streams have ended, or {@link DRAIN_MS} after
 * the exit, never past its deadline, when a descendant still holds one of them; such a descendant is not killed.
 *
 * At most {@link MAX_RUNNING} processes run at once, and one write at a time; reads run beside it. A call that cannot
 * start waits in order, at most {@link MAX_WAITING} of its mode.
 *
 * While a process runs, SIGINT and SIGHUP of `options.signals` (the development server's process by default) first
 * stop every process as {@link Runner.close} does, then end the server with the same signal.
 */
export function runner(options: RunnerOptions): Runner {
  const spawn = options.spawn ?? (nodeSpawn as unknown as Spawn);
  const kill = options.kill ?? killGroup;
  const signals = options.signals ?? process;
  const timer = options.timer;
  const live = new Set<Live>();
  const waiting: { call: Call; resolve: (ran: Ran) => void; reject: (error: unknown) => void }[] = [];
  let running = 0;
  let writing = false;
  let closing: Promise<void> | null = null;

  const ending = new Map<NodeJS.Signals, () => void>();
  const listen = (on: boolean) => {
    for (const signal of ENDING) {
      if (on && !ending.has(signal)) {
        const listener = () => {
          listen(false);
          void close().finally(() => signals.kill(signals.pid, signal));
        };
        ending.set(signal, listener);
        signals.once(signal, listener);
      } else if (!on && ending.has(signal)) {
        signals.off(signal, ending.get(signal)!);
        ending.delete(signal);
      }
    }
  };

  const start = (call: Call): Promise<Ran> =>
    new Promise<Ran>((resolve, reject) => {
      const limits = call.limits ?? READ_LIMITS;
      const argv = ['--no-pager', ...(call.clone === undefined ? [] : ['-C', call.clone]), ...call.args];
      let child: Child;
      try {
        child = spawn('git', argv, {
          env: environmentOf(options.env, call.mode),
          detached: true,
          shell: false,
          windowsHide: true,
          stdio: [call.stdin === undefined ? 'ignore' : 'pipe', 'pipe', 'pipe'],
        });
      } catch (error) {
        reject(new Refusal(500, `git could not be started: ${String(error)}`));
        return;
      }

      const output = { stdout: [] as Buffer[], stderr: [] as Buffer[] };
      const sizes = { stdout: 0, stderr: 0 };
      let failure: Refusal | undefined;
      let exited = false;
      let settled = false;
      let cancelKill: (() => void) | undefined;
      let endDrain: (() => void) | undefined;
      let markExited: () => void = () => {};
      const entry: Live = {
        stop(why) {
          if (exited || failure !== undefined) return;
          failure = why;
          if (child.pid === undefined) return;
          const pid = child.pid;
          kill(pid, 'SIGTERM');
          cancelKill = timer.after(KILL_AFTER_MS, () => {
            if (!exited) kill(pid, 'SIGKILL');
          });
        },
        exited: new Promise<void>((done) => {
          markExited = done;
        }),
      };
      live.add(entry);
      if (live.size === 1) listen(true);

      const cancelDeadline = timer.after(limits.ms, () => {
        if (exited) endDrain?.();
        else entry.stop(new Refusal(504, `git took longer than ${limits.ms / 1000} s and was stopped`));
      });

      const finish = (error: Refusal | undefined, code: number | null) => {
        if (settled) return;
        settled = true;
        cancelDeadline();
        cancelKill?.();
        child.stdout?.destroy();
        child.stderr?.destroy();
        live.delete(entry);
        if (live.size === 0) listen(false);
        markExited();
        if (error !== undefined) reject(error);
        else resolve({ code, stdout: Buffer.concat(output.stdout), stderr: Buffer.concat(output.stderr) });
      };

      for (const name of ['stdout', 'stderr'] as const) {
        const stream = child[name];
        stream?.on('data', (chunk: Buffer) => {
          const room = limits.bytes - sizes[name];
          if (room > 0) output[name].push(chunk.subarray(0, room));
          sizes[name] += chunk.length;
          if (sizes[name] > limits.bytes) entry.stop(new Refusal(500, `git wrote more than ${mib(limits.bytes)} on its ${name} and was stopped`));
        });
        stream?.on('error', () => {});
      }
      if (child.stdin !== null) {
        child.stdin.on('error', () => {});
        child.stdin.end(call.stdin ?? '');
      }

      child.once('error', (error) => {
        if (exited) return;
        exited = true;
        finish(
          error.code === 'ENOENT' ? new Refusal(503, 'git is not installed or not on the PATH of the development server') : new Refusal(500, `git could not be started: ${error.message}`),
          null,
        );
      });
      child.once('exit', (code) => {
        if (exited) return;
        exited = true;
        const ended = (stream: Readable | null) =>
          stream === null || stream.readableEnded || stream.destroyed
            ? Promise.resolve()
            : new Promise<void>((done) => {
                stream.once('end', done);
                stream.once('close', done);
              });
        const drained = new Promise<void>((done) => {
          // the streams' pending bytes are read before an immediate runs, so nothing written before the exit is lost
          endDrain = () => setImmediate(done);
          const cancel = timer.after(DRAIN_MS, endDrain);
          void Promise.all([ended(child.stdout), ended(child.stderr)]).then(() => {
            cancel();
            done();
          });
        });
        void drained.then(() => finish(failure, code));
      });
    });

  const canStart = (mode: Mode) => running < MAX_RUNNING && (mode === 'read' || !writing);

  const launch = (call: Call, resolve: (ran: Ran) => void, reject: (error: unknown) => void) => {
    running++;
    if (call.mode === 'write') writing = true;
    start(call)
      .then(resolve, reject)
      .finally(() => {
        running--;
        if (call.mode === 'write') writing = false;
        for (let i = 0; i < waiting.length; ) {
          const next = waiting[i]!;
          if (canStart(next.call.mode)) {
            waiting.splice(i, 1);
            launch(next.call, next.resolve, next.reject);
          } else i++;
        }
      });
  };

  const close = (): Promise<void> => {
    if (closing !== null) return closing;
    for (const entry of waiting.splice(0)) entry.reject(new Refusal(503, 'the development server is closing'));
    for (const entry of live) entry.stop(new Refusal(503, 'the development server closed while git ran'));
    closing = Promise.all([...live].map((entry) => entry.exited)).then(() => {});
    return closing;
  };

  return {
    run(call) {
      if (closing !== null) return Promise.reject(new Refusal(503, 'the development server is closing'));
      return new Promise<Ran>((resolve, reject) => {
        if (canStart(call.mode)) launch(call, resolve, reject);
        else if (waiting.filter((w) => w.call.mode === call.mode).length >= MAX_WAITING[call.mode]) {
          reject(new Refusal(503, `too many git ${call.mode}s are waiting: try again`));
        } else waiting.push({ call, resolve, reject });
      });
    },
    close,
  };
}
