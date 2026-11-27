import { unlinkSync } from 'node:fs';
import { mkdir, mkdtemp, readdir, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { heldWrites } from './heldWrites';
import { admitted, createFile, MAX_FILE_BYTES, MAX_FILES, MAX_WAITING_WRITES, Refusal, replaceFile, walk, workingFolderWrites, writeNew } from './workingFolder';

let root: string;

beforeEach(async () => {
  root = await mkdtemp(join(tmpdir(), 'rail8-replace-'));
});

afterEach(async () => {
  await rm(root, { recursive: true, force: true });
});

/** A promise the test resolves itself. */
function gate() {
  let open = () => undefined as void;
  const shut = new Promise<void>((done) => (open = done));
  return { shut, open };
}

/** Resolves once every promise that can settle now has settled: no timer, no time passes. */
const settled = () => new Promise((done) => setImmediate(done));

describe('workingFolderWrites', () => {
  it('runs the works its writes give the lock one at a time, first in, first out, and answers what each answers', async () => {
    const writes = workingFolderWrites();
    const ran: string[] = [];
    const first = gate();
    const one = admitted(writes, (lock) =>
      lock(async () => {
        ran.push('one');
        await first.shut;
        return 1;
      }),
    );
    const two = admitted(writes, (lock) => lock(async () => (ran.push('two'), 2)));
    const three = admitted(writes, (lock) => lock(async () => (ran.push('three'), 3)));
    await settled();
    expect(ran).toEqual(['one']);
    first.open();
    expect(await Promise.all([one, two, three])).toEqual([1, 2, 3]);
    expect(ran).toEqual(['one', 'two', 'three']);
  });

  it('passes on the failure of a work, and runs the next one', async () => {
    const writes = workingFolderWrites();
    const failed = admitted(writes, (lock) =>
      lock(async () => {
        throw new Refusal(412, 'file-changed', 'the file changed');
      }),
    );
    const next = admitted(writes, (lock) => lock(async () => 'next'));
    await expect(failed).rejects.toThrow(new Refusal(412, 'file-changed', 'the file changed'));
    expect(await next).toBe('next');
  });

  it('counts a write from its admission to its end, whatever it waits for, and runs nothing once one and 8 more are under way', async () => {
    const writes = workingFolderWrites();
    const elsewhere = gate();
    // none of these has asked for the lock yet: each waits for something else, as a save waits for the node
    const underWay = Array.from({ length: MAX_WAITING_WRITES + 1 }, (_, i) =>
      admitted(writes, async () => {
        await elsewhere.shut;
        if (i === 0) throw new Refusal(412, 'file-changed', 'the file changed');
        return i;
      }),
    );
    let ran = false;
    expect(
      writes(async () => {
        ran = true;
      }),
    ).toBeUndefined();
    elsewhere.open();
    await expect(underWay[0]).rejects.toThrow('the file changed');
    expect(await Promise.all(underWay.slice(1))).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
    expect(ran).toBe(false);
    // a write that failed has ended too: every place is free again
    expect(await Promise.all(Array.from({ length: MAX_WAITING_WRITES + 1 }, (_, i) => admitted(writes, async () => i)))).toHaveLength(9);
  });
});

describe('admitted', () => {
  it('answers what the write answers, and refuses with 503, running nothing, once one write and 8 more are under way', async () => {
    const { writes, release } = heldWrites();
    expect(MAX_WAITING_WRITES).toBe(8);
    const waiting = Array.from({ length: MAX_WAITING_WRITES - 1 }, () => admitted(writes, (lock) => lock(async () => undefined)));
    const last = admitted(writes, (lock) => lock(async () => 'the eighth'));
    let ran = false;
    const refused = admitted(writes, async () => {
      ran = true;
    });
    await expect(refused).rejects.toThrow(new Refusal(503, 'working-folder-busy', 'the working folder is busy: 8 writes wait already; nothing was written, try again'));
    expect(await refused.catch((error: Refusal) => error.status)).toBe(503);
    release();
    await Promise.all(waiting);
    expect(await last).toBe('the eighth');
    expect(ran).toBe(false);
  });
});

describe('replaceFile', () => {
  it('writes the text through a temporary file renamed over the file', async () => {
    const file = join(root, 'flow.yaml');
    await writeFile(file, 'old\n');
    await admitted(workingFolderWrites(), (lock) => replaceFile(lock, file, 'new\n'));
    expect(await readFile(file, 'utf8')).toBe('new\n');
    expect(await readdir(root)).toEqual(['flow.yaml']);
  });

  it('runs its check just before the rename, and leaves the file as it is when the check fails', async () => {
    const file = join(root, 'flow.yaml');
    await writeFile(file, 'old\n');
    const seen: string[] = [];
    const changed = async () => {
      seen.push(...(await readdir(root)).filter((f) => f.endsWith('.tmp')).map(() => 'temporary file written'));
      await writeFile(file, 'changed by another program\n');
      throw new Refusal(412, 'file-changed', 'the file changed since it was read; nothing was saved');
    };
    await expect(admitted(workingFolderWrites(), (lock) => replaceFile(lock, file, 'new\n', changed))).rejects.toThrow('the file changed since it was read');
    expect(seen).toEqual(['temporary file written']);
    expect(await readFile(file, 'utf8')).toBe('changed by another program\n');
    expect(await readdir(root)).toEqual(['flow.yaml']);
  });

  it('writes nothing, not even its temporary file, while another write holds the lock', async () => {
    const file = join(root, 'flow.yaml');
    await writeFile(file, 'old\n');
    const { writes, waiting, release } = heldWrites();
    const replacing = admitted(writes, (lock) => replaceFile(lock, file, 'new\n'));
    await waiting;
    await settled();
    expect(await readdir(root)).toEqual(['flow.yaml']);
    expect(await readFile(file, 'utf8')).toBe('old\n');
    release();
    await replacing;
    expect(await readFile(file, 'utf8')).toBe('new\n');
  });

  it('leaves it to its check when the file is gone by its turn, and writes nothing', async () => {
    const file = join(root, 'flow.yaml');
    await writeFile(file, 'old\n');
    const { writes, waiting, release } = heldWrites();
    const gone = new Refusal(412, 'file-changed', 'the file changed since it was read; nothing was saved');
    const replacing = admitted(writes, (lock) =>
      replaceFile(lock, file, 'new\n', async () => {
        throw gone;
      }),
    );
    await waiting;
    await rm(file);
    release();
    await expect(replacing).rejects.toBe(gone);
    expect(await readdir(root)).toEqual([]);
  });
});

describe('writeNew', () => {
  it('writes the new file only once the lock is free, and never over a file another write made meanwhile', async () => {
    const file = join(root, 'flow.yaml');
    const { writes, waiting, release } = heldWrites();
    const writing = admitted(writes, (lock) => writeNew(lock, file, 'mine\n'));
    await waiting;
    await settled();
    expect(await readdir(root)).toEqual([]);
    await writeFile(file, 'made by a pull\n');
    release();
    expect(await writing).toBe(false);
    expect(await readFile(file, 'utf8')).toBe('made by a pull\n');
    expect(await readdir(root)).toEqual(['flow.yaml']);
  });
});

describe('createFile', () => {
  const isYaml = (name: string) => name.endsWith('.yaml');

  it('looks for its folder under the lock, so a folder removed while it waited is no folder', async () => {
    await mkdir(join(root, 'flows'));
    const { writes, waiting, release } = heldWrites();
    const creating = admitted(writes, (lock) => createFile(lock, root, 'flows/new.yaml', isYaml, 'flow file', 'kind: flow\n'));
    await waiting;
    await rm(join(root, 'flows'), { recursive: true });
    release();
    await expect(creating).rejects.toThrow(new Refusal(404, 'no-folder', 'no folder flows in the working folder', { path: 'flows' }));
    expect(await readdir(root)).toEqual([]);
  });

  it('refuses a malformed path before it asks for the lock', async () => {
    const { writes, release } = heldWrites();
    await expect(admitted(writes, (lock) => createFile(lock, root, 'flows/../x.yaml', isYaml, 'flow file', ''))).rejects.toThrow(new Refusal(400, 'path-refused', 'the path names no flow file of the working folder'));
    release();
  });
});

describe('walk', () => {
  const isJson = (name: string) => name.endsWith('.json');

  it('still lists the other files when a file disappears during enumeration', async () => {
    await writeFile(join(root, 'gone.json'), 'old');
    await writeFile(join(root, 'kept.json'), 'current');
    const found = await walk(root, (name) => {
      if (name === 'gone.json') unlinkSync(join(root, name));
      return isJson(name);
    });
    expect(found.files).toEqual([{ path: 'kept.json', text: 'current' }]);
  });

  it('reads changed files and newly added files on the next walk', async () => {
    await writeFile(join(root, 'first.json'), 'one');
    expect((await walk(root, isJson)).files).toEqual([{ path: 'first.json', text: 'one' }]);
    await writeFile(join(root, 'first.json'), 'two');
    await writeFile(join(root, 'next.json'), 'next');
    expect((await walk(root, isJson)).files).toEqual([
      { path: 'first.json', text: 'two' },
      { path: 'next.json', text: 'next' },
    ]);
  });

  it('stops visiting files when the reader leaves', async () => {
    await writeFile(join(root, 'a.json'), 'a');
    await writeFile(join(root, 'b.json'), 'b');
    const controller = new AbortController();
    const visited: string[] = [];
    const read = walk(root, (name) => {
      visited.push(name);
      controller.abort();
      return false;
    }, controller.signal);
    await expect(read).rejects.toMatchObject({ name: 'AbortError' });
    expect(visited).toEqual(['a.json']);
  });

  it('reads the files it takes, in path order, and names those over the limit apart, unread', async () => {
    await mkdir(join(root, 'b', 'deep'), { recursive: true });
    await mkdir(join(root, '.hidden'));
    await mkdir(join(root, 'node_modules'));
    await writeFile(join(root, 'b', 'deep', 'one.json'), '1');
    await writeFile(join(root, 'a.json'), '2');
    await writeFile(join(root, 'b', 'large.json'), 'x'.repeat(MAX_FILE_BYTES + 1));
    await writeFile(join(root, 'b', 'notes.txt'), 'not taken');
    await writeFile(join(root, '.hidden', 'secret.json'), '3');
    await writeFile(join(root, 'node_modules', 'dependency.json'), '4');
    await symlink(join(root, 'a.json'), join(root, 'linked.json'));

    expect(await walk(root, isJson)).toEqual({
      files: [
        { path: 'a.json', text: '2' },
        { path: 'b/deep/one.json', text: '1' },
      ],
      large: ['b/large.json'],
      listed: [],
      truncated: false,
    });
  });

  it('lists every other file too, unread, with a limit of its own', async () => {
    await mkdir(join(root, 'b'));
    await writeFile(join(root, 'a.json'), '1');
    await writeFile(join(root, 'b', 'schema.xsd'), '<xs:schema/>');
    await writeFile(join(root, 'notes.md'), 'notes');
    await writeFile(join(root, '.hidden.md'), 'hidden');

    expect(await walk(root, isJson, undefined, true)).toEqual({ files: [{ path: 'a.json', text: '1' }], large: [], listed: ['b/schema.xsd', 'notes.md'], truncated: false });
    for (let i = 0; i < MAX_FILES; i++) await writeFile(join(root, `${String(i).padStart(4, '0')}.md`), '');
    await writeFile(join(root, 'z.json'), '2');
    const cut = await walk(root, isJson, undefined, true);
    expect(cut.listed).toHaveLength(MAX_FILES);
    expect(cut.files.map((f) => f.path)).toEqual(['a.json', 'z.json']);
    expect(cut.truncated).toBe(true);
  });

  it(`stops past ${MAX_FILES} files it takes, large ones among them`, async () => {
    await writeFile(join(root, '0000.json'), ' '.repeat(MAX_FILE_BYTES + 1));
    for (let i = 1; i <= MAX_FILES; i++) await writeFile(join(root, `${String(i).padStart(4, '0')}.json`), '{}');
    const { files, large, truncated } = await walk(root, isJson);
    expect(large).toEqual(['0000.json']);
    expect(files).toHaveLength(MAX_FILES - 1);
    expect(files.at(-1)?.path).toBe('0999.json');
    expect(truncated).toBe(true);
  });
});
