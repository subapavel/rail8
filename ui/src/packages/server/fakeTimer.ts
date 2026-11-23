import type { Timer } from './timer.ts';

/**
 * A {@link Timer} for tests: nothing runs until {@link FakeTimer.advance} moves its clock, which starts at 0 and runs
 * every call that has come due, in the order of its time.
 */
export class FakeTimer implements Timer {
  private now = 0;
  private calls: { at: number; run: () => void }[] = [];
  private watchers: { count: number; done: () => void }[] = [];

  after(ms: number, run: () => void): () => void {
    const call = { at: this.now + ms, run };
    this.calls.push(call);
    this.watchers = this.watchers.filter((w) => (this.calls.length >= w.count ? (w.done(), false) : true));
    return () => {
      this.calls = this.calls.filter((c) => c !== call);
    };
  }

  /** How many calls wait for their time. */
  get pending(): number {
    return this.calls.length;
  }

  /** Resolves once at least `count` calls wait for their time. */
  waitFor(count: number): Promise<void> {
    if (this.calls.length >= count) return Promise.resolve();
    return new Promise((done) => this.watchers.push({ count, done }));
  }

  /** Moves the clock on by `ms` and runs every call that has come due. */
  advance(ms: number): void {
    const until = this.now + ms;
    for (;;) {
      const due = this.calls.filter((c) => c.at <= until).sort((a, b) => a.at - b.at)[0];
      if (due === undefined) break;
      this.calls = this.calls.filter((c) => c !== due);
      this.now = due.at;
      due.run();
    }
    this.now = until;
  }
}
