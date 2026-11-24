-- The named tokens of the admin API, and the installation's ceiling of their life.
--
-- A token belongs to one user: a person issues their own, an administrator a technical account's. Its id names it, its
-- fingerprint (SHA-256 of the whole credential) proves it; the credential itself is never stored. A name is unique among the
-- user's unrevoked tokens. Every token expires, a year after its issue at the latest; a revoked one keeps its row until
-- retention.
create table tokens (
    id text primary key check (id ~ '^[0-9a-f]{16}$'),
    user_id bigint not null references users (id),
    name text not null check (name ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
    fingerprint bytea not null check (octet_length(fingerprint) = 32),
    created_at timestamptz not null,
    created_by text not null,
    expires_at timestamptz not null check (expires_at > created_at and expires_at <= created_at + interval '8760 hours'),
    last_used_at timestamptz,
    revoked_at timestamptz,
    revoked_by text
);
create unique index tokens_live_name on tokens (user_id, name) where revoked_at is null;
create index tokens_expiring on tokens (expires_at) where revoked_at is null;

-- One row: the longest a token lives in this installation, in days. A token older than it is expired, whatever its own expiry.
create table token_ceiling (
    only_row boolean primary key default true check (only_row),
    max_days int not null check (max_days between 1 and 365)
);
insert into token_ceiling values (true, 365);
