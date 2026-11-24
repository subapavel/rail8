import { describe, expect, test } from 'vitest';
import type { Change } from './api';
import { ADDRESS_REFUSALS, chosenPaths, cloneSourceOf, discardBodyOf, MAX_ADDRESS, MAX_CHOSEN } from './rules';

describe('the address rule of Get a package', () => {
  test.each([
    ['https://github.com/acme/orders.git', 'orders'],
    ['https://github.com/acme/orders', 'orders'],
    ['https://github.com/acme/orders/', 'orders'],
    ['git@github.com:acme/orders.git', 'orders'],
    ['ssh://git@github.com/acme/orders.git', 'orders'],
    ['https://gitlab.com/acme/team/sub/orders.git', 'orders'],
    ['git@gitlab.com:acme/team/sub/orders.git', 'orders'],
    ['ssh://git@bitbucket.example.com:7999/team/orders.git', 'orders'],
    ['https://bitbucket.example.com:8443/scm/team/orders.git', 'orders'],
    ['git@ssh.dev.azure.com:v3/acme/Project/orders', 'orders'],
    ['https://dev.azure.com/acme/My%20Project/_git/orders', 'orders'],
    ['ssh://jiri@gerrit.example.com:29418/orders', 'orders'],
    ['https://git.sr.ht/~jiri/orders', 'orders'],
    ['ssh://git@git.sr.ht/~jiri/orders', 'orders'],
    ['https://git-codecommit.eu-west-1.amazonaws.com/v1/repos/orders', 'orders'],
    ['ssh://git-codecommit.eu-west-1.amazonaws.com/v1/repos/orders', 'orders'],
    ['ssh://g/team/orders.git', 'orders'],
    ['gitserver:orders.git', 'orders'],
    ['my_host:/srv/git/orders.git', 'orders'],
    ['ssh://10.0.0.7/srv/orders.git', 'orders'],
    ['https://github.com/acme/orders.git.git', 'orders.git'],
    ['https://github.com/acme/order%73', 'orders'],
    ['https://github.com/acme/_orders-v2.1', '_orders-v2.1'],
  ])('%s clones into %s', (address, repository) => {
    expect(cloneSourceOf(address)).toEqual({ address, repository });
  });

  test('white space at both ends, and a leading git clone, are dropped; the rest is given to git as it stands', () => {
    expect(cloneSourceOf('  git clone   https://github.com/acme/orders.git \n')).toEqual({ address: 'https://github.com/acme/orders.git', repository: 'orders' });
    expect(cloneSourceOf('\tgit@github.com:acme/Orders.git ')).toEqual({ address: 'git@github.com:acme/Orders.git', repository: 'Orders' });
  });

  test.each([
    ['', ADDRESS_REFUSALS.empty],
    ['   ', ADDRESS_REFUSALS.empty],
    ['https://github.com/acme/my orders.git', ADDRESS_REFUSALS.space],
    ['git clone', ADDRESS_REFUSALS.space],
    ['https://github.com/acme/orders.git --upload-pack=touch', ADDRESS_REFUSALS.space],
    [`https://github.com/${'a'.repeat(MAX_ADDRESS)}`, ADDRESS_REFUSALS.long],
    ['https://github.com/acme/objednávky', ADDRESS_REFUSALS.character],
    ['-oProxyCommand=touch:o', ADDRESS_REFUSALS.option],
    ['--upload-pack=touch', ADDRESS_REFUSALS.option],
    ['-x@host:o.git', ADDRESS_REFUSALS.option],
    ['ext::ssh', ADDRESS_REFUSALS.transport],
    ['fd::17', ADDRESS_REFUSALS.transport],
    ['https::github.com/acme/orders', ADDRESS_REFUSALS.transport],
    ['ssh://[::1]/orders.git', ADDRESS_REFUSALS.transport],
    ['file:///srv/orders.git', ADDRESS_REFUSALS.scheme],
    ['http://github.com/acme/orders.git', ADDRESS_REFUSALS.scheme],
    ['git://github.com/acme/orders.git', ADDRESS_REFUSALS.scheme],
    ['git+ssh://github.com/acme/orders.git', ADDRESS_REFUSALS.scheme],
    ['ftp://github.com/acme/orders.git', ADDRESS_REFUSALS.scheme],
    ['ftps://github.com/acme/orders.git', ADDRESS_REFUSALS.scheme],
    ['HTTPS://github.com/acme/orders.git', ADDRESS_REFUSALS.scheme],
    ['Ssh://github.com/acme/orders.git', ADDRESS_REFUSALS.scheme],
    ['/srv/git/orders.git', ADDRESS_REFUSALS.local],
    ['../orders', ADDRESS_REFUSALS.local],
    ['orders', ADDRESS_REFUSALS.local],
    ['./a:b', ADDRESS_REFUSALS.local],
    ['https://jiri@github.com/acme/orders.git', ADDRESS_REFUSALS.httpsUser],
    ['https://jiri:ghp_secret@github.com/acme/orders.git', ADDRESS_REFUSALS.httpsUser],
    ['https://ghp_secret@github.com/acme/orders.git', ADDRESS_REFUSALS.httpsUser],
    ['ssh://jiri:secret@github.com/acme/orders.git', ADDRESS_REFUSALS.password],
    ['ssh://-oProxyCommand=touch@github.com/acme/orders.git', ADDRESS_REFUSALS.user],
    ['.jiri@github.com:acme/orders.git', ADDRESS_REFUSALS.user],
    ['ssh://-oProxyCommand=touch/o.git', ADDRESS_REFUSALS.host],
    ['ssh://-host/o.git', ADDRESS_REFUSALS.host],
    ['https://[2001:db8:0:0:1:0:0:1]/orders.git', ADDRESS_REFUSALS.host],
    ['https://github.com%2fevil/orders.git', ADDRESS_REFUSALS.host],
    ['https://fe80%25eth0/orders.git', ADDRESS_REFUSALS.host],
    [`https://${'a'.repeat(64)}.com/orders.git`, ADDRESS_REFUSALS.host],
    [`https://${Array(64).fill('abc').join('.')}/orders.git`, ADDRESS_REFUSALS.host],
    ['https://github..com/orders.git', ADDRESS_REFUSALS.host],
    ['https:///orders.git', ADDRESS_REFUSALS.host],
    ['C:\\work\\orders', ADDRESS_REFUSALS.driveLetter],
    ['c:/work/orders', ADDRESS_REFUSALS.driveLetter],
    ['g:orders.git', ADDRESS_REFUSALS.driveLetter],
    ['ssh://host:-1/o.git', ADDRESS_REFUSALS.port],
    ['ssh://host:0/o.git', ADDRESS_REFUSALS.port],
    ['ssh://host:65536/o.git', ADDRESS_REFUSALS.port],
    ['https://host:/o.git', ADDRESS_REFUSALS.port],
    ['https://host:22a/o.git', ADDRESS_REFUSALS.port],
    ['https://github.com', ADDRESS_REFUSALS.noRepository],
    ['https://github.com/', ADDRESS_REFUSALS.noRepository],
    ['host:', ADDRESS_REFUSALS.noRepository],
    ['host:/', ADDRESS_REFUSALS.noRepository],
    ['https://github.com/acme/.git', ADDRESS_REFUSALS.noRepository],
    ['host:-o.git', ADDRESS_REFUSALS.path],
    ['ssh://host/-o.git', ADDRESS_REFUSALS.path],
    ['ssh://host/%2Do.git', ADDRESS_REFUSALS.path],
    ['https://github.com/acme/../orders.git', ADDRESS_REFUSALS.path],
    ['https://github.com/acme/%2e%2e/orders.git', ADDRESS_REFUSALS.path],
    ['https://github.com/acme//orders.git', ADDRESS_REFUSALS.path],
    ['https://github.com/acme/orders.git//', ADDRESS_REFUSALS.path],
    ['https://github.com/acme/orders?x=1', ADDRESS_REFUSALS.path],
    ['https://github.com/acme/orders#main', ADDRESS_REFUSALS.path],
    ['https://github.com/acme/a@b', ADDRESS_REFUSALS.path],
    ['https://github.com/acme/a:b', ADDRESS_REFUSALS.path],
    ['https://github.com/acme/a%2Fb', ADDRESS_REFUSALS.path],
    ['https://github.com/acme/a%5Cb', ADDRESS_REFUSALS.path],
    ['https://github.com/acme/a%00b', ADDRESS_REFUSALS.path],
    ['https://github.com/acme/a%0Ab', ADDRESS_REFUSALS.path],
    ['https://github.com/acme/a%7Fb', ADDRESS_REFUSALS.path],
    ['https://github.com/acme/a%C3%A1', ADDRESS_REFUSALS.path],
    ['https://github.com/acme/a%2', ADDRESS_REFUSALS.path],
    ['host:acme/My%20orders', ADDRESS_REFUSALS.path],
    ['host:acme/a\\b', ADDRESS_REFUSALS.path],
    ['https://github.com/acme/my%20orders', ADDRESS_REFUSALS.folderCharacter],
    ['https://github.com/acme/orders~2', ADDRESS_REFUSALS.folderCharacter],
    ['https://github.com/acme/c%2B%2B', ADDRESS_REFUSALS.folderCharacter],
    ['https://github.com/acme/.orders', ADDRESS_REFUSALS.name],
    [`https://github.com/acme/${'o'.repeat(101)}`, ADDRESS_REFUSALS.name],
    ['https://github.com/acme/orders.', ADDRESS_REFUSALS.trailingDot],
    ['https://github.com/acme/orders..git', ADDRESS_REFUSALS.trailingDot],
    ['https://github.com/acme/con.git', ADDRESS_REFUSALS.device],
    ['git@github.com:acme/NUL', ADDRESS_REFUSALS.device],
    ['https://github.com/acme/com1.txt', ADDRESS_REFUSALS.device],
    ['https://github.com/acme/Lpt9.orders.git', ADDRESS_REFUSALS.device],
  ])('%j is refused: %s', (address, error) => {
    expect(cloneSourceOf(address)).toEqual({ status: 400, error });
  });

  test('a device name in another position, or with more letters, is a name like any other', () => {
    expect(cloneSourceOf('https://github.com/con/orders')).toMatchObject({ repository: 'orders' });
    expect(cloneSourceOf('https://github.com/acme/console')).toMatchObject({ repository: 'console' });
    expect(cloneSourceOf('https://github.com/acme/com10')).toMatchObject({ repository: 'com10' });
  });

  test('anything but text is refused', () => {
    expect(cloneSourceOf(undefined)).toEqual({ status: 400, error: ADDRESS_REFUSALS.notText });
    expect(cloneSourceOf(['https://github.com/acme/orders'])).toEqual({ status: 400, error: ADDRESS_REFUSALS.notText });
  });

  test('no refusal repeats the address or any part of it', () => {
    for (const error of Object.values(ADDRESS_REFUSALS)) expect(error).not.toMatch(/acme|github|secret|orders/);
  });
});

describe('the body of a discard', () => {
  const head = 'a'.repeat(40);
  const one = { path: 'orders/a.txt', tag: 't1', index: 'clean' };

  test('a well-formed body is taken as it stands, a null head and a null tag or index too', () => {
    const body = { head, paths: [one, { path: 'orders/b/c.txt', tag: null, index: 'staged' }, { path: 'orders/d.txt', tag: 't2', index: null }] };
    expect(discardBodyOf('orders', body)).toEqual(body);
    expect(discardBodyOf('orders', { head: null, paths: [one] })).toEqual({ head: null, paths: [one] });
  });

  test.each([
    ['no object', ['x'], 400, 'the request is no JSON object'],
    ['a short head', { head: 'abc', paths: [one] }, 400, 'head is neither null nor a commit of 40 hex digits'],
    ['no list', { head, paths: one }, 400, 'paths is no list'],
    ['too many paths', { head, paths: Array.from({ length: MAX_CHOSEN + 1 }, (_, i) => ({ path: `orders/${i}`, tag: null, index: null })) }, 400, `a discard takes at most ${MAX_CHOSEN} paths`],
    ['an index of another value', { head, paths: [{ ...one, index: 'differs' }] }, 400, 'each path is {path, tag, index}, its tag a text or null, its index clean, staged or null'],
    ['a tag of a number', { head, paths: [{ ...one, tag: 7 }] }, 400, 'each path is {path, tag, index}, its tag a text or null, its index clean, staged or null'],
    ['a path twice', { head, paths: [one, { ...one, tag: null }] }, 400, 'orders/a.txt is chosen twice'],
    ['a .git segment', { head, paths: [{ ...one, path: 'orders/.git/config' }] }, 400, 'a path with a .git segment names git’s own files, never a file of the package'],
    ['a path of another package', { head, paths: [{ ...one, path: 'billing/a.txt' }] }, 404, 'billing/a.txt lies outside the package orders'],
    ['no path', { head, paths: [] }, 422, 'nothing to discard: no path is chosen'],
  ])('%s is refused', (_, body, status, error) => {
    expect(discardBodyOf('orders', body)).toEqual({ status, error });
  });
});

describe('the paths a discard of chosen changes names', () => {
  const change = (path: string, more: Partial<Change> = {}): Change => ({ path, kind: 'file', status: 'modified', index: 'clean', tag: `tag of ${path}`, ...more });

  test('each picked change, in the order picked, with its tag and index; a row whose index differs is named clean', () => {
    const a = change('orders/a.txt');
    const b = change('orders/b.txt', { index: 'staged' });
    const c = change('orders/c.txt', { index: 'differs' });
    expect(chosenPaths([c, a, b], [a, b, c])).toEqual([
      { path: 'orders/c.txt', tag: 'tag of orders/c.txt', index: 'clean' },
      { path: 'orders/a.txt', tag: 'tag of orders/a.txt', index: 'clean' },
      { path: 'orders/b.txt', tag: 'tag of orders/b.txt', index: 'staged' },
    ]);
  });

  test('a rename brings the path it was renamed from right after it: with that path’s own row, else as not on disk', () => {
    const renamed = change('orders/new.txt', { status: 'renamed', renamedFrom: 'orders/old.txt', index: 'staged' });
    expect(chosenPaths([renamed], [renamed])).toEqual([
      { path: 'orders/new.txt', tag: 'tag of orders/new.txt', index: 'staged' },
      { path: 'orders/old.txt', tag: null, index: null },
    ]);
    const old = change('orders/old.txt', { status: 'added' });
    expect(chosenPaths([renamed, old], [renamed, old])).toEqual([
      { path: 'orders/new.txt', tag: 'tag of orders/new.txt', index: 'staged' },
      { path: 'orders/old.txt', tag: 'tag of orders/old.txt', index: 'clean' },
    ]);
  });
});
