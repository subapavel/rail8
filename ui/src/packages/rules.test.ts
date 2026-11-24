import { describe, expect, test } from 'vitest';
import { ADDRESS_REFUSALS, cloneSourceOf, MAX_ADDRESS } from './rules';

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
