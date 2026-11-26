-- The configuration of an environment: what differs between environments and changes without a deploy, each object with
-- one draft, one version in force and a history. config_store is one row: its generation is raised, with a new random
-- stamp, by every change a node must read. Every body in force, every activation of the history and every consent is bound
-- under the secret store's key (key_id names it, tag is the HMAC-SHA256); a key id without a tag marks a binding that failed.
create table config_store (
    one           boolean primary key default true check (one),
    generation    bigint  not null check (generation >= 0),
    stamp         bigint  not null,
    mocks_allowed boolean not null default false
);

-- One row per object: its version in force, its draft with the consent to it, and a connection's state.
create table config_objects (
    kind        text   not null check (kind in ('connection', 'system')),
    key         text   not null,
    incarnation bigint not null,
    revision    bigint not null check (revision >= 0),
    -- the version in force
    version      int    not null default 0 check (version >= 0),  -- 0 before the first activation
    activation   bigint,                                          -- null before the first activation
    format       smallint,
    body         jsonb,                                           -- null before the first activation and after a removal
    key_id       bytea  check (length(key_id) = 8),               -- the binding: null both, never bound; a key id alone, it failed
    tag          bytea  check (length(tag) = 32),
    activated_at timestamptz, activated_by text,
    -- the draft; null when there is none
    draft_id     bigint,                                          -- a new random id when a draft starts
    draft_format smallint, draft jsonb, draft_at timestamptz, draft_by text,
    -- the secret administrator's consent to what the draft sends
    consent        bytea check (length(consent) = 32),            -- the digest of the draft's sends
    consent_key_id bytea check (length(consent_key_id) = 8),
    consent_tag    bytea check (length(consent_tag) = 32),
    consent_at     timestamptz, consent_by text,
    -- a connection's state
    run          int not null default 0,
    stopped_at   timestamptz, stopped_by text,                    -- null: running
    primary key (kind, key),
    check (kind = 'connection' and key ~ '^(?!(rail8|subscribers|by-agreement)/)[a-z][a-z0-9-]{0,63}(/[a-z][a-z0-9-]{0,63}){1,2}$'
        or kind = 'system' and key ~ '^(?!(rail8|subscribers|by-agreement)(/|$))[a-z][a-z0-9-]{0,63}(/[a-z][a-z0-9-]{0,63})?$'),
    check ((body is null) = (format is null)),
    check ((version = 0) = (activation is null)),
    check (tag is null or key_id is not null),
    check ((draft is null) = (draft_format is null) and (draft is null) = (draft_id is null)),
    check (consent is null or draft is not null),
    check (consent_tag is null or consent_key_id is not null),
    check (kind = 'connection' or stopped_at is null)
);

-- Each object's history. The row of an activation holds the body it put in force: these rows are the versions kept.
create table config_history (
    id         bigint generated always as identity primary key,
    kind       text not null,
    key        text not null,
    at         timestamptz not null,
    actor      text not null, user_id bigint, via text not null, credential text,   -- as the audit names them
    change     text not null check (change in ('save', 'import', 'discard', 'activate', 'stop', 'start')),
    revision   bigint not null,                    -- the object's revision after the change
    generation bigint,                             -- an activation, a stop, a start: the generation it made
    version    int,                                -- an activation: the version it made
    format     smallint, body jsonb,               -- an activation: the body it put in force
    incarnation bigint, activation bigint,         -- an activation: the row's incarnation and the activation's id
    key_id     bytea check (length(key_id) = 8),   -- an activation: its binding, as the version in force has it
    tag        bytea check (length(tag) = 32),
    diff       jsonb not null,                     -- [{field, from, to}]; a secret field by its reference only
    note       text check (length(note) <= 200)    -- an activation or an import: its author's note, such as a commit
);
create index config_history_object on config_history (kind, key, id);
-- An id is drawn again when another row's incarnation or activation already holds it.
create index config_history_activation on config_history (activation) where activation is not null;
create index config_history_incarnation on config_history (incarnation) where incarnation is not null;
-- The retention job's scan by age.
create index config_history_at on config_history (at);

-- What each deployment's record names, written at a deploy by the step of systems and flows by name; empty until then.
-- A system's definition is its file as the deploy read it: partner, name, and connections by name, each with its adapter.
create table deployment_systems (
    deployment text  not null,                     -- the package
    system     text  not null primary key,         -- a system key is defined once in an installation
    definition jsonb not null
);
create index deployment_systems_deployment on deployment_systems (deployment);

-- Every connection a deployed flow can reach: its direction, whether it goes through an address field, a sender's polled place.
create table deployment_connection_uses (
    deployment text    not null,                   -- the package
    flow       text    not null,                   -- the flow's qualified id, <package>/<flow>
    step       text    not null,
    connection text    not null,
    adapter    text    not null,
    direction  text    not null check (direction in ('in', 'out')),
    address    boolean not null,
    polled     text,
    primary key (deployment, flow, step, connection, direction)
);
create index deployment_connection_uses_connection on deployment_connection_uses (connection);

-- The generation of the configuration a deploy checked against: null for every record written before it.
alter table deployments add column config_generation bigint;

insert into config_store values (true, 0, floor(random() * 9.2e18)::bigint, false);
