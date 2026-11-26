-- What the counts by status cost when they read the messages of small sets instead of every arrival of the range: the
-- newest rows of a work backlog, each joined to its arrival and checked against the DLQ by its id, and sets of 2 001
-- candidates whose status is decided by the list's own rule.
--
-- Run on the database the Messages-screen bench loaded: a million arrivals in the hour, every one completed, the node's
-- `work` and `dlq` empty. psql as the node's role, one step per call, the range in -v from and -v to:
--   psql -v step=install -v from='2026-11-26 01:45' -v to='2026-11-26 02:45' -f bench/sql/messages-status-sets.sql
--
-- Steps:
--   install      builds the stand-ins and two indexes, below. The range must hold more than 200 000 arrivals.
--   backlog      the newest 20 001 rows of the backlog: how many are running, how many were examined.
--   backlog-all  the same over the whole backlog, 200 000 rows.
--   candidates   2 001 candidates from the backlog (1 001 of them parked), each status by the list's rule.
--   older        2 001 arrivals older than the backlog, each status by the list's rule: every probe of the rule runs.
--   rewrite      rewrites every row of the stand-in work table, autovacuum off for it, so that its pages are no longer
--                all-visible, as on a busy work table. Run the reads again after it.
--   uninstall    drops the stand-ins and the two indexes.
-- Warm: a read run four times, the median of the last three. Cold: one read after Postgres was restarted and the page cache
-- dropped. One connection, no parallel workers, jit off.
--
-- The stand-ins, in a schema `bench_status` of their own; the node's tables stay untouched:
--   bench_status.work  the newest 200 000 arrivals of the range, each with the start of its arrival, indexed by
--                      (flow, start, id), (start, id) and (id);
--   bench_status.dlq   2 000 of those, keyed by id, indexed by (start, id).
-- The indexes on `messages`, built concurrently, which each candidate is joined and checked through:
--   bench_arrivals_by  (started_at, id) include (flow, step_name) where step = 0 and attempt = 1: the arrival, index-only;
--   bench_failed       (id) where outcome = 'failed': the probe for a failed step, empty here.

\set ON_ERROR_STOP on
\if :{?step}
\else
  \set step none
\endif
select :'step' = 'install' as install, :'step' = 'backlog' as backlog, :'step' = 'backlog-all' as backlog_all,
       :'step' = 'candidates' as candidates, :'step' = 'older' as older, :'step' = 'rewrite' as rewrite,
       :'step' = 'uninstall' as uninstall \gset
\if :install
\elif :backlog
\elif :backlog_all
\elif :candidates
\elif :older
\elif :rewrite
\elif :uninstall
\else
  do $$ begin raise exception 'messages-status-sets.sql: pass -v step=install, backlog, backlog-all, candidates, older, rewrite or uninstall'; end $$;
\endif
set statement_timeout = '600s';
set jit = off;
set max_parallel_workers_per_gather = 0;

\if :install
create schema bench_status;
create table bench_status.work as
  select row_number() over () as seq, id, flow, started_at as arrived_at
    from (select id, flow, started_at from messages
           where step = 0 and attempt = 1 and started_at >= :'from' and started_at < :'to'
           order by started_at desc limit 200000) a;
create index on bench_status.work (flow, arrived_at, id);
create index on bench_status.work (arrived_at, id);
create index on bench_status.work (id);
create table bench_status.dlq as select id, flow, arrived_at from bench_status.work order by arrived_at desc offset 1000 limit 2000;
alter table bench_status.dlq add primary key (id);
create index on bench_status.dlq (arrived_at, id);
vacuum analyze bench_status.work;
vacuum analyze bench_status.dlq;
create index concurrently bench_arrivals_by on messages (started_at, id) include (flow, step_name) where step = 0 and attempt = 1;
create index concurrently bench_failed on messages (id) where outcome = 'failed';
analyze messages;
\endif

\timing on

\if :backlog
select count(*) filter (where not exists (select 1 from bench_status.dlq d where d.id = w.id)) as running, count(*) as examined
  from (select distinct on (arrived_at, id) id, flow, arrived_at from bench_status.work
         where arrived_at >= :'from' and arrived_at < :'to' order by arrived_at desc, id desc limit 20001) w
  join messages m on m.started_at = w.arrived_at and m.id = w.id and m.step = 0 and m.attempt = 1;
\endif

\if :backlog_all
select count(*) filter (where not exists (select 1 from bench_status.dlq d where d.id = w.id)) as running, count(*) as examined
  from (select distinct on (arrived_at, id) id, flow, arrived_at from bench_status.work
         where arrived_at >= :'from' and arrived_at < :'to' order by arrived_at desc, id desc) w
  join messages m on m.started_at = w.arrived_at and m.id = w.id and m.step = 0 and m.attempt = 1;
\endif

\if :candidates
\set source work
\elif :older
\set source older
\endif

\if :{?source}
select s.status, count(*) from (
  select case when exists (select 1 from bench_status.dlq d where d.id = m.id) then 'failed'
              when exists (select 1 from bench_status.work w where w.flow = m.flow and w.id = m.id) then 'running'
              when exists (select 1 from audit c where c.action = 'message.cancel' and c.subject = 'message/' || m.id) then 'cancelled'
              when exists (select 1 from messages f where f.id = m.id and f.outcome = 'failed'
                           and not exists (select 1 from messages g where g.id = m.id and g.attempt > f.attempt)) then 'failed'
              else 'completed' end as status
    from (select distinct on (id) id, arrived_at from (
            select id, arrived_at from (select id, arrived_at from bench_status.work where :'source' = 'work'
                                         order by arrived_at desc, id desc limit 2001) w
            union all
            select id, started_at from (select id, started_at from messages
                                         where :'source' = 'older' and step = 0 and attempt = 1
                                           and started_at >= :'from' and started_at < :'to'
                                         order by started_at, id limit 2001) o) k) k
    join messages m on m.started_at = k.arrived_at and m.id = k.id and m.step = 0 and m.attempt = 1
   where m.started_at >= :'from' and m.started_at < :'to') s group by 1 order by 1;
\endif

\if :rewrite
alter table bench_status.work set (autovacuum_enabled = false);
update bench_status.work set seq = seq + 1;
\endif

\if :uninstall
drop index concurrently if exists bench_arrivals_by;
drop index concurrently if exists bench_failed;
drop schema if exists bench_status cascade;
\endif
