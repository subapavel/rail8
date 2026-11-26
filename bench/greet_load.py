#!/usr/bin/env python3
"""A load of distinct people sent to the demo's flow `greet`: N requests over C keep-alive connections.

Usage: python3 bench/greet_load.py [--n 1000000] [--c 64] [--prefix p] [--first 0]
                                    [--host localhost] [--port 8080] [--path /http/greet]
                                    [--resend-wait 0.05] [--max-resends 1000] [--timeout 60] [--json out.json]

Request i of the run (i from --first to --first + n - 1) posts {"name": "<prefix>-<i>", "city": "Brno"}, so every message
carries a name of its own. A 503 is the flow refusing for now: the client waits --resend-wait seconds and sends the same
request again, at most --max-resends times, then counts it refused for good. Any other answer that is not 2xx is refused
for good at once; a connection that breaks is opened again and its request counted as an error, never sent again, since
the node may have taken it. Every 10 s it prints its progress to stderr; at the end one line of figures to stdout:
the messages accepted, the time, accepted per second, the latency of the accepted requests (median, 95 %, 99 %; the
request that was answered 2xx, not its earlier 503s), how many requests were sent again after a 503, and how many were
refused for good or broke. --json writes the same figures to a file. Standard library only.

Exits 0 when every message was accepted, 1 when one was refused for good or broke.
"""
import argparse
import http.client
import json
import sys
import threading
import time


def worker(args, numbers, out, progress):
    """Sends the requests of `numbers` one after the other over one keep-alive connection."""
    times, resent, refused, broken = [], 0, {}, {}
    conn = http.client.HTTPConnection(args.host, args.port, timeout=args.timeout)
    headers = {"Content-Type": "application/json"}
    for i in numbers:
        body = json.dumps({"name": f"{args.prefix}-{i}", "city": "Brno"}).encode()
        tries = 0
        while True:
            t0 = time.perf_counter()
            try:
                conn.request("POST", args.path, body, headers)
                resp = conn.getresponse()
                resp.read()
            except (OSError, http.client.HTTPException) as e:
                broken[type(e).__name__] = broken.get(type(e).__name__, 0) + 1
                conn.close()
                conn = http.client.HTTPConnection(args.host, args.port, timeout=args.timeout)
                break
            if 200 <= resp.status < 300:
                times.append(time.perf_counter() - t0)
                break
            if resp.status == 503 and tries < args.max_resends:
                tries += 1
                resent += 1
                time.sleep(args.resend_wait)
                continue
            refused[resp.status] = refused.get(resp.status, 0) + 1
            break
        progress[0] += 1   # one writer per slot: each worker owns its own list
    conn.close()
    out.append((times, resent, refused, broken))


def pct(sorted_times, p):
    if not sorted_times:
        return float("nan")
    return sorted_times[min(len(sorted_times) - 1, int(p / 100 * len(sorted_times)))]


def merge(maps):
    total = {}
    for m in maps:
        for k, v in m.items():
            total[str(k)] = total.get(str(k), 0) + v
    return total


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--n", type=int, default=1_000_000)
    ap.add_argument("--c", type=int, default=64)
    ap.add_argument("--prefix", default="p")
    ap.add_argument("--first", type=int, default=0)
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--path", default="/http/greet")
    ap.add_argument("--resend-wait", type=float, default=0.05)
    ap.add_argument("--max-resends", type=int, default=1000)
    ap.add_argument("--timeout", type=float, default=60.0)
    ap.add_argument("--json")
    args = ap.parse_args()

    out, progresses, threads = [], [], []
    start = time.perf_counter()
    for w in range(args.c):
        numbers = range(args.first + w, args.first + args.n, args.c)
        progress = [0]
        progresses.append(progress)
        t = threading.Thread(target=worker, args=(args, numbers, out, progress), daemon=True)
        threads.append(t)
        t.start()
    last = 0
    while any(t.is_alive() for t in threads):
        deadline = time.perf_counter() + 10.0
        while time.perf_counter() < deadline and any(t.is_alive() for t in threads):
            time.sleep(0.2)
        done = sum(p[0] for p in progresses)
        elapsed = time.perf_counter() - start
        print(f"{done} of {args.n} sent in {elapsed:.0f} s, {(done - last) / 10.0:.0f}/s lately", file=sys.stderr, flush=True)
        last = done
    elapsed = time.perf_counter() - start

    times = sorted(t for o in out for t in o[0])
    resent = sum(o[1] for o in out)
    refused = merge(o[2] for o in out)
    broken = merge(o[3] for o in out)
    figures = {
        "messages": args.n,
        "connections": args.c,
        "prefix": args.prefix,
        "first": args.first,
        "seconds": round(elapsed, 1),
        "accepted": len(times),
        "acceptedPerSecond": round(len(times) / elapsed) if elapsed > 0 else 0,
        "latencyMs": {str(p): round(pct(times, p) * 1000, 1) for p in (50, 95, 99)} if times else {},
        "resentAfter503": resent,
        "refusedForGood": refused,
        "broken": broken,
    }
    lat = figures["latencyMs"]
    print(f"{len(times)} of {args.n} accepted over {args.c} connections in {elapsed:.1f} s: {figures['acceptedPerSecond']}/s, "
          f"latency median / 95 % / 99 % {lat.get('50')} / {lat.get('95')} / {lat.get('99')} ms, "
          f"{resent} sent again after 503, refused for good {refused or 'none'}, broken {broken or 'none'}")
    if args.json:
        with open(args.json, "w") as f:
            json.dump(figures, f, indent=2)
    sys.exit(0 if len(times) == args.n else 1)


if __name__ == "__main__":
    main()
