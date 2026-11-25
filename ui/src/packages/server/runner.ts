import { spawn as nodeSpawn } from 'node:child_process';
import type { Readable, Writable } from 'node:stream';
import { GIT_REFUSALS, RUNNER_REFUSALS, type Refused } from '../rules.ts';
import { refusal } from './refused.ts';
import type { Timer } from './timer.ts';

/**
 * A read changes nothing; a write may change the clone, and runs with the author's locale for their hooks; a clone
 * makes a new clone, as a write runs, with only the https and ssh transports allowed.
 */
export type Mode = 'read' | 'write' | 'clone';

/** How long one call may run, and how many bytes it may write on each of its two output streams. */
export interface Limits {
  ms: number;
  bytes: number;
}

/** The limits of a git read: 10 s, and 4 MiB on each stream. */
export const READ_LIMITS: Limits = { ms: 10_000, bytes: 4 * 1024 * 1024 };

/** The limits of each call of an undo: 10 s, and 1 MiB on each stream. */
export const UNDO_LIMITS: Limits = { ms: 10_000, bytes: 1024 * 1024 };

/** An undo makes at most this many calls, so that it ends within this many times {@link UNDO_LIMITS}' 10 s. */
export const UNDO_CALLS = 6;

/** From TERM to KILL. */
export const KILL_AFTER_MS = 2_000;

/** How long a call waits, after its process exited, for a descendant to release its output streams. */
export const DRAIN_MS = 100;

/** At most this many processes run at once, and at most this many reads, writes and clones wait. */
export const MAX_RUNNING = 4;
export const MAX_WAITING: Readonly<Record<Mode, number>> = { read: 32, write: 8, clone: 1 };

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
  /** Its abort stops the call as its deadline does, and the call rejects with the signal's reason. */
  signal?: AbortSignal;
  /**
   * Another program to run instead of git, such as the Rail8 command line: its file, the name the call's refusals give
   * it, and what a refusal says when the file cannot be found. Its arguments are `args` alone, with no `--no-pager` or `-C`.
   */
  program?: { file: string; name: string; missing: Refused };
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
  on(signal: NodeJS.Signals, listener: () => void): unknown;
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
  /** Prints a line of the runner's own in the development server's output; to stderr by default. */
  say?: (line: string) => void;
}

/** Starts git, one call at a time per process slot, and stops every process it started when it closes. */
export interface Runner {
  /**
   * Runs `call` once a slot is free, and answers how its process ended, whatever its exit code. Rejects with a
   * refusal of the UI server: 503 when git cannot be found, when too many calls wait or when the runner closes; 504 when the
   * call passed its deadline and 500 when it wrote more than its limit, both after its process was stopped.
   */
  run(call: Call): Promise<Ran>;
  /**
   * Runs `work`, and answers what it answers. When it rejects, for any reason (its own refusal, a call refused or
   * stopped, the runner closing), `undo` runs once, given the failure and a `run` whose calls are admitted even while
   * the runner closes and however many calls wait, still one write at a time, at most {@link UNDO_CALLS} of them
   * (a further one is refused with 503), each within {@link UNDO_LIMITS} and never
   * stopped by a close; then it rejects with work's failure, or with undo's own. Refused with 503, and neither run, once
   * the runner closes.
   */
  undoable<T>(work: () => Promise<T>, undo: (run: (call: Call) => Promise<Ran>, failure: unknown) => Promise<void>): Promise<T>;
  /**
   * Refuses every call from now on but an undo's, and stops every process it started but an undo's: TERM, then KILL
   * 2 s later. Resolves once each has exited and every undoable under way has settled.
   */
  close(): Promise<void>;
}

/** The terminal signals that end the development server without its close hooks; the runner stops its processes on them first. */
const ENDING: readonly NodeJS.Signals[] = ['SIGINT', 'SIGHUP'];

/**
 * The environment of a call: `env` without any `GIT_*` or `RAIL8_*` variable, with no terminal prompt from git, the Git
 * Credential Manager or ssh, and for a read the C locale and no optional locks, so that it never blocks the author's
 * own git. A clone may reach a repository through https and ssh only, whatever the author's config allows.
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
  if (mode === 'clone') kept.GIT_ALLOW_PROTOCOL = 'https:ssh';
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

const mib = (bytes: number) => bytes / (1024 * 1024);

/** What a call is while its process runs: whether it is an undo's, how to stop it, and when it has exited. */
interface Live {
  undo: boolean;
  stop(why: unknown): void;
  exited: Promise<void>;
}

/**
 * A runner over `options.spawn` (Node's own by default). Each process is git with `--no-pager`, started without a
 * shell, in a session and process group of its own with no terminal, so that a prompt in a terminal fails at once.
 * At its deadline, at the abort of its signal, or when one of its output streams passes its limit while it is read, its
 * process group gets TERM, and KILL 2 s later. A call ends when its process has exited and both streams have ended, or
 * {@link DRAIN_MS} after the exit, never past its deadline, when a descendant still holds one of them; such a
 * descendant is not killed. A stopped clone's group gets its KILL even when git has exited first, and the clone ends
 * only once it was sent, so that no process of the group writes into the clone's folder after it ended; on Windows
 * only git itself is signalled, as for every call.
 *
 * At most {@link MAX_RUNNING} processes run at once, one write at a time and one clone at a time; reads run beside
 * them, and a write and a clone beside each other. A call that cannot start waits in order, at most
 * {@link MAX_WAITING} of its mode.
 *
 * While a process runs, or an undoable is under way, SIGINT and SIGHUP of `options.signals` (the development server's
 * process by default) first close the runner as {@link Runner.close} does, and end the server with the same signal
 * once it has closed. While an undoable is under way, the first of them also says so in a line of `options.say`, and
 * any further one is caught and ignored until the runner has closed.
 */
export function runner(options: RunnerOptions): Runner {
  const spawn = options.spawn ?? (nodeSpawn as unknown as Spawn);
  const kill = options.kill ?? killGroup;
  const signals = options.signals ?? process;
  const say = options.say ?? ((line: string) => void process.stderr.write(`${line}\n`));
  const timer = options.timer;
  const live = new Set<Live>();
  const undoables = new Set<Promise<unknown>>();
  const waiting: { call: Call; undo: boolean; resolve: (ran: Ran) => void; reject: (error: unknown) => void }[] = [];
  let running = 0;
  let writing = false;
  let cloning = false;
  let closing: Promise<void> | null = null;

  const ending = new Map<NodeJS.Signals, () => void>();
  let ended = false;
  const listen = (on: boolean) => {
    for (const signal of ENDING) {
      if (on && !ending.has(signal)) {
        const listener = () => {
          // a signal after the first waits for the close as well: the undo under way ends within its bounds
          if (ended) return;
          ended = true;
          if (undoables.size > 0) say(`Undoing an unfinished git write before the server ends: at most ${(UNDO_CALLS * UNDO_LIMITS.ms) / 1000} s.`);
          void close().finally(() => {
            listen(false);
            signals.kill(signals.pid, signal);
          });
        };
        ending.set(signal, listener);
        signals.on(signal, listener);
      } else if (!on && ending.has(signal)) {
        signals.off(signal, ending.get(signal)!);
        ending.delete(signal);
      }
    }
  };

  // the signals are caught while a process runs or an undoable is under way
  const watch = () => listen(live.size > 0 || undoables.size > 0);

  const start = (call: Call, undo: boolean): Promise<Ran> =>
    new Promise<Ran>((resolve, reject) => {
      const limits = undo ? UNDO_LIMITS : (call.limits ?? READ_LIMITS);
      const name = call.program?.name ?? 'git';
      const argv = call.program !== undefined ? [...call.args] : ['--no-pager', ...(call.clone === undefined ? [] : ['-C', call.clone]), ...call.args];
      let child: Child;
      try {
        child = spawn(call.program?.file ?? 'git', argv, {
          env: environmentOf(options.env, call.mode),
          detached: true,
          shell: false,
          windowsHide: true,
          stdio: [call.stdin === undefined ? 'ignore' : 'pipe', 'pipe', 'pipe'],
        });
      } catch (error) {
        reject(refusal(500, RUNNER_REFUSALS.notStarted(String(error), { program: name })));
        return;
      }

      const output = { stdout: [] as Buffer[], stderr: [] as Buffer[] };
      const sizes = { stdout: 0, stderr: 0 };
      let failure: unknown;
      let exited = false;
      let settled = false;
      let cancelKill: (() => void) | undefined;
      // a stopped clone ends only once its group was sent KILL
      let killSent: Promise<void> = Promise.resolve();
      const groupKilled = call.mode === 'clone' && process.platform !== 'win32';
      let endDrain: (() => void) | undefined;
      let markExited: () => void = () => {};
      const entry: Live = {
        undo,
        stop(why) {
          if (exited || failure !== undefined) return;
          failure = why;
          if (child.pid === undefined) return;
          const pid = child.pid;
          kill(pid, 'SIGTERM');
          if (groupKilled) {
            killSent = new Promise<void>((sent) => {
              timer.after(KILL_AFTER_MS, () => {
                kill(pid, 'SIGKILL');
                sent();
              });
            });
          } else {
            cancelKill = timer.after(KILL_AFTER_MS, () => {
              if (!exited) kill(pid, 'SIGKILL');
            });
          }
        },
        exited: new Promise<void>((done) => {
          markExited = done;
        }),
      };
      live.add(entry);
      watch();

      const cancelDeadline = timer.after(limits.ms, () => {
        if (exited) endDrain?.();
        else entry.stop(refusal(504, RUNNER_REFUSALS.timedOut({ program: name, seconds: limits.ms / 1000 })));
      });
      const aborted = () => entry.stop(call.signal!.reason);
      call.signal?.addEventListener('abort', aborted);

      const finish = (error: unknown, code: number | null) => {
        if (settled) return;
        settled = true;
        cancelDeadline();
        cancelKill?.();
        call.signal?.removeEventListener('abort', aborted);
        child.stdout?.destroy();
        child.stderr?.destroy();
        live.delete(entry);
        watch();
        markExited();
        if (error !== undefined) reject(error);
        else resolve({ code, stdout: Buffer.concat(output.stdout), stderr: Buffer.concat(output.stderr) });
      };

      for (const stream of ['stdout', 'stderr'] as const) {
        child[stream]?.on('data', (chunk: Buffer) => {
          const room = limits.bytes - sizes[stream];
          if (room > 0) output[stream].push(chunk.subarray(0, room));
          sizes[stream] += chunk.length;
          if (sizes[stream] > limits.bytes) entry.stop(refusal(500, RUNNER_REFUSALS.tooMuchOutput({ program: name, mib: mib(limits.bytes), stream })));
        });
        child[stream]?.on('error', () => {});
      }
      if (child.stdin !== null) {
        child.stdin.on('error', () => {});
        child.stdin.end(call.stdin ?? '');
      }

      child.once('error', (error) => {
        if (exited) return;
        exited = true;
        finish(
          error.code === 'ENOENT'
            ? refusal(503, call.program?.missing ?? GIT_REFUSALS.notFound)
            : refusal(500, RUNNER_REFUSALS.notStarted(error.message, { program: name })),
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
        // a clone stopped before this exit sends its KILL all the same, and ends after it
        void drained.then(async () => {
          await killSent;
          finish(failure, code);
        });
      });
    });

  const canStart = (mode: Mode) => running < MAX_RUNNING && (mode === 'read' || (mode === 'write' ? !writing : !cloning));

  const launch = (call: Call, undo: boolean, resolve: (ran: Ran) => void, reject: (error: unknown) => void) => {
    running++;
    if (call.mode === 'write') writing = true;
    if (call.mode === 'clone') cloning = true;
    start(call, undo)
      .then(resolve, reject)
      .finally(() => {
        running--;
        if (call.mode === 'write') writing = false;
        if (call.mode === 'clone') cloning = false;
        for (let i = 0; i < waiting.length; ) {
          const next = waiting[i]!;
          if (canStart(next.call.mode)) {
            waiting.splice(i, 1);
            launch(next.call, next.undo, next.resolve, next.reject);
          } else i++;
        }
      });
  };

  const submit = (call: Call, undo: boolean): Promise<Ran> => {
    if (closing !== null && !undo) return Promise.reject(refusal(503, RUNNER_REFUSALS.closing));
    if (call.signal?.aborted) return Promise.reject(call.signal.reason);
    return new Promise<Ran>((resolve, reject) => {
      if (canStart(call.mode)) launch(call, undo, resolve, reject);
      else if (!undo && waiting.filter((w) => w.call.mode === call.mode).length >= MAX_WAITING[call.mode]) {
        reject(refusal(503, RUNNER_REFUSALS.queueFull({ mode: call.mode })));
      } else {
        const waits = { call, undo, resolve, reject };
        waiting.push(waits);
        // an abort while it waits takes it out of the queue
        call.signal?.addEventListener(
          'abort',
          () => {
            const at = waiting.indexOf(waits);
            if (at >= 0) {
              waiting.splice(at, 1);
              reject(call.signal!.reason);
            }
          },
          { once: true },
        );
      }
    });
  };

  const close = (): Promise<void> => {
    if (closing !== null) return closing;
    for (let i = 0; i < waiting.length; ) {
      if (waiting[i]!.undo) i++;
      else waiting.splice(i, 1)[0]!.reject(refusal(503, RUNNER_REFUSALS.closing));
    }
    for (const entry of live) if (!entry.undo) entry.stop(refusal(503, RUNNER_REFUSALS.closed));
    closing = Promise.all([...[...live].map((entry) => entry.exited), ...[...undoables].map((u) => u.catch(() => undefined))]).then(() => {});
    return closing;
  };

  return {
    run: (call) => submit(call, false),
    undoable(work, undo) {
      if (closing !== null) return Promise.reject(refusal(503, RUNNER_REFUSALS.closing));
      const done = (async () => {
        try {
          return await work();
        } catch (failure) {
          let calls = 0;
          await undo((call) => (++calls > UNDO_CALLS ? Promise.reject(refusal(503, RUNNER_REFUSALS.undoCalls({ max: UNDO_CALLS }))) : submit(call, true)), failure);
          throw failure;
        }
      })();
      const settled = done.then(
        () => undefined,
        () => undefined,
      );
      undoables.add(settled);
      watch();
      void settled.then(() => {
        undoables.delete(settled);
        watch();
      });
      return done;
    },
    close,
  };
}
