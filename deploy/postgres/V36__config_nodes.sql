-- What each node holds of the configuration of an environment, written by the node after each read of the store, only
-- while its row carries the epoch it wrote: the generation and stamp it read, its epoch then, when its last read
-- succeeded, and the objects it left out (at most 100 named) with their count. Nothing decides by these columns but
-- what the admin API shows of which nodes hold a change.
alter table nodes add column config_generation bigint, add column config_stamp bigint, add column config_epoch bigint,
    add column config_read_at timestamptz,
    add column config_refused text[],
    add column config_refused_count int;

-- The connection administrator and the partner administrator join the roles a user may hold.
alter table role_assignments drop constraint role_assignments_role_check;
alter table role_assignments add constraint role_assignments_role_check check (role in ('administrator', 'developer', 'operator',
    'message-bodies', 'secret-administrator', 'connection-administrator', 'partner-administrator'));
