import { spawn as nodeSpawn } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { afterEach, describe, expect, test } from 'vitest';
import { Refusal } from '../../../server/workingFolder.ts';
import { FakeTimer } from './fakeTimer.ts';
import { DRAIN_MS, KILL_AFTER_MS, MAX_RUNNING, MAX_WAITING, READ_LIMITS, runner, type Call, type Child, type Runner, type Signals, type Spawn } from './runner.ts';

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
