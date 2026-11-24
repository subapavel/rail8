import { afterEach, describe, expect, test } from 'vitest';
import { PackagesError, type Change, type CommitBody, type PackageState } from '../api';
import { httpWorld } from './httpWorld';
import { AUTHOR, fakeWorld, type Build, type CloneSeed, type Seed, type World } from './seed';

/** A text of several lines that names `what`, so that no two seeded files look alike to git's rename detection. */
const textOf = (what: string) => Array.from({ length: 6 }, (_, n) => `${what} line ${n}`).join('\n') + '\n';

// The commit cases on both implementations of PackagesApi: the real git half over real git, and the fake.
describe.each<[string, Build]>([
  ['http', httpWorld],
  ['fake', fakeWorld],
])('the %s git half commits', (name, build) => {
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
  const changeOf = (state: PackageState, path: string) => state.changes.find((c) => c.path === `orders/${path}`);
  /** A commit of `paths` as the state shows them, with `message`, on the state's branch and HEAD. */
  const bodyOf = (state: PackageState, paths: string[], message = 'Commit from the UI'): CommitBody => ({
    message,
    branch: state.branch!,
    head: state.head?.id ?? null,
    paths: paths.map((p) => ({ path: `orders/${p}`, tag: changeOf(state, p)?.tag ?? null })),
  });
  const view = (c: Change | undefined) => (c === undefined ? undefined : { status: c.status, index: c.index });

  test('the state names the author identity git gives in the clone, or none', async () => {
    const { api } = await one({});
    expect((await api.get('orders')).identity).toEqual(AUTHOR);
    await world!.close();
    const none = await one({}, { identity: null });
    expect((await none.api.get('orders')).identity).toBeNull();
  });

  test('the chosen paths alone, with the author as git gives them: what the author staged on other paths stays staged', async () => {
    const { api } = await one({
      files: { 'a.txt': textOf('a'), 'b.txt': textOf('b'), 's.txt': textOf('s'), 'gone.txt': textOf('gone') },
      staged: { 's.txt': textOf('s, staged') },
      edits: { 'a.txt': textOf('a, edited'), 'b.txt': textOf('b, edited'), 'new.txt': textOf('new'), 'gone.txt': null },
    });
    const before = await api.get('orders');

    const answer = await api.commit('orders', bodyOf(before, ['a.txt', 'new.txt', 'gone.txt'], 'Change a, add new\n\nand delete gone'));

    expect(answer.commit).toMatchObject({ parents: [before.head!.id], subject: 'Change a, add new', author: AUTHOR.name, staged: [], rewritten: [] });
    const after = await api.get('orders');
    expect(answer.package).toEqual(after);
    expect(after.head!.id).toBe(answer.commit!.id);
    expect(after.changes.map((c) => [c.path, c.index])).toEqual([
      ['orders/b.txt', 'clean'],
      ['orders/s.txt', 'staged'],
    ]);
    expect(await api.file('orders', answer.commit!.id, 'orders/new.txt')).toMatchObject({ text: textOf('new') });
    expect(await api.file('orders', answer.commit!.id, 'orders/a.txt')).toMatchObject({ text: textOf('a, edited') });
    expect(await api.file('orders', answer.commit!.id, 'orders/b.txt')).toMatchObject({ text: textOf('b') });
    expect(await api.file('orders', answer.commit!.id, 'orders/s.txt')).toMatchObject({ text: textOf('s') });
    await expect(api.file('orders', answer.commit!.id, 'orders/gone.txt')).rejects.toMatchObject({ status: 404 });
  });

  test('a renamed file named with the path it was renamed from, as not on disk, is committed as a rename', async () => {
    const { api } = await one({ files: { 'old.txt': textOf('moved') }, moves: { 'old.txt': 'moved/new.txt' } });
    const before = await api.get('orders');
    expect(view(changeOf(before, 'moved/new.txt'))).toEqual({ status: 'renamed', index: 'staged' });

    const answer = await api.commit('orders', bodyOf(before, ['moved/new.txt', 'old.txt']));

    expect(answer.commit).toMatchObject({ staged: [], rewritten: [], changedOnDisk: [] });
    expect(answer.package!.changes).toEqual([]);
    expect((await api.changes('orders', before.head!.id, answer.commit!.id)).changes.map((c) => [c.path, c.status, c.renamedFrom])).toEqual([
      ['orders/moved/new.txt', 'renamed', 'orders/old.txt'],
    ]);
  });

  test('a renamed file named by its new path alone is refused with 412, as is one renamed from another path than the one named', async () => {
    const { api } = await one({ files: { 'a.txt': textOf('same'), 'b.txt': textOf('same') }, moves: { 'b.txt': 'new.txt' } });
    const before = await api.get('orders');
    expect(changeOf(before, 'new.txt')?.renamedFrom).toBe('orders/b.txt');

    const alone = await refusalOf(api.commit('orders', bodyOf(before, ['new.txt'])));
    // the author reviewed a.txt renamed to new.txt, the same bytes; a terminal has since made it b.txt
    const other = await refusalOf(api.commit('orders', { ...bodyOf(before, ['new.txt']), paths: [...bodyOf(before, ['new.txt']).paths, { path: 'orders/a.txt', tag: null }] }));

    expect(alone).toMatchObject({ status: 412, message: 'orders/new.txt is renamed from orders/b.txt, which is not among the paths chosen with it; nothing was committed' });
    expect(other.status).toBe(412);
    expect(await api.get('orders')).toEqual(before);
  });

  test('a renamed file whose old path is named as not on disk is refused with 412 while that path is on disk, and is committed when both are chosen', async () => {
    const { api } = await one({ files: { 'old.txt': textOf('moved') }, moves: { 'old.txt': 'moved/new.txt' }, edits: { 'old.txt': textOf('old, written again') } });
    const before = await api.get('orders');

    const refused = await refusalOf(api.commit('orders', { ...bodyOf(before, ['moved/new.txt']), paths: [...bodyOf(before, ['moved/new.txt']).paths, { path: 'orders/old.txt', tag: null }] }));

    expect(refused).toMatchObject({ status: 412, message: 'orders/old.txt, which orders/moved/new.txt was renamed from, is on disk: choose it too; nothing was committed' });
    expect((await api.get('orders')).head).toEqual(before.head);
    const answer = await api.commit('orders', bodyOf(before, ['moved/new.txt', 'old.txt']));
    expect(await api.file('orders', answer.commit!.id, 'orders/old.txt')).toMatchObject({ text: textOf('old, written again') });
    expect(await api.file('orders', answer.commit!.id, 'orders/moved/new.txt')).toMatchObject({ text: textOf('moved') });
  });

  test('the first commit of a branch that has none yet', async () => {
    const { api } = await one({ commits: [], files: { 'a.txt': textOf('a') }, staged: { 's.txt': textOf('s') } });
    const before = await api.get('orders');
    expect(before.head).toBeNull();

    const answer = await api.commit('orders', bodyOf(before, ['package.yaml', 'a.txt']));

    expect(answer.commit).toMatchObject({ parents: [], staged: [], rewritten: [] });
    expect(answer.package!.changes.map((c) => [c.path, c.status, c.index])).toEqual([['orders/s.txt', 'added', 'staged']]);
  });

  test('a chosen path the author staged with other content than on disk is refused with 409, and nothing is committed', async () => {
    const { api } = await one({ files: { 'a.txt': textOf('a'), 'b.txt': textOf('b') }, staged: { 'a.txt': textOf('a, staged') }, edits: { 'a.txt': textOf('a, edited'), 'b.txt': textOf('b, edited') } });
    const before = await api.get('orders');
    expect(changeOf(before, 'a.txt')?.index).toBe('differs');

    expect((await refusalOf(api.commit('orders', bodyOf(before, ['b.txt', 'a.txt'])))).status).toBe(409);
    // a tag that is stale as well is the 412 of the tags, which are checked first
    const stale = bodyOf(before, ['b.txt', 'a.txt']);
    stale.paths[1]!.tag = '"stale"';
    expect((await refusalOf(api.commit('orders', stale))).status).toBe(412);
    expect(await api.get('orders')).toEqual(before);
  });

  test('a stale branch, HEAD or tag, or a path no longer among the changes, is refused with 412, and nothing is committed', async () => {
    const { api, id } = await one({ commits: ['first', 'second'], files: { 'a.txt': textOf('a') }, edits: { 'a.txt': textOf('a, edited'), 'new.txt': textOf('new') } });
    const before = await api.get('orders');
    const body = bodyOf(before, ['a.txt', 'new.txt']);

    const stale: CommitBody[] = [
      { ...body, branch: 'other' },
      { ...body, head: id('orders', 'first') },
      { ...body, head: null },
      { ...body, paths: [{ path: 'orders/a.txt', tag: changeOf(before, 'new.txt')!.tag }] },
      { ...body, paths: [{ path: 'orders/new.txt', tag: null }] },
      { ...body, paths: [{ path: 'orders/first.txt', tag: (await api.file('orders', 'work', 'orders/first.txt')).etag }] },
    ];
    for (const asked of stale) expect((await refusalOf(api.commit('orders', asked))).status).toBe(412);
    expect(await api.get('orders')).toEqual(before);
  });

  test('a hook that refuses: 422 with what it said, and only the paths the request added leave the index', async () => {
    const { api } = await one({
      files: { 'a.txt': textOf('a') },
      staged: { 'mine.txt': textOf('mine') },
      edits: { 'a.txt': textOf('a, edited'), 'new.txt': textOf('new') },
      hook: { says: 'lint: new.txt has trailing spaces', refuses: true },
    });
    const before = await api.get('orders');

    const refused = await refusalOf(api.commit('orders', bodyOf(before, ['a.txt', 'new.txt'])));

    expect(refused).toMatchObject({ status: 422, message: 'git commit failed, as git and the hooks say; nothing was committed' });
    expect(refused.output).toContain('lint: new.txt has trailing spaces');
    expect(await api.get('orders')).toEqual(before);
    expect(view(changeOf(before, 'new.txt'))).toEqual({ status: 'added', index: 'clean' });
    expect(view(changeOf(before, 'mine.txt'))).toEqual({ status: 'added', index: 'staged' });
  });

  test('a hook that rewrites an added file and refuses: that file stays staged, the error names a command that unstages it, and the other added file leaves the index', async () => {
    const { api } = await one({ edits: { 'new.txt': textOf('new'), 'other.txt': textOf('other') }, hook: { writes: { 'new.txt': textOf('new, formatted') }, refuses: true } });
    const before = await api.get('orders');

    const refused = await refusalOf(api.commit('orders', bodyOf(before, ['new.txt', 'other.txt'])));

    expect(refused).toMatchObject({
      status: 422,
      message: 'git commit failed, as git and the hooks say; nothing was committed; orders/new.txt stay staged, since git would not take them out of the index: unstage them in a terminal with git restore --staged -- new.txt',
    });
    expect(refused.output).toContain('staged content different from both');
    const after = await api.get('orders');
    expect(after.head).toEqual(before.head);
    expect(view(changeOf(after, 'new.txt'))).toEqual({ status: 'added', index: 'differs' });
    expect(view(changeOf(after, 'other.txt'))).toEqual({ status: 'added', index: 'clean' });
    expect(await api.file('orders', 'work', 'orders/new.txt')).toMatchObject({ text: textOf('new, formatted') });
  });

  test('on a branch with no commit yet, the command the error names for a file that stays staged is git rm --cached -f', async () => {
    const { api } = await one({ commits: [], edits: { 'new.txt': textOf('new') }, hook: { writes: { 'new.txt': textOf('new, formatted') }, refuses: true } });
    const before = await api.get('orders');

    const refused = await refusalOf(api.commit('orders', bodyOf(before, ['new.txt'])));

    expect(refused.message).toBe(
      'git commit failed, as git and the hooks say; nothing was committed; orders/new.txt stay staged, since git would not take them out of the index: unstage them in a terminal with git rm --cached -f -- new.txt',
    );
  });

  test.runIf(name === 'http')('an added symbolic link leaves the index again when a hook refuses', async () => {
    const { api, link, terminal } = await one({ hook: { refuses: true } });
    await link!('orders', 'link.txt', 'first.txt');
    const before = await api.get('orders');

    const refused = await refusalOf(api.commit('orders', bodyOf(before, ['link.txt'])));

    expect(refused).toMatchObject({ status: 422, message: 'git commit failed, as git and the hooks say; nothing was committed' });
    expect(terminal!('orders', ['ls-files', '--', 'link.txt'])).toBe('');
    expect(view(changeOf(await api.get('orders'), 'link.txt'))).toEqual({ status: 'added', index: 'clean' });
  });

  test.runIf(name === 'http')('a committed symbolic link is read back as git stores it: neither rewritten nor changed on disk', async () => {
    const { api, link, terminal } = await one({});
    await link!('orders', 'link.txt', 'first.txt');
    const before = await api.get('orders');

    const answer = await api.commit('orders', bodyOf(before, ['link.txt']));

    expect(answer.commit).toMatchObject({ staged: [], rewritten: [], changedOnDisk: [] });
    expect(terminal!('orders', ['ls-files', '-s', '--', 'link.txt'])).toMatch(/^120000 /);
  });

  test.runIf(name === 'http')('the command the error names unstages, in a terminal, each file git would not take out of the index', async () => {
    for (const commits of [undefined, []]) {
      const { api, terminal } = await one({ ...(commits === undefined ? {} : { commits }), edits: { 'new.txt': textOf('new') }, hook: { writes: { 'new.txt': textOf('new, formatted') }, refuses: true } });
      const before = await api.get('orders');
      const refused = await refusalOf(api.commit('orders', bodyOf(before, ['new.txt'])));

      const command = / with git (.+)$/.exec(refused.message)![1]!.split(' ');
      terminal!('orders', command);

      expect(view(changeOf(await api.get('orders'), 'new.txt'))).toEqual({ status: 'added', index: 'clean' });
      await world!.close();
      world = undefined;
    }
  });

  test.runIf(name === 'http')('a close of the server right after the add: the added file leaves the index again, and nothing was committed', async () => {
    const { api, closesAfter, terminal } = await one({ edits: { 'new.txt': textOf('new') } });
    const before = await api.get('orders');
    closesAfter!('add');

    const refused = await refusalOf(api.commit('orders', bodyOf(before, ['new.txt'])));

    expect(refused.status).toBe(503);
    expect(refused.message).toMatch(/; nothing was committed$/);
    expect(terminal!('orders', ['ls-files', '--stage', '--', 'new.txt'])).toBe('');
    expect(terminal!('orders', ['rev-parse', 'HEAD'])).toBe(before.head!.id);
  });

  test('a hook that refuses, then a terminal that stages an added file before the undo: that file stays staged as the terminal staged it, and the error says so', async () => {
    const { api } = await one({
      edits: { 'new.txt': textOf('new'), 'other.txt': textOf('other') },
      hook: { refuses: true },
      afterwards: { stages: { 'new.txt': textOf('new, by the author') } },
    });
    const before = await api.get('orders');

    const refused = await refusalOf(api.commit('orders', bodyOf(before, ['new.txt', 'other.txt'])));

    expect(refused).toMatchObject({
      status: 422,
      message: 'git commit failed, as git and the hooks say; nothing was committed; orders/new.txt stay staged, as another git staged them meanwhile',
    });
    const after = await api.get('orders');
    expect(view(changeOf(after, 'new.txt'))).toEqual({ status: 'added', index: 'staged' });
    expect(view(changeOf(after, 'other.txt'))).toEqual({ status: 'added', index: 'clean' });
  });

  test('git stopped after it committed: the error says HEAD moved, never that nothing was committed, and the commit stays', async () => {
    const { api } = await one({ edits: { 'new.txt': textOf('new') }, hook: { stopsGit: true } });
    const before = await api.get('orders');

    const refused = await refusalOf(api.commit('orders', bodyOf(before, ['new.txt'])));

    const after = await api.get('orders');
    expect(after.head!.subject).toBe('Commit from the UI');
    expect(refused).toMatchObject({
      status: 422,
      message: `git commit failed, as git and the hooks say; HEAD moved to ${after.head!.id.slice(0, 7)} meanwhile, so git may have committed: the package's history shows it`,
    });
    expect(after.changes).toEqual([]);
  });

  test('git gives no identity: 422 naming git config, and nothing is committed', async () => {
    const { api } = await one({ edits: { 'new.txt': textOf('new') } }, { identity: null });
    const before = await api.get('orders');

    const refused = await refusalOf(api.commit('orders', bodyOf(before, ['new.txt'])));

    expect(refused.status).toBe(422);
    expect(refused.message).toContain('git config user.name');
    expect(await api.get('orders')).toEqual(before);
  });

  test('the read-back names a chosen path a hook rewrote and a path it staged, and undoes neither', async () => {
    const { api } = await one({
      files: { 'a.txt': textOf('a'), 'b.txt': textOf('b') },
      edits: { 'a.txt': textOf('a, edited') },
      hook: { says: 'formatted a.txt', writes: { 'a.txt': textOf('a, formatted'), 'b.txt': textOf('b, by the hook'), 'hook.txt': textOf('hook') } },
    });
    const before = await api.get('orders');

    const answer = await api.commit('orders', bodyOf(before, ['a.txt']));

    expect(answer.commit).toMatchObject({ staged: ['orders/b.txt', 'orders/hook.txt'], rewritten: ['orders/a.txt'], changedOnDisk: [] });
    expect(answer.output).toContain('formatted a.txt');
    for (const [path, text] of [
      ['a.txt', 'a, formatted'],
      ['b.txt', 'b, by the hook'],
      ['hook.txt', 'hook'],
    ] as const) {
      expect(await api.file('orders', answer.commit!.id, `orders/${path}`)).toMatchObject({ text: textOf(text) });
      expect(await api.file('orders', 'work', `orders/${path}`)).toMatchObject({ text: textOf(text) });
    }
    // git's index keeps what it held before the hook
    expect(answer.package!.changes.map((c) => [c.path, c.index])).toEqual([
      ['orders/a.txt', 'differs'],
      ['orders/b.txt', 'differs'],
      ['orders/hook.txt', 'differs'],
    ]);
  });

  test('a commit made in a terminal between the check and the commit is the new commit’s parent, and nothing is lost', async () => {
    const { api, id } = await one({ edits: { 'new.txt': textOf('new') }, meanwhile: 'terminal' });
    const before = await api.get('orders');

    const answer = await api.commit('orders', bodyOf(before, ['new.txt']));

    expect(answer.commit!.parents).toEqual([id('orders', 'terminal')]);
    expect(answer.commit).toMatchObject({ staged: [], rewritten: [], changedOnDisk: [] });
    expect((await api.log('orders')).items.map((c) => c.subject)).toEqual(['Commit from the UI', 'terminal', 'first']);
  });

  test('a commit made in a terminal right after git’s is not taken for it: the commit is null, and problem names both', async () => {
    const { api, id } = await one({ edits: { 'new.txt': textOf('new') }, afterwards: { commits: 'terminal' } });
    const before = await api.get('orders');

    const answer = await api.commit('orders', bodyOf(before, ['new.txt']));

    const log = (await api.log('orders')).items;
    expect(log.map((c) => c.subject)).toEqual(['terminal', 'Commit from the UI', 'first']);
    expect(answer.commit).toBeNull();
    expect(answer.problem).toBe(
      `git committed ${log[1]!.id.slice(0, 7)}, but HEAD moved on at once to ${id('orders', 'terminal').slice(0, 7)}, as when a terminal commits or amends right after, so the commit HEAD shows is not the one git made`,
    );
    expect(answer.package!.head!.id).toBe(id('orders', 'terminal'));
  });

  test('a terminal that amends git’s commit right after is not taken for it, though the amended commit has the same parent', async () => {
    const { api, id } = await one({ edits: { 'new.txt': textOf('new') }, afterwards: { amends: 'amended' } });
    const before = await api.get('orders');

    const answer = await api.commit('orders', bodyOf(before, ['new.txt']));

    const amended = id('orders', 'amended');
    expect(answer.commit).toBeNull();
    expect(answer.problem).toMatch(
      new RegExp(`^git committed (?!${amended.slice(0, 7)})[0-9a-f]{7}, but HEAD moved on at once to ${amended.slice(0, 7)}, as when a terminal commits or amends right after, so the commit HEAD shows is not the one git made$`),
    );
    expect(answer.package!.head).toMatchObject({ id: amended, parents: [before.head!.id], subject: 'amended' });
  });

  test('a hook that changes a chosen file on disk without staging it: the commit holds the reviewed version, and the read-back names the file', async () => {
    const { api } = await one({ files: { 'a.txt': textOf('a') }, edits: { 'a.txt': textOf('a, edited') }, hook: { unstaged: { 'a.txt': textOf('a, by the hook') } } });
    const before = await api.get('orders');

    const answer = await api.commit('orders', bodyOf(before, ['a.txt']));

    expect(answer.commit).toMatchObject({ staged: [], rewritten: [], changedOnDisk: ['orders/a.txt'] });
    expect(await api.file('orders', answer.commit!.id, 'orders/a.txt')).toMatchObject({ text: textOf('a, edited') });
    expect(await api.file('orders', 'work', 'orders/a.txt')).toMatchObject({ text: textOf('a, by the hook') });
    expect(answer.package!.changes.map((c) => c.path)).toEqual(['orders/a.txt']);
  });

  test('a save that waits for the commit is refused by its recheck after a hook rewrote its file', async () => {
    const { api, save, held } = await one({ files: { 'a.txt': textOf('a') }, edits: { 'a.txt': textOf('a, edited') }, hook: { writes: { 'a.txt': textOf('a, formatted') } } });
    const before = await api.get('orders');
    const holding = held();

    const committing = api.commit('orders', bodyOf(before, ['a.txt']));
    await holding;
    const saved = save('orders/a.txt', textOf('a, edited again'), changeOf(before, 'a.txt')!.tag!);

    expect((await committing).commit!.rewritten).toEqual(['orders/a.txt']);
    expect(await saved).toBe(412);
    expect(await api.file('orders', 'work', 'orders/a.txt')).toMatchObject({ text: textOf('a, formatted') });
  });

  test('a busy clone or a detached HEAD is refused with 409, and nothing is committed', async () => {
    const busy: Omit<CloneSeed, 'folder'>[] = [{ detached: true }, { conflict: true }, { conflict: true, unmerged: true }, { indexLock: true }];
    for (const clone of busy) {
      const { api } = await one({ ...clone, edits: { 'new.txt': textOf('new') } });
      const before = await api.get('orders');
      const body: CommitBody = { ...bodyOf(before, ['new.txt']), branch: before.branch ?? 'main' };

      expect((await refusalOf(api.commit('orders', body))).status, JSON.stringify(clone)).toBe(409);
      expect(await api.get('orders')).toEqual(before);
      await world!.close();
      world = undefined;
    }
  });

  test('under RAIL8_READ_ONLY=true a commit is refused with 403', async () => {
    const { api } = await one({ edits: { 'new.txt': textOf('new') } }, { readOnly: true });
    const before = await api.get('orders');

    expect((await refusalOf(api.commit('orders', bodyOf(before, ['new.txt'])))).status).toBe(403);
    expect(await api.get('orders')).toEqual(before);
  });

  test('a malformed body is refused with 400, a path outside the package with 404, and no path at all with 422', async () => {
    const { api } = await one({ edits: { 'new.txt': textOf('new') } });
    const before = await api.get('orders');
    const body = bodyOf(before, ['new.txt']);
    const path = { path: 'orders/new.txt', tag: changeOf(before, 'new.txt')!.tag };

    const cases: [unknown, number][] = [
      [{ ...body, message: ' \n' }, 400],
      [{ ...body, message: `x${'\0'}` }, 400],
      [{ ...body, message: 'x'.repeat(8 * 1024 + 1) }, 400],
      [{ ...body, head: 'HEAD' }, 400],
      [{ ...body, paths: [path, path] }, 400],
      [{ ...body, paths: Array.from({ length: 1001 }, (_, n) => ({ path: `orders/${n}.txt`, tag: null })) }, 400],
      [{ ...body, paths: [{ path: 'orders/.git/config', tag: null }] }, 400],
      [{ ...body, paths: [{ path: 'stock/new.txt', tag: null }] }, 404],
      [{ ...body, paths: [] }, 422],
    ];
    for (const [asked, status] of cases) expect((await refusalOf(api.commit('orders', asked as CommitBody))).status).toBe(status);
    expect(await api.get('orders')).toEqual(before);
  });
});
