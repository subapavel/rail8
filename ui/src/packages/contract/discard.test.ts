import { chmod, mkdir, readFile, rm, stat, writeFile, readlink } from 'node:fs/promises';
import { join } from 'node:path';
import { afterEach, describe, expect, test } from 'vitest';
import { PackagesError, type DiscardBody, type PackageState } from '../api';
import { chosenPaths } from '../rules';
import { httpWorld } from './httpWorld';
import { fakeWorld, type Build, type CloneSeed, type Seed, type World } from './seed';

/** A text of several lines that names `what`, so that no two seeded files look alike to git's rename detection. */
const textOf = (what: string) => Array.from({ length: 6 }, (_, n) => `${what} line ${n}`).join('\n') + '\n';

// The discard cases on both implementations of PackagesApi: the real git half over real git, and the fake.
describe.each<[string, Build]>([
  ['http', httpWorld],
  ['fake', fakeWorld],
])('the %s git half discards', (name, build) => {
  let world: World | undefined;
  afterEach(async () => {
    await world?.close();
    world = undefined;
  });

  const open = async (seed: Seed) => (world = await build(seed));
  const one = (clone: Omit<CloneSeed, 'folder'>, seed: Seed = {}) => open({ ...seed, clones: [{ folder: 'orders', ...clone }] });
  const refusalOf = (call: Promise<unknown>) =>
    call.then(
      () => expect.unreachable('the call was not refused'),
      (error: unknown) => {
        expect(error).toBeInstanceOf(PackagesError);
        return error as PackagesError;
      },
    );
  /** A discard of the changes at `paths` as the state shows them, as the dialog sends it. */
  const bodyOf = (state: PackageState, paths: string[]): DiscardBody => ({
    head: state.head?.id ?? null,
    paths: chosenPaths(
      paths.map((p) => state.changes.find((c) => c.path === `orders/${p}`)!),
      state.changes,
    ),
  });
  const rows = (state: PackageState) => state.changes.map((c) => [c.path, c.status, c.index]);
  const text = async (path: string, rev = 'work') => {
    const read = await world!.api.file('orders', rev, `orders/${path}`);
    return 'text' in read ? read.text : null;
  };

  test('a modified, an added and a deleted file come back as in the last commit; another change and another staged path stay', async () => {
    const { api } = await one({
      files: { 'a.txt': textOf('a'), 'gone.txt': textOf('gone'), 'keep.txt': textOf('keep'), 's.txt': textOf('s') },
      staged: { 's.txt': textOf('s, staged') },
      edits: { 'a.txt': textOf('a, edited'), 'new.txt': textOf('new'), 'gone.txt': null, 'keep.txt': textOf('keep, edited') },
    });
    const before = await api.get('orders');

    const answer = await api.discard('orders', bodyOf(before, ['a.txt', 'new.txt', 'gone.txt']));

    expect(answer).toMatchObject({ discarded: ['orders/a.txt', 'orders/gone.txt', 'orders/new.txt'], left: [] });
    expect(answer.problem).toBeUndefined();
    const after = await api.get('orders');
    expect(answer.package).toEqual(after);
    expect(after.head).toEqual(before.head);
    expect(rows(after)).toEqual([
      ['orders/keep.txt', 'modified', 'clean'],
      ['orders/s.txt', 'modified', 'staged'],
    ]);
    expect(await text('a.txt')).toBe(textOf('a'));
    expect(await text('gone.txt')).toBe(textOf('gone'));
    await expect(api.file('orders', 'work', 'orders/new.txt')).rejects.toMatchObject({ status: 404 });
  });

  test('a staged modification, a staged add, a staged deletion and a staged rename are put back as in the last commit', async () => {
    const { api } = await one({
      files: { 'm.txt': textOf('m'), 'd.txt': textOf('d'), 'old.txt': textOf('moved') },
      moves: { 'old.txt': 'moved/new.txt' },
      staged: { 'm.txt': textOf('m, staged'), 'add.txt': textOf('add'), 'd.txt': null },
    });
    const before = await api.get('orders');
    expect(rows(before)).toEqual([
      ['orders/add.txt', 'added', 'staged'],
      ['orders/d.txt', 'deleted', 'staged'],
      ['orders/m.txt', 'modified', 'staged'],
      ['orders/moved/new.txt', 'renamed', 'staged'],
    ]);

    const answer = await api.discard('orders', bodyOf(before, ['add.txt', 'd.txt', 'm.txt', 'moved/new.txt']));

    expect(answer).toMatchObject({ discarded: ['orders/add.txt', 'orders/d.txt', 'orders/m.txt', 'orders/moved/new.txt', 'orders/old.txt'], left: [] });
    expect((await api.get('orders')).changes).toEqual([]);
    expect(await text('m.txt')).toBe(textOf('m'));
    expect(await text('d.txt')).toBe(textOf('d'));
    expect(await text('old.txt')).toBe(textOf('moved'));
  });

  test('what the author saw is stale: 412, and nothing changes', async () => {
    const { api, save, stage, id } = await one({
      commits: ['first', 'second'],
      files: { 'a.txt': textOf('a'), 'b.txt': textOf('b'), 'old.txt': textOf('moved'), 'twice.txt': textOf('twice') },
      moves: { 'old.txt': 'moved/new.txt' },
      edits: { 'a.txt': textOf('a, edited'), 'b.txt': textOf('b, edited'), 'twice.txt': textOf('twice, edited') },
    });
    const seen = await api.get('orders');
    const unchanged = async (asked: DiscardBody) => {
      const before = await api.get('orders');
      expect((await refusalOf(api.discard('orders', asked))).status, JSON.stringify(asked)).toBe(412);
      expect(await api.get('orders')).toEqual(before);
    };

    await unchanged({ ...bodyOf(seen, ['a.txt']), head: id('orders', 'first') });
    await unchanged({ ...bodyOf(seen, ['a.txt']), paths: [{ path: 'orders/first.txt', tag: null, index: 'clean' }] });
    await unchanged({ ...bodyOf(seen, ['moved/new.txt']), paths: bodyOf(seen, ['moved/new.txt']).paths.filter((p) => p.path !== 'orders/old.txt') });
    // in a two-path request with one stale path, both keep their disk and their index
    expect(await save('orders/b.txt', textOf('b, saved'), seen.changes.find((c) => c.path === 'orders/b.txt')!.tag!)).toBe(200);
    await unchanged(bodyOf(seen, ['a.txt', 'b.txt']));
    expect(await text('a.txt')).toBe(textOf('a, edited'));
    // a path staged after the review, with the same bytes
    await stage('orders/twice.txt');
    await unchanged(bodyOf(seen, ['twice.txt']));
    expect(rows(await api.get('orders'))).toContainEqual(['orders/twice.txt', 'modified', 'staged']);
  });

  test('the path a renamed change was renamed from, named as not on disk while it is on disk again, is 412', async () => {
    const { api } = await one({ files: { 'old.txt': textOf('moved') }, moves: { 'old.txt': 'moved/new.txt' }, edits: { 'old.txt': textOf('old, again') } });
    const before = await api.get('orders');
    const asked: DiscardBody = { head: before.head!.id, paths: [...bodyOf(before, ['moved/new.txt']).paths.filter((p) => p.path !== 'orders/old.txt'), { path: 'orders/old.txt', tag: null, index: null }] };

    expect((await refusalOf(api.discard('orders', asked))).status).toBe(412);
    expect(await api.get('orders')).toEqual(before);
  });

  test('a path outside the package, a .git segment, a link that leaves the package and hostile values are refused, and nothing changes', async () => {
    const { api } = await one({ edits: { 'new.txt': textOf('new') }, linkedOut: ['out.txt'] });
    const before = await api.get('orders');
    const body = bodyOf(before, ['new.txt']);

    expect((await refusalOf(api.discard('orders', { ...body, paths: [{ path: 'stock/new.txt', tag: null, index: 'clean' }] }))).status).toBe(404);
    expect((await refusalOf(api.discard('orders', { ...body, paths: [{ path: 'orders/.git/config', tag: null, index: 'clean' }] }))).status).toBe(400);
    expect((await refusalOf(api.discard('orders', bodyOf(before, ['out.txt'])))).status).toBe(403);
    for (const hostile of ['-x', ':(glob)*', '..']) {
      const refused = await refusalOf(api.discard('orders', { ...body, paths: [{ path: `orders/${hostile}`, tag: null, index: 'clean' }] }));
      expect([400, 412], hostile).toContain(refused.status);
    }
    expect(await api.get('orders')).toEqual(before);
    expect(await text('new.txt')).toBe(textOf('new'));
  });

  test('a discard that waits behind a commit is refused with 412, since HEAD moved', async () => {
    const { api, held } = await one({ files: { 'a.txt': textOf('a'), 'b.txt': textOf('b') }, edits: { 'a.txt': textOf('a, edited'), 'b.txt': textOf('b, edited') } });
    const before = await api.get('orders');
    const holding = held();

    const committing = api.commit('orders', { message: 'Commit a', branch: 'main', head: before.head!.id, paths: [{ path: 'orders/a.txt', tag: before.changes[0]!.tag }] });
    await holding;
    const discarding = refusalOf(api.discard('orders', bodyOf(before, ['b.txt'])));

    expect((await committing).commit).not.toBeNull();
    expect((await discarding).status).toBe(412);
    expect(await text('b.txt')).toBe(textOf('b, edited'));
  });

  test('a discard that waits behind a save is refused by its tag', async () => {
    const { api, save, held } = await one({ files: { 'a.txt': textOf('a') }, edits: { 'a.txt': textOf('a, edited') } });
    const before = await api.get('orders');
    const holding = held();

    const saved = save('orders/a.txt', textOf('a, saved'), before.changes[0]!.tag!);
    await holding;
    const discarding = refusalOf(api.discard('orders', bodyOf(before, ['a.txt'])));

    expect(await saved).toBe(200);
    expect((await discarding).status).toBe(412);
    expect(await text('a.txt')).toBe(textOf('a, saved'));
  });

  test('a save that waits behind a discard is refused by its recheck', async () => {
    const { api, save, held } = await one({ files: { 'a.txt': textOf('a') }, edits: { 'a.txt': textOf('a, edited') } });
    const before = await api.get('orders');
    const holding = held();

    const discarding = api.discard('orders', bodyOf(before, ['a.txt']));
    await holding;
    const saved = save('orders/a.txt', textOf('a, saved'), before.changes[0]!.tag!);

    expect((await discarding).discarded).toEqual(['orders/a.txt']);
    expect(await saved).toBe(412);
    expect(await text('a.txt')).toBe(textOf('a'));
  });

  test('a path whose index differs, a merge, a conflict and another git holding the index are 409; a detached HEAD discards', async () => {
    const differs = await one({ files: { 'a.txt': textOf('a') }, staged: { 'a.txt': textOf('a, staged') }, edits: { 'a.txt': textOf('a, edited') } });
    const seen = await differs.api.get('orders');
    expect(rows(seen)).toEqual([['orders/a.txt', 'modified', 'differs']]);
    expect((await refusalOf(differs.api.discard('orders', bodyOf(seen, ['a.txt'])))).status).toBe(409);
    expect(await differs.api.get('orders')).toEqual(seen);
    await world!.close();

    const busy: Omit<CloneSeed, 'folder'>[] = [{ conflict: true }, { conflict: true, unmerged: true }, { indexLock: true }];
    for (const clone of busy) {
      const { api } = await one({ ...clone, edits: { 'new.txt': textOf('new') } });
      const before = await api.get('orders');
      expect((await refusalOf(api.discard('orders', bodyOf(before, ['new.txt'])))).status, JSON.stringify(clone)).toBe(409);
      expect(await api.get('orders')).toEqual(before);
      await world!.close();
    }

    const { api } = await one({ detached: true, edits: { 'new.txt': textOf('new') } });
    expect((await api.discard('orders', bodyOf(await api.get('orders'), ['new.txt']))).discarded).toEqual(['orders/new.txt']);
  });

  test('a file in place of a folder the last commit has is refused with 409; alone it goes, and then the folder discards', async () => {
    const { api } = await one({ files: { 'd/x.txt': textOf('x') }, edits: { 'd/x.txt': null, d: textOf('d, a file') } });
    const before = await api.get('orders');
    expect(rows(before)).toEqual([
      ['orders/d', 'added', 'clean'],
      ['orders/d/x.txt', 'deleted', 'clean'],
    ]);

    expect((await refusalOf(api.discard('orders', bodyOf(before, ['d/x.txt'])))).status).toBe(409);
    expect(await api.get('orders')).toEqual(before);
    expect((await api.discard('orders', bodyOf(before, ['d']))).discarded).toEqual(['orders/d']);
    expect((await api.discard('orders', bodyOf(await api.get('orders'), ['d/x.txt']))).discarded).toEqual(['orders/d/x.txt']);
    expect(await text('d/x.txt')).toBe(textOf('x'));
  });

  test('a folder in place of a file the last commit has is refused with 409; its file alone goes, and then the file discards', async () => {
    const { api } = await one({ files: { d: textOf('d, a file') }, edits: { d: null, 'd/x.txt': textOf('x') } });
    const before = await api.get('orders');
    // git reads a file whose path is a folder on disk as deleted or modified, as its version allows
    expect(before.changes.map((c) => [c.path, c.index])).toEqual([
      ['orders/d', 'clean'],
      ['orders/d/x.txt', 'clean'],
    ]);

    expect((await refusalOf(api.discard('orders', bodyOf(before, ['d'])))).status).toBe(409);
    expect(await api.get('orders')).toEqual(before);
    expect((await api.discard('orders', bodyOf(before, ['d/x.txt']))).discarded).toEqual(['orders/d/x.txt']);
    // the emptied folder d is removed, and the file comes back
    expect((await api.discard('orders', bodyOf(await api.get('orders'), ['d']))).discarded).toEqual(['orders/d']);
    expect(await text('d')).toBe(textOf('d, a file'));
    expect((await api.get('orders')).changes).toEqual([]);
  });

  test('a staged file in place of a folder the last commit has is refused with 409, and reads differs', async () => {
    const { api } = await one({ files: { 'd/x.txt': textOf('x') }, staged: { 'd/x.txt': null, d: textOf('d, a file') }, edits: { d: null } });
    const before = await api.get('orders');
    expect(rows(before)).toEqual([
      ['orders/d', 'added', 'differs'],
      ['orders/d/x.txt', 'deleted', 'staged'],
    ]);

    expect((await refusalOf(api.discard('orders', bodyOf(before, ['d/x.txt'])))).status).toBe(409);
    expect(await api.get('orders')).toEqual(before);
  });

  test('on a branch with no commit yet, a staged added file and an untracked one both leave', async () => {
    const { api } = await one({ commits: [], staged: { 's.txt': textOf('s') }, edits: { 'u.txt': textOf('u') } });
    const before = await api.get('orders');
    expect(before.head).toBeNull();

    const answer = await api.discard('orders', bodyOf(before, ['s.txt', 'u.txt']));

    expect(answer).toMatchObject({ discarded: ['orders/s.txt', 'orders/u.txt'], left: [] });
    expect((await api.get('orders')).changes.map((c) => c.path)).toEqual(['orders/package.yaml']);
  });

  test('read-only and a foreign page are 403, a malformed body 400 and no path 422, and nothing changes', async () => {
    const seed = { edits: { 'new.txt': textOf('new') } };
    const readOnly = await one(seed, { readOnly: true });
    const seen = await readOnly.api.get('orders');
    expect((await refusalOf(readOnly.api.discard('orders', bodyOf(seen, ['new.txt'])))).status).toBe(403);
    expect(await readOnly.api.get('orders')).toEqual(seen);
    await world!.close();

    const foreign = await one(seed, { foreignPage: true });
    expect((await refusalOf(foreign.api.discard('orders', { head: null, paths: [{ path: 'orders/new.txt', tag: null, index: 'clean' }] }))).status).toBe(403);
    await world!.close();

    const { api } = await one(seed);
    const before = await api.get('orders');
    const body = bodyOf(before, ['new.txt']);
    const path = body.paths[0]!;
    const cases: [unknown, number][] = [
      [{ ...body, head: 'HEAD' }, 400],
      [{ ...body, paths: [{ ...path, index: 'differs' }] }, 400],
      [{ ...body, paths: [path, path] }, 400],
      [{ ...body, paths: Array.from({ length: 1001 }, (_, n) => ({ path: `orders/${n}.txt`, tag: null, index: 'clean' })) }, 400],
      [{ ...body, paths: [{ path: 'orders/new.txt', tag: null }] }, 400],
      [{ ...body, paths: [] }, 422],
    ];
    for (const [asked, status] of cases) expect((await refusalOf(api.discard('orders', asked as DiscardBody))).status, JSON.stringify(asked).slice(0, 80)).toBe(status);
    expect(await api.get('orders')).toEqual(before);
  });

  // The cases the fake does not model: links, modes, filters, sparse entries, intent to add, submodules, letter case.
  describe.runIf(name === 'http')('on real git', () => {
    const fileIn = (path: string) => join(world!.workingFolder!, 'orders', ...path.split('/'));

    test('a symbolic link on disk, a dangling one, a submodule, a skip-worktree entry, an intent-to-add entry and a case alias are 409', async () => {
      const { api, link, terminal } = await one({ files: { 'Readme.txt': textOf('readme'), 'sparse.txt': textOf('sparse') }, edits: { 'readme.txt': textOf('readme, lower') } });
      await link!('orders', 'link.txt', 'first.txt');
      await link!('orders', 'dangling.txt', 'nowhere.txt');
      terminal!('orders', ['update-index', '--add', '--cacheinfo', `160000,${terminal!('orders', ['rev-parse', 'HEAD'])},sub`]);
      terminal!('orders', ['commit', '-q', '-m', 'Add a submodule']);
      await writeFile(fileIn('sparse.txt'), textOf('sparse, staged'));
      terminal!('orders', ['add', '--', 'sparse.txt']);
      terminal!('orders', ['update-index', '--skip-worktree', '--', 'sparse.txt']);
      await writeFile(fileIn('intent.txt'), textOf('intent'));
      terminal!('orders', ['add', '-N', '--', 'intent.txt']);
      const before = await api.get('orders');
      expect(rows(before)).toEqual(
        expect.arrayContaining([
          ['orders/intent.txt', 'modified', 'clean'],
          ['orders/sparse.txt', 'modified', 'staged'],
          ['orders/sub', 'deleted', 'clean'],
        ]),
      );

      for (const path of ['link.txt', 'dangling.txt', 'sub', 'sparse.txt', 'intent.txt', 'readme.txt']) {
        expect((await refusalOf(api.discard('orders', bodyOf(before, [path])))).status, path).toBe(409);
      }
      expect(await api.get('orders')).toEqual(before);
    });

    test('a link to a folder in the way of a chosen path is 409, and the link stays', async () => {
      const { api } = await one({ files: { 'd/x.txt': textOf('x'), 'other/y.txt': textOf('y') }, edits: { 'd/x.txt': null } });
      await world!.link!('orders', 'd', 'other');
      const before = await api.get('orders');

      expect((await refusalOf(api.discard('orders', bodyOf(before, ['d/x.txt'])))).status).toBe(409);
      expect(await readlink(fileIn('d'))).toBe('other');
      expect(await api.get('orders')).toEqual(before);
    });

    test('a folder at a chosen path that holds only an ignored file is 409, and the ignored file stays', async () => {
      const { api } = await one({ files: { '.gitignore': '*.log\n', d: textOf('d') }, edits: { d: null } });
      await mkdir(fileIn('d'));
      await writeFile(fileIn('d/x.log'), 'ignored\n');
      const before = await api.get('orders');

      expect((await refusalOf(api.discard('orders', bodyOf(before, ['d'])))).status).toBe(409);
      expect(await readFile(fileIn('d/x.log'), 'utf8')).toBe('ignored\n');
    });

    test('a link comes back as a link, the executable bit comes back, and an eol=crlf file reads clean; no post-checkout hook runs', async () => {
      const { api, link, terminal } = await one({ files: { '.gitattributes': 'crlf.txt eol=crlf\n', 'crlf.txt': 'one\ntwo\n', 'run.sh': '#!/bin/sh\n' } });
      await chmod(fileIn('run.sh'), 0o755);
      await link!('orders', 'link.txt', 'first.txt');
      terminal!('orders', ['add', '-A']);
      terminal!('orders', ['commit', '-q', '-m', 'Add a link']);
      await rm(fileIn('link.txt'));
      await writeFile(fileIn('link.txt'), 'no link\n');
      await chmod(fileIn('run.sh'), 0o644);
      await writeFile(fileIn('crlf.txt'), 'one\r\nthree\r\n');
      await writeFile(join(world!.workingFolder!, 'orders', '.git', 'hooks', 'post-checkout'), '#!/bin/sh\ntouch hooked\n');
      await chmod(join(world!.workingFolder!, 'orders', '.git', 'hooks', 'post-checkout'), 0o755);
      const before = await api.get('orders');

      const answer = await api.discard('orders', bodyOf(before, ['crlf.txt', 'link.txt', 'run.sh']));

      expect(answer).toMatchObject({ discarded: ['orders/crlf.txt', 'orders/link.txt', 'orders/run.sh'], left: [] });
      expect(await readlink(fileIn('link.txt'))).toBe('first.txt');
      expect((await stat(fileIn('run.sh'))).mode & 0o111).not.toBe(0);
      expect(await readFile(fileIn('crlf.txt'), 'utf8')).toBe('one\r\ntwo\r\n');
      expect((await api.get('orders')).changes).toEqual([]);
      await expect(stat(fileIn('hooked'))).rejects.toMatchObject({ code: 'ENOENT' });
    });

    test('a required smudge filter that fails stops git at its path: it and the paths after it are left, with git words, and no unlink runs', async () => {
      const { api, terminal } = await one({
        files: { '.gitattributes': 'b.txt filter=bad\n', 'a.txt': textOf('a'), 'b.txt': textOf('b'), 'c.txt': textOf('c') },
        edits: { 'a.txt': textOf('a, edited'), 'b.txt': textOf('b, edited'), 'c.txt': textOf('c, edited'), 'u.txt': textOf('u') },
      });
      terminal!('orders', ['config', 'filter.bad.clean', 'cat']);
      terminal!('orders', ['config', 'filter.bad.smudge', 'false']);
      terminal!('orders', ['config', 'filter.bad.required', 'true']);
      const before = await api.get('orders');

      const answer = await api.discard('orders', bodyOf(before, ['a.txt', 'b.txt', 'c.txt', 'u.txt']));

      expect(answer.discarded).toEqual(['orders/a.txt']);
      expect(answer.left).toEqual(['orders/b.txt', 'orders/c.txt', 'orders/u.txt']);
      expect(answer.problem).toMatch(/smudge filter bad failed/);
      expect(rows(answer.package!)).toEqual([
        ['orders/b.txt', 'deleted', 'clean'],
        ['orders/c.txt', 'modified', 'clean'],
        ['orders/u.txt', 'added', 'clean'],
      ]);
      expect(await text('c.txt')).toBe(textOf('c, edited'));
    });
  });
});
