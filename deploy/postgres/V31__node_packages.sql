-- What each node of the pool runs: one row per package a node lists from git, written by that node alone, about itself. A
-- node replaces its rows whole in one transaction, and only while its row in nodes carries the epoch it writes with and a lease
-- that was not released; the same transaction deletes the rows of every node whose epoch moved on or whose lease was released.
-- GET /admin/pool/packages answers the rows of every node whose lease holds, of its current epoch. Nothing reads these rows to
-- decide anything: what the pool runs is in deployments.
create table node_packages (
    node text not null,
    epoch bigint not null,
    package text not null,
    version text not null,
    repository text not null,
    commit text not null check (commit ~ '^[0-9a-f]{40}$'),
    deployed_at timestamptz not null,
    state text not null check (state in ('RUNNING', 'FAILED')),
    -- why the node's last install of the package's record was refused, one line each; empty when it was not
    behind text[] not null,
    primary key (node, package)
);
