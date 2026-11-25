-- The secret store: secrets sealed under the store's key, which is never in Postgres. secret_store is one row whose generation
-- every change raises and whose stamp every change writes anew, so a node sees that something changed, even after the database
-- went back to an earlier state. A row of secrets is sealed with AES-256-GCM: key_id names the key, nonce is the seal's random
-- 96 bits, sealed is the ciphertext with its tag. secret_uses names every direct user of a secret, written by the store's one method only.
create table secret_store (
    one        boolean primary key default true check (one),
    generation bigint  not null,
    stamp      bigint  not null
);

create table secrets (
    scope      text        not null check (scope = 'installation'),
    name       text        not null check (name ~ '^[a-z0-9][a-z0-9._-]{0,127}$'),
    kind       text        not null check (kind in ('value', 'credentials', 'key-pair')),
    format     smallint    not null check (format = 1),
    key_id     bytea       not null check (length(key_id) = 8),
    nonce      bytea       not null check (length(nonce) = 12),
    sealed     bytea       not null,
    created_at timestamptz not null,
    changed_at timestamptz not null,
    changed_by text        not null,
    primary key (scope, name)
);

create table secret_uses (
    scope     text   not null,
    name      text   not null,
    user_kind text   not null check (user_kind in ('flow', 'drain', 'connection', 'credential', 'platform')),
    user_id   text   not null,
    kinds     text[] not null,
    primary key (scope, name, user_kind, user_id)
);

-- A node writes, when it joins the store, the ids of the keys it holds, the names (never the values) of its RAIL8_SECRET_*
-- variables, and the epoch of that join; a write counts a live node whose secret_epoch is not its epoch as holding no key.
alter table nodes add column secret_key bytea, add column secret_key_previous bytea,
    add column secret_variables text[], add column secret_epoch bigint;
-- Whether a record's flow rows of secret_uses are written: false for every record written before the store.
alter table deployments add column secret_uses_known boolean not null default false;

insert into secret_store values (true, 0, floor(random() * 9.2e18)::bigint);
insert into secret_uses values ('installation', 'rail8.git', 'platform', 'git', '{credentials}');
