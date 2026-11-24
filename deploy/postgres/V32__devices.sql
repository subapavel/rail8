-- The devices of the login limit: one row per device credential a browser holds after a full login.
--
-- A device belongs to one user and proves nothing: a login that presents a valid one is counted on the device's own row of
-- login_limits, and may take the node's second place to hash. Its id names it, its fingerprint (SHA-256 of the whole
-- credential) proves it; the credential itself is never stored. It is valid for 90 days from its issue, and only while the
-- user's generation is the one it was issued at. A user holds at most 10 rows; a dead one keeps its row until the next issue
-- or retention.
create table devices (
    id text primary key check (id ~ '^[0-9a-f]{16}$'),
    user_id bigint not null references users (id),
    fingerprint bytea not null check (octet_length(fingerprint) = 32),
    generation bigint not null,   -- the user's at issue; another one ends the device
    created_at timestamptz not null,
    expires_at timestamptz not null
);
create index devices_user on devices (user_id);
