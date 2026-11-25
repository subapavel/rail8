# The gate stack (`deploy/compose`)

A demo stack for GATE checks 1-2 (D-085), not production: the node runs `RAIL8_ENV=dev`. Services: `postgres`, `valkey`
(persistence off), `sftp-keys` (one-shot: the SFTP host key), `sftp`, `netns` (holds the published ports and the network namespace of the node and the echo), `rail8` (the node), `echo` (the demo's partner),
`deploy` (one-shot, profile `deploy`: `rail8 deploy rail8 HEAD --check` reads the state the pool records, then `rail8 deploy rail8 HEAD --expect` it), `clickhouse` under the `history` profile (not wired), and `kafka` (KRaft, the optional event-broker add-on) under the
`kafka` profile, off by default and not wired: a plain `up` does not start it.

```sh
./mvnw -B -ntp -q package -DskipTests -pl :rail8-node,:rail8-cli -am   # the jars the node image and `deploy` use, with the engines they need
cd deploy/compose
export RAIL8_ADMIN_TOKEN=$(../../bin/rail8 key new)    # the node's emergency key, required: compose refuses to start without it
docker compose up -d --build                           # returns once the node is healthy
../../bin/rail8 bootstrap --admin admin --deploy-account deploy --token-file ~/.rail8-stack-token   # prints the administrator's password, once
export RAIL8_TOKEN=$(cat ~/.rail8-stack-token)         # the deploy account's token: it deploys, and reads the deployed packages
docker compose run --rm deploy                         # exits 0 after the demo is deployed
curl -H "Authorization: Bearer $RAIL8_TOKEN" localhost:8081/admin/packages
curl -X POST --data-binary @../../examples/demo/fixtures/sample-invoice.xml localhost:8080/http/in   # flow invoice-sync: the reply is the ACK
curl -X POST --data-binary @../../examples/demo/fixtures/sample-invoice.xml localhost:8080/http/invoice/async   # flow invoice-async: 202 after the durable save
docker compose --profile history up -d clickhouse      # optional
docker compose --profile kafka up -d kafka             # optional: the event-broker add-on
docker compose --profile history --profile kafka down -v   # stop and remove every volume
```

Postgres runs `postgres:18.6` with its volume at `/var/lib/postgresql` (D-129). A `pg-data` volume made by the earlier
`postgres:17.11` cannot be reused (the 18 image refuses to start on it): run `docker compose --profile history --profile kafka down -v` once
before the first `up` after the upgrade; it deletes every volume of this demo stack (database, the node's git cache and packages, SFTP keys and files, ClickHouse).

A stack that was started before the rename to Rail8 (2026-11-15, D-148) needs the same once, with `--remove-orphans` added, before the first
`up`: `docker compose --profile history --profile kafka down -v --remove-orphans`. Its `pg-data` volume holds the role and the database of
the former name and Postgres initialises only an empty volume, so the node cannot log in although `pg_isready` passes
(`docker compose logs postgres` shows the refused login). And the node's service was renamed, so the old node container is an orphan: a plain
`down -v` leaves it, and while it exists it keeps its volumes in use. The old node-data volume is no longer declared in this file and stays
after that: `docker volume ls` shows it under the former name, `docker volume rm` removes it.

- The package: the node reads this checkout as the git repository `rail8` (the repository's root mounted read-only at
  `/repos/rail8`, `RAIL8_GIT_BASE=file:///repos/`) and deploys its folder `examples/demo` (`RAIL8_GIT_PATH`). `deploy` deploys
  the checkout's `HEAD`: a change to the demo is deployed once it is committed, by `docker compose run --rm deploy`. The checkout
  needs its full history and its own `.git` folder: a shallow clone or a `git worktree` of Rail8 cannot be deployed from.
- Admin API and `/health`: `localhost:8081`, plain HTTP under `RAIL8_ADMIN_TLS=off` on the node and on `deploy`, which calls
  `http://rail8:8081` (the node logs a WARNING at its start): the port is published on the host's loopback only. A node other
  hosts reach is given `RAIL8_ADMIN_TLS_CERT` and `RAIL8_ADMIN_TLS_KEY` in place of `off`; the image's health check,
  `/opt/rail8/health.sh`, then asks `/health` over HTTPS. The node's HTTP port, shared by every inbound HTTP endpoint: `localhost:8080`; the demo's http-in at `/http/in`, its async door `http-in-async` at `/http/invoice/async` (answers 202).
- Postgres password: `RAIL8_PG_PASSWORD` (default `rail8`).
- A secret mounted as a file: each of the node's secrets can be given as the path of a file that holds it, the variable's
  name with `_FILE` added (`docs/ARCHITECTURE.md`, part 10). The stack gives the SFTP host key's fingerprint this way:

  ```yaml
  environment:
    RAIL8_SECRET_SFTP_HOST_KEY_FILE: /sftp-fingerprint/host-key.fingerprint   # read once at start, one line end dropped
  volumes:
    - sftp-fingerprint:/sftp-fingerprint:ro
  ```
- SFTP: user `demo`, password `RAIL8_SFTP_PASSWORD` (default `demo`), directory `inbox` (the demo's `sftp-inbox` polls `/inbox` every
  10 s and deletes a file once the flow `sftp-invoice` has saved it). The one-shot `sftp-keys` generates an RSA host key (3072 bits; the node's SFTP client does not offer ed25519) into the
  `sftp-hostkeys` volume on the first start (never replaced) and writes its public fingerprint to `host-key.fingerprint` in the separate volume
  `sftp-fingerprint`, the only one the node mounts (read-only, no host key reaches it); the
  node reads that file as `RAIL8_SECRET_SFTP_HOST_KEY_FILE`, the password reaches it as `RAIL8_SECRET_SFTP_PASSWORD`.
  Nothing secret or generated is in the repo. The fingerprint:

  ```sh
  docker compose exec sftp ssh-keygen -l -f /etc/ssh/ssh_host_rsa_key      # the server's own view; the node reads the same
  ```

  Drop a file into the inbox (it reaches the echo transformed; `docker compose logs echo`):

  ```sh
  docker compose cp ../../examples/demo/fixtures/inbox/invoice-4711.xml sftp:/home/demo/inbox/
  # or over SFTP from the sftp container: docker compose exec sftp sftp demo@localhost (password above), then: put <file> inbox
  ```

## The secret store

The stack starts without the store's key: each node reads its secrets from its `RAIL8_SECRET_*` variables only, and a
write to `/admin/secrets` is 503 `no-key`. To keep secrets in the store, give every node of the installation the same key,
the base64 of 256 random bits (`openssl rand -base64 32`), as `RAIL8_SECRETS_KEY_FILE` (preferred) or `RAIL8_SECRETS_KEY`.
It is never the journal's key. What the store holds is sealed in Postgres under that key, and Postgres never holds the key.

- **A lost key loses every secret.** Back the key up apart from the database and its backups: a backup of the database
  without the key holds nothing that can be read.
- **It protects a database at rest, not access to the server.** Whoever reads a node's memory, its environment or the file
  of its key reads every secret. The store is not a vault.
- **Whoever deploys can reach every secret**: a flow names any secret by its name, and its code can send the value anywhere.
- **The limits**: 1,000 secrets and 16 MiB of sealed values; a plain value of 32 KiB, a user of 256 characters, a password
  of 4,096 characters; a name of 128 characters, `[a-z0-9][a-z0-9._-]*`, never under `rail8.` but `rail8.git`.
- **After a lost key.** A node with rows that no key opens refuses its start. Stop every node, run `delete from secrets;
  update secret_store set generation = generation + 1, stamp = floor(random() * 9.2e18)::bigint;`, start every node with a
  new key, and enter each secret again. The uses stay, and the list shows each name as missing, with its users.
- **Changing the key** takes two rolls of the nodes; `docs/SECRET-STORE.md` says how.
