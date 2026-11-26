#!/usr/bin/env python3
"""Times the reads of the Messages screen through the admin API, warm and cold.

Usage: RAIL8_TOKEN=<a token with the operator role> python3 bench/messages_screen.py
           [--admin http://localhost:8081] [--from <ISO instant>] [--to <ISO instant>] [--flow demo/greet]
           [--key name:p-500000] [--step nameKey] [--repeat 5] [--cold-command '<shell>'] [--only first,counts]
           [--timeout 120] [--json out.json]

The time range is --from to --to, the last hour up to now when they are not given. Each read is a request the screen
or a caller makes:

  first        the list's first page, every flow           page2, page10, page100: the page after 1, 9 and 99 others
  counts       the strip's counts of the range             counts.flow: of --flow
  flow         the list narrowed to --flow                 status.<s>: narrowed to each status
  key          the list narrowed to the key --key          step: narrowed to a step named --step; step.flow: and --flow
  key.any      the search by key at any time (?key=)       step.any: the search by --flow and --step at any time
  detail       a message's timeline, the first page's top row

Warm: each read is made once untimed, then --repeat times; the median and the slowest are kept. Cold: with
--cold-command, that command runs before each read (for example a restart of Postgres and a drop of the page cache),
the script waits until the admin API reads the list of flows again, then times the read once. A read that answers
other than 200 is recorded with its status. The figures go to stdout as a table and, with --json, to a file: per read
its URL, warm median and slowest in ms, cold ms, the number of items or the counts it answered.

Standard library only. Exits 0 when every read answered 200, 1 otherwise.
"""
import argparse
import datetime
import json
import os
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


class Admin:
    def __init__(self, base, token, timeout):
        self.base, self.token, self.timeout = base.rstrip("/"), token, timeout

    def get(self, path):
        """(status, answer as JSON or None, seconds) of GET path."""
        req = urllib.request.Request(self.base + path, headers={"Authorization": f"Bearer {self.token}", "Accept": "application/json"})
        t0 = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                body = resp.read()
                return resp.status, json.loads(body), time.perf_counter() - t0
        except urllib.error.HTTPError as e:
            e.read()
            return e.code, None, time.perf_counter() - t0


def iso(t):
    return t.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def query(**params):
    return urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})


def reads(args):
    """Each read by name: a function of the admin API answering the path to time (it may read pages to get there)."""
    rng = {"from": args.from_, "to": args.to}

    def at(**params):
        return lambda admin: "/admin/messages?" + query(**rng, **params)

    def page(n):
        def path(admin):
            after = None
            for _ in range(n - 1):
                status, answer, _ = admin.get("/admin/messages?" + query(**rng, after=after))
                if status != 200 or not answer.get("next"):
                    return None
                after = answer["next"]
            return "/admin/messages?" + query(**rng, after=after)
        return path

    def detail(admin):
        status, answer, _ = admin.get("/admin/messages?" + query(**rng, limit=1))
        if status != 200 or not answer["items"]:
            return None
        return "/admin/messages/" + urllib.parse.quote(answer["items"][0]["id"], safe="")

    return {
        "first": at(),
        "page2": page(2),
        "page10": page(10),
        "page100": page(100),
        "counts": lambda admin: "/admin/message-counts?" + query(**rng),
        "counts.flow": lambda admin: "/admin/message-counts?" + query(**rng, flow=args.flow),
        "flow": at(flow=args.flow),
        "status.completed": at(status="completed"),
        "status.failed": at(status="failed"),
        "status.running": at(status="running"),
        "status.cancelled": at(status="cancelled"),
        "key": at(key=args.key),
        "step": at(step=args.step),
        "step.flow": at(flow=args.flow, step=args.step),
        "key.any": lambda admin: "/admin/messages?" + query(key=args.key),
        "step.any": lambda admin: "/admin/messages?" + query(flow=args.flow, step=args.step),
        "detail": detail,
    }


def answered(answer):
    """What a read answered, in short: its counts, or how many items and whether another page exists."""
    if answer is None:
        return None
    if "items" in answer:
        return {"items": len(answer["items"]), "next": answer.get("next") is not None}
    if "steps" in answer:
        return {"steps": len(answer["steps"])}
    return answer


def wait_for_admin(admin, deadline_s=180):
    end = time.time() + deadline_s
    while time.time() < end:
        try:
            status, _, _ = admin.get("/admin/flows")
            if status == 200:
                return
        except OSError:
            pass
        time.sleep(1)
    raise SystemExit("the admin API did not read the list of flows again within %d s" % deadline_s)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--admin", default="http://localhost:8081")
    ap.add_argument("--from", dest="from_")
    ap.add_argument("--to")
    ap.add_argument("--flow", default="demo/greet")
    ap.add_argument("--key", default="name:p-500000")
    ap.add_argument("--step", default="nameKey")
    ap.add_argument("--repeat", type=int, default=5)
    ap.add_argument("--cold-command")
    ap.add_argument("--only", help="comma-separated names of the reads to make, every read when not given")
    ap.add_argument("--timeout", type=float, default=120.0)
    ap.add_argument("--json")
    args = ap.parse_args()
    token = os.environ.get("RAIL8_TOKEN")
    if not token:
        raise SystemExit("RAIL8_TOKEN is not set: a token of an account with the operator role")
    now = datetime.datetime.now(datetime.timezone.utc)
    args.to = args.to or iso(now)
    args.from_ = args.from_ or iso(datetime.datetime.fromisoformat(args.to.replace("Z", "+00:00")) - datetime.timedelta(hours=1))
    admin = Admin(args.admin, token, args.timeout)

    chosen = reads(args)
    if args.only:
        names = args.only.split(",")
        unknown = [n for n in names if n not in chosen]
        if unknown:
            raise SystemExit("no such read: " + ", ".join(unknown))
        chosen = {n: chosen[n] for n in names}

    results, ok = {}, True
    for name, path_of in chosen.items():
        path = path_of(admin)
        result = {"path": path}
        if path is None:
            result["error"] = "not reached: an earlier page was the last, or the list is empty"
            ok = False
            results[name] = result
            continue
        status, answer, _ = admin.get(path)   # warm-up
        times = []
        for _ in range(args.repeat):
            status, answer, seconds = admin.get(path)
            times.append(seconds * 1000)
        result.update(status=status, warmMedianMs=round(statistics.median(times), 1), warmMaxMs=round(max(times), 1), answered=answered(answer))
        ok = ok and status == 200
        results[name] = result
        print(f"{name}: warm {result['warmMedianMs']} ms (slowest {result['warmMaxMs']}), {status} {json.dumps(result['answered'])}", file=sys.stderr, flush=True)

    if args.cold_command:
        for name, result in results.items():
            if result.get("path") is None:
                continue
            subprocess.run(args.cold_command, shell=True, check=True)
            wait_for_admin(admin)
            status, _, seconds = admin.get(result["path"])
            result["coldMs"] = round(seconds * 1000, 1)
            result["coldStatus"] = status
            ok = ok and status == 200
            print(f"{name}: cold {result['coldMs']} ms, {status}", file=sys.stderr, flush=True)

    print(f"range {args.from_} to {args.to}")
    print("| read | warm median ms | warm slowest ms | cold ms | answered |")
    print("| --- | --- | --- | --- | --- |")
    for name, r in results.items():
        print(f"| {name} | {r.get('warmMedianMs', '')} | {r.get('warmMaxMs', '')} | {r.get('coldMs', '')} | {json.dumps(r.get('answered', r.get('error')))} |")
    if args.json:
        with open(args.json, "w") as f:
            json.dump({"from": args.from_, "to": args.to, "reads": results}, f, indent=2)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
