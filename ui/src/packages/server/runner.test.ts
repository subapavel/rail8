import { spawn as nodeSpawn } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { afterEach, describe, expect, test } from 'vitest';
import { Refusal } from '../../../server/workingFolder.ts';
import { FakeTimer } from './fakeTimer.ts';
import { DRAIN_MS, KILL_AFTER_MS, MAX_RUNNING, MAX_WAITING, READ_LIMITS, runner, UNDO_CALLS, UNDO_LIMITS, type Call, type Child, type Runner, type Signals, type Spawn } from './runner.ts';

/** A child process the test drives: what it was started with, its streams, and the signals its group was sent. */
class FakeChild extends EventEmitter {
  readonly stdin = new PassThrough();
  readonly stdout = new PassThrough();
  readonly stderr = new PassThrough();
  readonly signals: NodeJS.Signals[] = [];
  stdinText = '';

  constructor(
    readonly pid: number,
    readonly command: string,
    readonly args: string[],
    readonly options: Parameters<Spawn>[2],
  ) {
    super();
    this.stdin.on('data', (chunk: Buffer) => (this.stdinText += chunk.toString('utf8')));
  }

  /** Ends both streams and exits with `code`. */
  exit(code: number | null = 0) {
    this.stdout.end();
    this.stderr.end();
    this.emit('exit', code, null);
  }
}

/** A fake process table: every child the runner started, in order. */
function fakeProcesses() {
  const children: FakeChild[] = [];
  const spawn: Spawn = (command, args, options) => {
    const child = new FakeChild(1000 + children.length, command, args, options);
    children.push(child);
    return child as unknown as Child;
  };
  const kill = (pid: number, signal: NodeJS.Signals) => children.find((c) => c.pid === pid)?.signals.push(signal);
  return { children, spawn, kill };
}

/** A process whose signal listeners the test can see and fire. */
class FakeSignals extends EventEmitter implements Signals {
  readonly pid = 77;
  readonly raised: [number, NodeJS.Signals][] = [];

  kill(pid: number, signal: NodeJS.Signals) {
    this.raised.push([pid, signal]);
  }
}

const read = (args: string[], extra: Partial<Call> = {}): Call => ({ args, clone: '/work/orders', mode: 'read', ...extra });

async function refusalOf(call: Promise<unknown>): Promise<Refusal> {
  const error = await call.then(
    () => undefined,
    (e: unknown) => e,
  );
  expect(error).toBeInstanceOf(Refusal);
  return error as Refusal;
}

/** Lets the promises that are ready run. */
const settle = () => new Promise<void>((done) => setImmediate(done));

describe('the runner on a fake process', () => {
  test('starts git without a shell, in a group of its own, with --no-pager and -C before the arguments', async () => {
    const processes = fakeProcesses();
    const git = runner({ env: {}, timer: new FakeTimer(), spawn: processes.spawn, kill: processes.kill, signals: new FakeSignals() });

    const ran = git.run(read(['status', '--porcelain=v2', '-z', '--', ':(literal)a*b']));
    const child = processes.children[0]!;
    child.stdout.write('out');
    child.stderr.write('err');
    child.exit(1);

    expect(await ran).toEqual({ code: 1, stdout: Buffer.from('out'), stderr: Buffer.from('err') });
    expect(child.command).toBe('git');
    expect(child.args).toEqual(['--no-pager', '-C', '/work/orders', 'status', '--porcelain=v2', '-z', '--', ':(literal)a*b']);
    expect(child.options).toMatchObject({ detached: true, shell: false, stdio: ['ignore', 'pipe', 'pipe'] });
  });

  test('gives a call outside a clone no -C, and writes its stdin', async () => {
    const processes = fakeProcesses();
    const git = runner({ env: {}, timer: new FakeTimer(), spawn: processes.spawn, kill: processes.kill, signals: new FakeSignals() });

    const ran = git.run({ args: ['--version'], mode: 'read', stdin: 'a message\n' });
    const child = processes.children[0]!;
    await settle();
    child.exit(0);
    await ran;

    expect(child.args).toEqual(['--no-pager', '--version']);
    expect(child.options.stdio[0]).toBe('pipe');
    expect(child.stdinText).toBe('a message\n');
  });

  test('removes every GIT_ and RAIL8_ variable, turns prompts off, and sets the C locale and no optional locks for reads only', async () => {
    const processes = fakeProcesses();
    const env = {
      PATH: '/usr/bin',
      HOME: '/home/author',
      LANG: 'cs_CZ.UTF-8',
      GIT_DIR: '/elsewhere',
      GIT_SSH_COMMAND: 'ssh -i key',
      GIT_LITERAL_PATHSPECS: '1',
      Git_Work_Tree: '/elsewhere',
      RAIL8_ADMIN_TOKEN: 'secret',
      RAIL8_WORKSPACE: '/work',
      UNSET: undefined,
    };
    const git = runner({ env, timer: new FakeTimer(), spawn: processes.spawn, kill: processes.kill, signals: new FakeSignals() });

    const reading = git.run(read(['status']));
    processes.children[0]!.exit(0);
    await reading;
    const writing = git.run({ ...read(['commit']), mode: 'write' });
    processes.children[1]!.exit(0);
    await writing;

    const prompts = { GIT_TERMINAL_PROMPT: '0', GCM_INTERACTIVE: 'never', SSH_ASKPASS_REQUIRE: 'never' };
    const author = { PATH: '/usr/bin', HOME: '/home/author', LANG: 'cs_CZ.UTF-8' };
    expect(processes.children[0]!.options.env).toEqual({ ...author, ...prompts, LC_ALL: 'C', GIT_OPTIONAL_LOCKS: '0' });
    expect(processes.children[1]!.options.env).toEqual({ ...author, ...prompts });
  });

  test.each(['stdout', 'stderr'] as const)('stops a call whose %s passes its limit while it is read, and keeps no more than the limit', async (name) => {
    const processes = fakeProcesses();
    const timer = new FakeTimer();
    const git = runner({ env: {}, timer, spawn: processes.spawn, kill: processes.kill, signals: new FakeSignals() });

    const ran = git.run(read(['log'], { limits: { ms: 10_000, bytes: 10 } }));
    const child = processes.children[0]!;
    child[name].write('0123456789');
    await settle();
    expect(child.signals).toEqual([]);
    child[name].write('x');
    await settle();
    expect(child.signals).toEqual(['SIGTERM']);
    timer.advance(KILL_AFTER_MS);
    expect(child.signals).toEqual(['SIGTERM', 'SIGKILL']);
    child.exit(null);

    const refused = await refusalOf(ran);
    expect(refused.status).toBe(500);
    expect(refused.message).toContain(name);
  });

  test('stops a call at its deadline on the timer: TERM, then KILL 2 s later, and answers 504 once it exits', async () => {
    const processes = fakeProcesses();
    const timer = new FakeTimer();
    const git = runner({ env: {}, timer, spawn: processes.spawn, kill: processes.kill, signals: new FakeSignals() });

    const ran = git.run(read(['status']));
    const child = processes.children[0]!;
    timer.advance(READ_LIMITS.ms - 1);
    expect(child.signals).toEqual([]);
    timer.advance(1);
    expect(child.signals).toEqual(['SIGTERM']);
    timer.advance(KILL_AFTER_MS - 1);
    expect(child.signals).toEqual(['SIGTERM']);
    timer.advance(1);
    expect(child.signals).toEqual(['SIGTERM', 'SIGKILL']);
    child.exit(null);

    expect((await refusalOf(ran)).status).toBe(504);
  });

  test('sends no KILL to a call that exited after its TERM', async () => {
    const processes = fakeProcesses();
    const timer = new FakeTimer();
    const git = runner({ env: {}, timer, spawn: processes.spawn, kill: processes.kill, signals: new FakeSignals() });

    const ran = git.run(read(['status']));
    const child = processes.children[0]!;
    timer.advance(READ_LIMITS.ms);
    child.exit(null);
    expect((await refusalOf(ran)).status).toBe(504);
    timer.advance(KILL_AFTER_MS);

    expect(child.signals).toEqual(['SIGTERM']);
  });

  test('answers 503 when git cannot be found', async () => {
    const timer = new FakeTimer();
    const spawn: Spawn = () => {
      const child = new FakeChild(1, 'git', [], {} as Parameters<Spawn>[2]);
      setImmediate(() => child.emit('error', Object.assign(new Error('spawn git ENOENT'), { code: 'ENOENT' })));
      return child as unknown as Child;
    };
    const git = runner({ env: {}, timer, spawn, kill: () => {}, signals: new FakeSignals() });

    expect((await refusalOf(git.run(read(['--version'])))).status).toBe(503);
  });

  test('runs at most 4 processes at once and starts a waiting one when one ends', async () => {
    const processes = fakeProcesses();
    const git = runner({ env: {}, timer: new FakeTimer(), spawn: processes.spawn, kill: processes.kill, signals: new FakeSignals() });

    const calls = Array.from({ length: MAX_RUNNING + 1 }, (_, i) => git.run(read([`call-${i}`])));
    expect(processes.children).toHaveLength(MAX_RUNNING);
    processes.children[0]!.exit(0);
    await calls[0];
    await settle();

    expect(processes.children.map((c) => c.args.at(-1))).toEqual(['call-0', 'call-1', 'call-2', 'call-3', 'call-4']);
    for (const child of processes.children.slice(1)) child.exit(0);
    await Promise.all(calls);
  });

  test('lets 32 reads wait and refuses one more with 503', async () => {
    const processes = fakeProcesses();
    const git = runner({ env: {}, timer: new FakeTimer(), spawn: processes.spawn, kill: processes.kill, signals: new FakeSignals() });

    const calls = Array.from({ length: MAX_RUNNING + MAX_WAITING.read }, () => git.run(read(['status'])));
    const refused = await refusalOf(git.run(read(['status'])));

    expect(refused.status).toBe(503);
    for (let i = 0; i < calls.length; i++) {
      await settle();
      processes.children[i]!.exit(0);
    }
    await Promise.all(calls);
    expect(processes.children).toHaveLength(MAX_RUNNING + MAX_WAITING.read);
  });

  test('runs one write at a time beside reads, lets 8 writes wait and refuses one more with 503', async () => {
    const processes = fakeProcesses();
    const git = runner({ env: {}, timer: new FakeTimer(), spawn: processes.spawn, kill: processes.kill, signals: new FakeSignals() });
    const write = (n: number) => git.run({ args: [`write-${n}`], clone: '/work/orders', mode: 'write' });

    const writes = Array.from({ length: 1 + MAX_WAITING.write }, (_, i) => write(i));
    const reading = git.run(read(['read']));
    const refused = await refusalOf(write(99));

    expect(refused.status).toBe(503);
    expect(processes.children.map((c) => c.args.at(-1))).toEqual(['write-0', 'read']);
    processes.children[1]!.exit(0);
    await reading;
    processes.children[0]!.exit(0);
    await writes[0];
    await settle();
    expect(processes.children.map((c) => c.args.at(-1))).toEqual(['write-0', 'read', 'write-1']);
    for (let i = 3; i < 2 + MAX_WAITING.write; i++) {
      processes.children[i - 1]!.exit(0);
      await settle();
      expect(processes.children.map((c) => c.args.at(-1))).toHaveLength(i + 1);
    }
    processes.children.at(-1)!.exit(0);
    await Promise.all(writes);
    expect(processes.children).toHaveLength(2 + MAX_WAITING.write);
  });

  test('stops every process when it closes, refuses waiting and new calls, and resolves once each has exited', async () => {
    const processes = fakeProcesses();
    const timer = new FakeTimer();
    const git = runner({ env: {}, timer, spawn: processes.spawn, kill: processes.kill, signals: new FakeSignals() });

    const running = Array.from({ length: MAX_RUNNING }, () => refusalOf(git.run(read(['status']))));
    const waiting = git.run(read(['status']));
    let closed = false;
    const closing = git.close().then(() => (closed = true));

    expect((await refusalOf(waiting)).status).toBe(503);
    expect((await refusalOf(git.run(read(['status'])))).status).toBe(503);
    expect(processes.children.map((c) => c.signals)).toEqual(Array.from({ length: MAX_RUNNING }, () => ['SIGTERM']));
    for (const child of processes.children.slice(1)) child.exit(null);
    timer.advance(KILL_AFTER_MS);
    expect(processes.children[0]!.signals).toEqual(['SIGTERM', 'SIGKILL']);
    await settle();
    expect(closed).toBe(false);
    processes.children[0]!.exit(null);
    await closing;
    for (const refused of await Promise.all(running)) expect(refused.status).toBe(503);
  });

  test('stops its processes on SIGINT while one runs, then ends the server with the same signal, and listens to nothing while none runs', async () => {
    const processes = fakeProcesses();
    const signals = new FakeSignals();
    const git = runner({ env: {}, timer: new FakeTimer(), spawn: processes.spawn, kill: processes.kill, signals });

    const first = git.run(read(['status']));
    expect(signals.listenerCount('SIGINT')).toBe(1);
    expect(signals.listenerCount('SIGHUP')).toBe(1);
    processes.children[0]!.exit(0);
    await first;
    expect(signals.listenerCount('SIGINT')).toBe(0);
    expect(signals.listenerCount('SIGHUP')).toBe(0);

    const second = git.run(read(['status']));
    signals.emit('SIGINT');
    expect(processes.children[1]!.signals).toEqual(['SIGTERM']);
    expect(signals.raised).toEqual([]);
    processes.children[1]!.exit(null);
    expect((await refusalOf(second)).status).toBe(503);
    await settle();

    expect(signals.raised).toEqual([[77, 'SIGINT']]);
    expect(signals.listenerCount('SIGINT')).toBe(0);
    expect(signals.listenerCount('SIGHUP')).toBe(0);
  });
});

const write = (args: string[]): Call => ({ args, clone: '/work/orders', mode: 'write' });

describe('an undoable work of the runner', () => {
  test('runs its undo once when the work fails, given the failure, then fails as the work did; no undo when it succeeds', async () => {
    const processes = fakeProcesses();
    const git = runner({ env: {}, timer: new FakeTimer(), spawn: processes.spawn, kill: processes.kill, signals: new FakeSignals() });
    const undone: unknown[] = [];
    const failure = new Refusal(422, 'the hook refused');

    const failing = git.undoable(
      async () => {
        throw failure;
      },
      async (run, why) => {
        undone.push(why);
        const ran = run(write(['rm', '--cached']));
        processes.children.at(-1)!.exit(0);
        await ran;
      },
    );
    expect(await failing.catch((e: unknown) => e)).toBe(failure);
    expect(undone).toEqual([failure]);
    expect(processes.children.map((c) => c.args.slice(3))).toEqual([['rm', '--cached']]);

    expect(await git.undoable(async () => 'done', async () => void undone.push('again'))).toBe('done');
    expect(undone).toEqual([failure]);
  });

  test('fails with the undo’s own failure when the undo fails', async () => {
    const git = runner({ env: {}, timer: new FakeTimer(), spawn: fakeProcesses().spawn, signals: new FakeSignals() });
    const own = new Refusal(422, 'the hook refused; orders/new.txt stays staged');

    const failed = git.undoable(
      async () => {
        throw new Refusal(422, 'the hook refused');
      },
      async () => {
        throw own;
      },
    );

    expect(await failed.catch((e: unknown) => e)).toBe(own);
  });

  test('a close while the work runs stops its process and refuses its next call; the undo still runs, is not stopped, and the close waits for it', async () => {
    const processes = fakeProcesses();
    const timer = new FakeTimer();
    const git = runner({ env: {}, timer, spawn: processes.spawn, kill: processes.kill, signals: new FakeSignals() });
    let undoing: Promise<unknown> | undefined;

    const committing = git.undoable(
      async () => {
        await git.run(write(['add']));
        return git.run(write(['commit']));
      },
      async (run) => {
        undoing = run(write(['rm', '--cached']));
        await undoing;
      },
    );
    let closed = false;
    const closing = git.close().then(() => (closed = true));
    expect(processes.children[0]!.signals).toEqual(['SIGTERM']);
    processes.children[0]!.exit(null);
    await settle();

    expect(processes.children.map((c) => c.args[3])).toEqual(['add', 'rm']);
    timer.advance(KILL_AFTER_MS);
    expect(processes.children[1]!.signals).toEqual([]);
    await settle();
    expect(closed).toBe(false);
    processes.children[1]!.exit(0);
    await closing;
    expect((await refusalOf(committing)).status).toBe(503);
    expect(await undoing).toMatchObject({ code: 0 });
  });

  test('a close while an undo’s call runs leaves that call running, and waits for it', async () => {
    const processes = fakeProcesses();
    const timer = new FakeTimer();
    const git = runner({ env: {}, timer, spawn: processes.spawn, kill: processes.kill, signals: new FakeSignals() });

    const failing = git.undoable(
      () => Promise.reject(new Refusal(422, 'the hook refused')),
      async (run) => {
        await run(write(['rm', '--cached']));
      },
    );
    await settle();
    let closed = false;
    const closing = git.close().then(() => (closed = true));
    timer.advance(KILL_AFTER_MS);
    await settle();

    expect(processes.children[0]!.signals).toEqual([]);
    expect(closed).toBe(false);
    processes.children[0]!.exit(0);
    await closing;
    expect((await refusalOf(failing)).status).toBe(422);
  });

  test('an undo’s call runs within its own limits, and is stopped at their deadline', async () => {
    const processes = fakeProcesses();
    const timer = new FakeTimer();
    const git = runner({ env: {}, timer, spawn: processes.spawn, kill: processes.kill, signals: new FakeSignals() });

    const failed = git.undoable(
      async () => {
        throw new Refusal(422, 'the hook refused');
      },
      async (run) => {
        await run({ ...write(['rm', '--cached']), limits: { ms: 3_600_000, bytes: 1 } });
      },
    );
    await settle();
    timer.advance(UNDO_LIMITS.ms);

    expect(processes.children[0]!.signals).toEqual(['SIGTERM']);
    processes.children[0]!.exit(null);
    expect((await refusalOf(failed)).status).toBe(504);
  });

  test('an undo’s call is admitted while 8 writes wait, and waits for the write before it', async () => {
    const processes = fakeProcesses();
    const git = runner({ env: {}, timer: new FakeTimer(), spawn: processes.spawn, kill: processes.kill, signals: new FakeSignals() });
    const writes = Array.from({ length: 1 + MAX_WAITING.write }, () => git.run(write(['add'])));

    const failed = git.undoable(
      async () => {
        throw new Refusal(422, 'the hook refused');
      },
      async (run) => {
        await run(write(['rm', '--cached']));
      },
    );
    await settle();
    expect(processes.children).toHaveLength(1);
    for (let n = 0; n <= MAX_WAITING.write; n++) {
      processes.children[n]!.exit(0);
      await settle();
    }
    expect(processes.children.at(-1)!.args[3]).toBe('rm');
    processes.children.at(-1)!.exit(0);

    expect((await refusalOf(failed)).status).toBe(422);
    await Promise.all(writes);
  });

  test('keeps SIGINT caught while one is under way with no process, says so once, and ends the server with it only after the undo', async () => {
    const processes = fakeProcesses();
    const signals = new FakeSignals();
    const said: string[] = [];
    const git = runner({ env: {}, timer: new FakeTimer(), spawn: processes.spawn, kill: processes.kill, signals, say: (line) => said.push(line) });
    let open: () => void = () => {};
    const gate = new Promise<void>((done) => (open = done));

    const committing = git.undoable(
      async () => {
        await gate;
        return git.run(write(['commit']));
      },
      async (run) => {
        await run(write(['rm', '--cached']));
      },
    );
    expect(signals.listenerCount('SIGINT')).toBe(1);
    signals.emit('SIGINT');
    open();
    await settle();

    expect(processes.children.map((c) => c.args[3])).toEqual(['rm']);
    expect(said).toEqual([`Undoing an unfinished git write before the server ends: at most ${(UNDO_CALLS * UNDO_LIMITS.ms) / 1000} s.`]);
    signals.emit('SIGINT');
    signals.emit('SIGHUP');
    expect(signals.listenerCount('SIGINT')).toBe(1);
    expect(processes.children[0]!.signals).toEqual([]);
    expect(signals.raised).toEqual([]);
    processes.children[0]!.exit(0);
    expect((await refusalOf(committing)).status).toBe(503);
    await settle();
    expect(signals.raised).toEqual([[77, 'SIGINT']]);
    expect(said).toHaveLength(1);
    expect(signals.listenerCount('SIGINT')).toBe(0);
  });

  test(`refuses an undo's call past its ${UNDO_CALLS}th with 503, and starts no process for it`, async () => {
    const processes = fakeProcesses();
    const git = runner({ env: {}, timer: new FakeTimer(), spawn: processes.spawn, kill: processes.kill, signals: new FakeSignals() });
    let refused: unknown;

    const failing = git.undoable(
      () => Promise.reject(new Refusal(422, 'the hook refused')),
      async (run) => {
        for (let n = 0; n < UNDO_CALLS; n++) {
          const ran = run(read(['ls-files']));
          await settle();
          processes.children[n]!.exit(0);
          await ran;
        }
        refused = await run(read(['ls-files'])).catch((error: unknown) => error);
      },
    );

    expect((await refusalOf(failing)).status).toBe(422);
    expect(processes.children).toHaveLength(UNDO_CALLS);
    expect(refused).toMatchObject({ status: 503, message: `an undo makes at most ${UNDO_CALLS} git calls` });
  });

  test('is refused with 503 once the runner closes, and runs neither its work nor its undo', async () => {
    const git = runner({ env: {}, timer: new FakeTimer(), spawn: fakeProcesses().spawn, signals: new FakeSignals() });
    await git.close();
    const ran: string[] = [];

    const refused = await refusalOf(git.undoable(async () => void ran.push('work'), async () => void ran.push('undo')));

    expect(refused.status).toBe(503);
    expect(ran).toEqual([]);
  });
});

/**
 * Starts the real `/bin/sh -c script` in place of git, with exactly the options the runner gives git; `ready` resolves
 * at the first bytes the script writes on its stdout.
 */
function shell(script: string) {
  let ready: () => void = () => {};
  const started = new Promise<void>((done) => (ready = done));
  const spawn: Spawn = (_command, _args, options) => {
    const child = nodeSpawn('/bin/sh', ['-c', script], options);
    child.stdout?.once('data', () => ready());
    return child;
  };
  return { spawn, started };
}

const alive = (pid: number) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};

describe('the runner on real child processes, with every deadline on a fake timer', () => {
  const leftovers: number[] = [];
  let git: Runner | undefined;
  afterEach(async () => {
    for (const pid of leftovers.splice(0)) if (alive(pid)) process.kill(pid, 'SIGKILL');
    await git?.close();
    git = undefined;
  });

  test('stops a child that never exits and ignores TERM: TERM at its deadline, KILL 2 s later', async () => {
    const timer = new FakeTimer();
    const child = shell("trap '' TERM; echo ready; while :; do sleep 1; done");
    git = runner({ env: process.env, timer, spawn: child.spawn, signals: new FakeSignals() });

    const ran = git.run(read(['wait'], { clone: undefined }));
    await child.started;
    timer.advance(READ_LIMITS.ms);
    await settle();
    expect(timer.pending).toBe(1);
    timer.advance(KILL_AFTER_MS);

    expect((await refusalOf(ran)).status).toBe(504);
  });

  test('stops a child that floods both streams at the first limit it passes', async () => {
    const timer = new FakeTimer();
    const child = shell('echo ready; yes out & yes err >&2 & wait');
    git = runner({ env: process.env, timer, spawn: child.spawn, signals: new FakeSignals() });

    const refused = await refusalOf(git.run(read(['flood'], { clone: undefined, limits: { ms: 10_000, bytes: 1024 * 1024 } })));

    expect(refused.status).toBe(500);
    expect(refused.message).toMatch(/more than 1 MiB on its std(out|err)/);
  });

  test('ends a call whose descendant keeps a pipe open once the drain has passed, with what was written, and does not kill the descendant', async () => {
    const timer = new FakeTimer();
    const child = shell('sleep 1000 & echo $!');
    git = runner({ env: process.env, timer, spawn: child.spawn, signals: new FakeSignals() });

    const ran = git.run(read(['hold'], { clone: undefined }));
    await child.started;
    await timer.waitFor(2);
    timer.advance(DRAIN_MS);
    const result = await ran;
    const descendant = Number(result.stdout.toString('utf8').trim());
    leftovers.push(descendant);

    expect(result.code).toBe(0);
    expect(descendant).toBeGreaterThan(0);
    expect(alive(descendant)).toBe(true);
  });

  test('stops a child that runs when the server shuts down, and resolves once it has exited', async () => {
    const timer = new FakeTimer();
    const child = shell("trap '' TERM; echo ready; while :; do sleep 1; done");
    git = runner({ env: process.env, timer, spawn: child.spawn, signals: new FakeSignals() });

    const ran = git.run(read(['wait'], { clone: undefined }));
    await child.started;
    let closed = false;
    const closing = git.close().then(() => (closed = true));
    await settle();
    expect(closed).toBe(false);
    timer.advance(KILL_AFTER_MS);
    await closing;

    expect((await refusalOf(ran)).status).toBe(503);
  });
});
