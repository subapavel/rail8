import { admitted, workingFolderWrites, type WorkingFolderWrites } from './workingFolder.ts';

/**
 * Writes of the working folder whose lock a test holds from the start, for the routes it serves:
 *
 * - `writes`, the writes the test gives the routes;
 * - `admissions(n)`, which resolves once `n` writes have asked `writes` to admit them;
 * - `waiting`, which resolves once an admitted write has asked for the lock;
 * - `release`, which lets the lock go, so the writes that wait for it run in turn.
 */
export function heldWrites(): { writes: WorkingFolderWrites; admissions: (n: number) => Promise<void>; waiting: Promise<void>; release: () => void } {
  const all = workingFolderWrites();
  let release = () => undefined as void;
  const held = new Promise<void>((done) => (release = done));
  void admitted(all, (lock) => lock(() => held));
  let asked = () => undefined as void;
  const waiting = new Promise<void>((done) => (asked = done));
  let count = 0;
  const counted: { n: number; done: () => void }[] = [];
  return {
    writes: (write) => {
      count += 1;
      for (const { n, done } of counted) if (n <= count) done();
      return all((lock) =>
        write((work) => {
          asked();
          return lock(work);
        }),
      );
    },
    admissions: (n) => new Promise<void>((done) => (n <= count ? done() : counted.push({ n, done }))),
    waiting,
    release: () => release(),
  };
}
