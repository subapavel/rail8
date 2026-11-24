/**
 * Who uses the admin API and what they may do: the users and their roles, the actions, the one decision point every admin
 * route asks ({@link dev.rail8.runtime.access.Caller#may}), the login with its limit, the sessions, the emergency key and the
 * bridge from the shared token. {@link dev.rail8.runtime.access.PgAccess} keeps it all in Postgres and caches nothing;
 * {@link dev.rail8.runtime.access.MemoryAccess} keeps it in memory for tests.
 */
package dev.rail8.runtime.access;
