-- What a statement trigger that writes one delta per (flow, minute) of the arrivals a statement inserted costs the insert.
-- One run inserts 3 000 statements of 300 rows (100 messages, 3 rows each, as the recorder's batches) into an empty table
-- shaped like `messages`. Seven rounds, each a run into the plain table and a run into the table with the trigger, in turn,
-- so that both see the same drift of the machine. Each run is one transaction with no commit in it: the times are those of
-- the inserts alone, without the commits and the rest of the node's intake.
--
-- psql on any database, once: psql -f bench/sql/arrivals-delta-trigger.sql
-- It builds the tables bench_plain, bench_trig and bench_deltas and the function bench_arrivals_in, and drops them at the
-- end. It prints each round's two times, then the medians, the delta rows and the arrivals they sum to (3 000 and 300 000),
-- and what the same rows sent again, every one a conflict, leave in the deltas (the same 3 000 and 300 000).

\set ON_ERROR_STOP on
set jit = off;
drop table if exists bench_plain, bench_trig, bench_deltas cascade;
drop function if exists bench_arrivals_in();
create table bench_plain (id text, step int, attempt int, flow text, started_at timestamptz, pad text, primary key (id, step, attempt));
create table bench_trig (like bench_plain including all);
create table bench_deltas (flow text not null, minute timestamptz not null, n bigint not null);
create function bench_arrivals_in() returns trigger language plpgsql as $$
begin
  insert into bench_deltas select flow, date_trunc('minute', started_at, 'UTC'), count(*) from new_rows where step = 0 and attempt = 1 group by 1, 2;
  return null;
end $$;
create trigger bench_arrivals_in after insert on bench_trig referencing new table as new_rows for each statement execute function bench_arrivals_in();
create temporary table bench_times (round int, plain_ms float8, trigger_ms float8);

create procedure pg_temp.bench_run(target text, out ms float8) language plpgsql as $$
declare started timestamptz := clock_timestamp();
begin
  for b in 0..2999 loop
    execute format('insert into %I select md5((%s*100+i)::text), s, 1, ''demo/greet'', now(), repeat(''x'', 200)'
                   ' from generate_series(0,99) i, generate_series(0,2) s on conflict do nothing', target, b);
  end loop;
  ms := extract(epoch from clock_timestamp() - started) * 1000;
end $$;

do $$ declare plain float8; trig float8; begin
  for r in 1..7 loop
    truncate bench_plain, bench_trig, bench_deltas;
    call pg_temp.bench_run('bench_plain', plain);
    call pg_temp.bench_run('bench_trig', trig);
    insert into bench_times values (r, plain, trig);
  end loop;
end $$;

select round, round(plain_ms) as plain_ms, round(trigger_ms) as trigger_ms,
       round((trigger_ms / plain_ms - 1)::numeric * 100, 1) as "trigger %" from bench_times order by round;
select round(percentile_cont(0.5) within group (order by plain_ms)) as plain_median_ms,
       round(percentile_cont(0.5) within group (order by trigger_ms)) as trigger_median_ms,
       round((percentile_cont(0.5) within group (order by trigger_ms) / percentile_cont(0.5) within group (order by plain_ms) - 1)::numeric * 100, 1) as "median %"
  from bench_times;
select count(*) as delta_rows, sum(n) as arrivals from bench_deltas;

do $$ declare ms float8; begin call pg_temp.bench_run('bench_trig', ms); raise notice 'the same rows sent again: % ms', round(ms); end $$;
select count(*) as delta_rows, sum(n) as arrivals from bench_deltas;

drop table bench_plain, bench_trig, bench_deltas;
drop function bench_arrivals_in();
