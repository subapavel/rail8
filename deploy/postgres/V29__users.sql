-- The users of the admin API, their roles and their sessions, the limit of login attempts, the marker of the bridge from the
-- shared token, and the audit's columns that name who acted.
--
-- A user is a person or a technical account; names never change and users are never deleted, only blocked. A person's password
-- is an Argon2id PHC string; a technical account has none. The generation is raised by a block, a password change or reset, and
-- the end of the user's sessions: a credential is made only while it is unchanged, checked under the user's row.
create table users (
    id bigint generated always as identity primary key,
    name text not null unique check (name ~ '^[a-z][a-z0-9._-]{1,63}$'),
    kind text not null check (kind in ('person', 'technical')),
    source text not null check (source = 'rail8'),
    subject text,
    password text,
    first_password boolean not null default false,
    password_set_at timestamptz,
    generation bigint not null default 0,
    created_at timestamptz not null,
    created_by text not null,
    blocked_at timestamptz,
    check (kind = 'person' or password is null),
    unique (source, subject)
);

-- The roles a user holds, for the whole installation, with who granted each and how they proved who they were.
create table role_assignments (
    user_id bigint not null references users (id),
    role text not null check (role in ('administrator', 'developer', 'operator', 'message-bodies', 'secret-administrator')),
    scope text not null check (scope = 'installation'),
    granted_at timestamptz not null,
    granted_by text not null,
    granted_via text not null check (granted_via in ('session', 'emergency-key', 'shared-token')),
    primary key (user_id, role, scope)
);

-- A session of a logged-in user: its id names it, its fingerprint (SHA-256 of the whole credential) proves it. A session ends by
-- deleting its row; one that expired keeps it until retention.
create table sessions (
    id text primary key check (id ~ '^[0-9a-f]{16}$'),
    user_id bigint not null references users (id),
    fingerprint bytea not null check (octet_length(fingerprint) = 32),
    first_password boolean not null,
    created_at timestamptz not null,
    last_seen_at timestamptz not null,
    expires_at timestamptz not null
);
create index sessions_user on sessions (user_id);

-- The failures of logins by name, as typed and lowercased, and by device; the row ('*', '*') counts the installation's refused-login
-- audit rows of the current hour.
create table login_limits (
    name text not null,
    device text not null default '',
    failures int not null,
    last_failure_at timestamptz not null,
    audited_at timestamptz,
    primary key (name, device)
);

-- One row: open while the shared token and the open loopback API keep their powers.
create table admin_bridge (
    only_row boolean primary key default true check (only_row),
    open boolean not null
);
insert into admin_bridge values (true, true);

-- Who acted: the user's id, how the caller proved who it is, and the id of its session or token. The default of via is for the
-- writers of nodes older than this migration.
alter table audit add column user_id bigint, add column credential text,
    add column via text not null default 'declared'
        check (via in ('declared', 'session', 'token', 'emergency-key', 'shared-token', 'local'));
create index audit_at on audit (at);
create index audit_user_at on audit (user_id, at) where user_id is not null;
create index audit_subject_at on audit (subject, at);
