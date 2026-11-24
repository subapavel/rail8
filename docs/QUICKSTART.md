# Quickstart: run Rail8 and its UI on your machine

One node with its Postgres and a partner echo in Docker, and the UI in your browser, all from one terminal. The demo
package (`examples/demo`) is deployed to the node, an invoice is sent in and found in the UI. Commands run from the
repository root.

## Prerequisites

| Tool | Version | Install |
| --- | --- | --- |
| JDK | 25 | any distribution, e.g. Temurin |
| Maven | none | the repository's `./mvnw` wrapper fetches the Maven it needs (3.9.11) and builds everything |
| Node.js | 22.19 or newer | with npm |
| Docker | Compose v2 | Docker Engine on Linux, Docker Desktop on macOS and Windows |
| `curl`, `git` | `git` 2.30 or newer | `git` with your name and email set (`git config --global user.name`, `user.email`): you commit what you deploy |

The scripts are shell scripts. On Windows run everything inside WSL 2, with Docker Desktop's WSL integration on and the
repository cloned inside the WSL file system.

## First run

```sh
git clone https://github.com/subapavel/rail8.git && cd rail8
./mvnw -B -ntp -q package -DskipTests -pl :rail8-node,:rail8-cli -am   # the node's jar, which the image copies, and bin/rail8
(cd ui && npm ci)

mkdir -p ~/rail8-workspace && cp -r examples/demo ~/rail8-workspace/demo   # once: the packages the endpoint forms edit
cp -r mapping/corpus ~/rail8-workspace/mapping-corpus                     # once: the mappings the mapping studio edits
(cd ~/rail8-workspace/demo && git init -q -b main && echo '* -text' > .gitattributes && git add -A && git commit -qm 'The demo')   # once

export RAIL8_ADMIN_TOKEN=rail8-local               # the admin API's bearer token; any string will do, keep it for later shells
docker compose up -d --build                       # the node, its Postgres and the partner echo
until curl -sf localhost:8081/health >/dev/null; do sleep 2; done
bin/rail8 deploy demo HEAD --expect none          # the demo's last commit, deployed to a node that runs no demo yet
```

The working folder `~/rail8-workspace` holds the packages as git repositories, each named as its folder: the demo is the
repository `demo`. The node mounts the folder read-only and deploys a commit of a repository in it, never the files
beside the commit, so a change is deployed once it is committed. The folder must exist before the first `up`.

Start the UI in the terminal where `RAIL8_ADMIN_TOKEN` is exported. On Windows with the working folder in WSL,
run Vite in that WSL distribution so opening an editor reads the files on the local Linux filesystem.

```sh
cd ui && RAIL8_WORKSPACE=$HOME/rail8-workspace npm run dev
```

Leave the terminal running and open **http://localhost:5173** in your browser. If that port is occupied,
add `RAIL8_UI_PORT=5175` before `npm run dev` and open **http://localhost:5175**.
The menu at the left lists the screens; each opens in a tab of its own in the row under the header, Ctrl+K (⌘K on a
Mac) finds a screen or an open tab, and Ctrl+B (⌘B) folds the menu to its icons.
The UI has **Messages**, the list of the messages the node recorded, **Packages**, each package of `RAIL8_WORKSPACE`
with its branch, last commit, changed files and what the node runs of it, each opening a tab of its changes,
artifacts and history, **Endpoints**, the
settings form of each endpoint file in `RAIL8_WORKSPACE`, **Flows**, the picture of each flow file in it, and
**Mappings**, the mapping studio. The UI sends its
`/admin` requests to the node on `localhost:8081` and adds the token itself, so the browser never sees it.

The studio's mappings are the `*.mapping.json` files in `RAIL8_WORKSPACE`, such as
`mapping-corpus/mappings/order-id-copy`, and the schemas a mapping reads are the files its bundles name, relative to its
file. **Mappings** lists them without the node; opening, saving, compiling and testing a mapping ask the node, so they
need it running. With `RAIL8_READ_ONLY=true` the studio saves and creates nothing.

An endpoint's form is drawn from its component's settings description, which the node serves, so it needs the node
running. It opens read-only; Edit, change a setting, Check, and Save writes the file in the working folder, changing only
the lines of the settings you changed. Commit the change and deploy it, from the repository root in another terminal
where `RAIL8_ADMIN_TOKEN` is exported:

```sh
deployed=$(git -C ~/rail8-workspace/demo rev-parse HEAD)   # the commit deployed before the edit
git -C ~/rail8-workspace/demo add -A && git -C ~/rail8-workspace/demo commit -qm 'An edit'
bin/rail8 deploy demo HEAD --expect "$deployed"            # replaces that commit, and only that one
```

Every deploy names the state it replaces: `none` for a package the node does not run yet, else the commit the node runs.
When the node runs another commit, the deploy is refused (`412`) and nothing changes; the answer's `replaces` names the
commit the node runs, and `bin/rail8 deploy demo HEAD --check` answers it too.

The UI does the same from **Packages**: open `demo`, choose Commit…, write the message and choose **Commit and deploy**.
Deploy… deploys the last commit, and **Deploy this commit…** in the History tab deploys an earlier one, which goes back
to it. The dialog checks the commit on the node first and shows the flows it adds, swaps and removes, or every problem;
Deploy then deploys it over the commit the check found. Uncommitted changes are never deployed.

With `RAIL8_READ_ONLY=true` the forms only show the files, as on a server.

To see the demo flows as pictures, open **Flows** and choose a flow, such as `invoice-sync`. Its tab draws the flow from
left to right inside a labelled frame: the system that calls it arrives on the left, then each step, the fork of a `to`
by prop into its endpoints, a call of another flow beside the step that makes it, and the receiving systems outside the
frame. The frame can be resized and collapsed; collapsed, the connections to the outside stay visible. **Find step**
in the toolbar jumps to a step or a receiver by its name. Choose a step and its properties open in a panel below the
canvas; choose the flow's frame or empty canvas and the panel shows the flow's own settings. Drag the separator, or use
its arrow keys, to change the panel's height, and minimize, maximize or restore it with its buttons. An endpoint opens in
its form with Open endpoint, a flow called with Open flow. The settings of the steps of modules and plugins come from the
node, so it needs the node running too.

To edit a flow, open `invoice-sync` and choose Edit. The button on a connection between two steps opens a menu: Insert
step here adds a step there, whose type you choose, such as `filter`, and whose settings you fill in below the canvas.
The toolbar's Add step inserts after the chosen step. Choose a step to move it up or down or delete it with the panel's
buttons, or, with the canvas focused, with the arrow keys and Delete; Undo (or Ctrl+Z) takes a change back. The
problems are found again after every change; Check lists them, and they do not stop Save. Save writes
`~/rail8-workspace/demo/flows/invoice-sync.yaml`, changing only the lines of what you changed and keeping its comments.
Commit it and deploy it as above. A flow written in a form the editor does not keep,
such as its steps on one line, opens read-only and says why.

While a flow is edited, the rules of a `route` and the endpoints of a `to` by prop are tables: Add a row, change a row's
values in place, move a rule up or down, remove a row, and rename an endpoint's value in its first column. With no step
chosen, the panel is a form of the flow's own settings, such as its `delivery.mode`, and Add a process adds a process
with its first step as a lane of its own. Choose a lane to rename its process, which renames every call of it too, or to
delete it once no step calls it; the panel of a step moves it to the end of another lane with Move to. The step types
offered by Insert step here or Add step also include Call a flow: choose `order-partner`, and the step sends to `order-partner-call`, the `direct`
endpoint whose address that flow serves. A flow whose address no endpoint of the package names gets one,
`call-<flow id>.yaml`, written before the step is added; Cancel leaves it in place. The connection's menu also marks where a
simulation starts and ends: with both marked in one list of steps, the start before the end, Open simulation takes a body, attachments, properties
and headers, and runs nothing. New flow, on the Flows screen, writes a new flow file with one step,
such as `demo/flows/audit.yaml` arriving at `http-in` and sending to `partner-domestic`, and opens it; it never writes
over an existing file.

The node migrates its database itself and serves `/health` on `localhost:8081`. Postgres is published on the host's
loopback port 5432; if that is taken, prefix the `up` with `RAIL8_PG_HOST_PORT=55432`. Without a token the node exits
with "the admin API on 0.0.0.0 is not loopback-only: it needs a token" (`docker compose logs rail8`). Its log
starts with a WARNING that `RAIL8_ADMIN_TLS=off` lets the admin API take credentials over plain HTTP: the stack publishes
that port on the host's loopback only.

## Every morning

Stop the UI (Ctrl+C) and go back to the repository root (`cd ..`), then:

```sh
git pull
./mvnw -B -ntp -q package -DskipTests -pl :rail8-node,:rail8-cli -am
(cd ui && npm ci)
export RAIL8_ADMIN_TOKEN=<the token of the first run>   # a new shell has none, and the node refuses to start without it
docker compose up -d --build --force-recreate           # the node restores the packages deployed before, from its git cache
```

A pull that brings a database migration which clears the deployments, such as `deploy/postgres/V27__deploy_pool.sql`,
leaves the node with no package deployed: the migration deletes the deployment records and every package is deployed
again. You see it as `404 no endpoint at /http/invoice/async` from a call that worked before, and the Messages screen
stays empty. Check the packages with `curl -H "Authorization: Bearer $RAIL8_ADMIN_TOKEN" localhost:8081/admin/flows`,
which lists none. Deploy the demo again:

```sh
bin/rail8 deploy demo HEAD --expect none
```

A working copy of the mapping corpus made by an older checkout, `~/rail8-mapping-workspace`, is moved into the working
folder once, which keeps its edits: `mv ~/rail8-mapping-workspace ~/rail8-workspace/mapping-corpus`. The studio then
finds its mappings under `mapping-corpus/`, and nothing else reads the old folder.

Then start the UI again in the same terminal, with the command of the first run:
`cd ui && RAIL8_WORKSPACE=$HOME/rail8-workspace npm run dev`.

If the node does not start after a pull, the database volume may be from an older checkout. `docker compose down -v`
removes this stack's database and the node's git cache; start again from `docker compose up`, then deploy the demo again.

A package deployed by an older checkout can be listed FAILED after a pull when its endpoint files name an option the node
no longer takes. For example, an `http` in endpoint now names its `address` (`/in`, served at `localhost:8080/http/in`),
no longer a `host`, `port` or `path`. The node's restore keeps the package as it was deployed, and the working folder
keeps the old files: copy the changed ones over them, such as
`cp examples/demo/endpoints/http-in*.yaml ~/rail8-workspace/demo/endpoints/`, which drops any edits of your own in those
files, then commit them and deploy `demo` again as above.

## Seeing it work

```sh
curl -X POST --data-binary @examples/demo/fixtures/sample-invoice.xml -H 'Content-Type: application/xml' localhost:8080/http/in
```

The flow validates the invoice, keys it by `invoiceId`, routes it by currency, maps it with XSLT and sends it to the
partner; the partner's answer is the reply, the invoice as mapped: `<PartnerInvoice number="4711" currency="CZK">…`.

In the UI open **Messages**. It opens on the messages of the last hour, newest first: the invoice is the top row, with
its start, the flow `demo/invoice-sync`, the status Completed, its duration and its business key `invoiceId=4711`.
Above the list, the time range (the last 15 minutes, hour, 24 hours or 7 days), the flow and the status narrow it as soon
as they are chosen; a key name and value, or the name of a step the message passed, narrow it with **Search**, which
also reads the list again up to now. Just above the list, a strip counts the messages of the time range and flow by
status: the invoice is counted under Completed, and Failed shows 0. Choosing a count shows only the messages in that status;
choosing it again shows them all. Type `invoiceId` and `4711` and Search: only the invoice is left. Open it by its
id: its timeline lists the steps `ingress`, `validate`, `extractKey`, `route`, `toPartner` and `partner`, and each step
shows the payload it recorded. The same through the admin API:

```sh
auth="Authorization: Bearer $RAIL8_ADMIN_TOKEN"
from=$(date -u -d '1 hour ago' +%FT%TZ 2>/dev/null || date -u -v-1H +%FT%TZ)   # an hour ago, in UTC (GNU date, or macOS date)
curl -H "$auth" "localhost:8081/admin/messages?from=$from&to=$(date -u +%FT%TZ)"
# {"items":[{"id":"<id>","flow":"demo/invoice-sync","status":"completed","startedAt":"…","durationNanos":…,"keys":{"invoiceId":"4711"}}],"next":null}
curl -H "$auth" "localhost:8081/admin/messages?from=$from&to=$(date -u +%FT%TZ)&status=failed"   # the ones that failed: none
curl -H "$auth" "localhost:8081/admin/message-counts?from=$from&to=$(date -u +%FT%TZ)"   # {"completed":1,"failed":0,"running":0,"cancelled":0}: by status
curl -H "$auth" 'localhost:8081/admin/messages?key=invoiceId:4711'    # the ids of the messages with that key, at any time: {"items":["<id>"],"next":null}
curl -H "$auth" localhost:8081/admin/messages/<id>/steps/5/body       # the payload after step 5: the partner's answer
```

The list's statuses are completed, failed (parked after its retries, or a step of its latest run failed), running (an
at-least-once message queued, waiting for its retry or running) and cancelled (ended by an operator). A message is
listed by the start of its arrival, so a flow whose recording level is `errors` or `off` lists none.

A foreign invoice takes the other route: `sed -e 's/4711/4712/' -e 's/CZK/EUR/' examples/demo/fixtures/sample-invoice.xml | curl -X POST --data-binary @- -H 'Content-Type: application/xml' localhost:8080/http/in`.

The flow `demo/invoice-async` takes the same invoice at least once: it answers `202` after saving it, and a worker sends it on.

```sh
curl -i -X POST --data-binary @examples/demo/fixtures/sample-invoice.xml -H 'Content-Type: application/xml' localhost:8080/http/invoice/async
```

In **Messages** choose the flow `demo/invoice-async`: the invoice is its top row, Completed once the worker has sent it to the
partner. Its timeline lists `ingress`, `validate`, `extractKey`, `route`, `toPartner` and `partner`.

The flow `demo/sftp-invoice` takes a file dropped in the inbox of the stack's SFTP server (user `demo`, password `demo`). Drop one:

```sh
docker compose cp examples/demo/fixtures/inbox/invoice-4711.xml sftp:/home/demo/inbox/
```

The node polls the inbox every 10 seconds, so within about ten seconds the file is gone from the inbox
(`docker compose exec sftp ls /home/demo/inbox` lists nothing) and **Messages**, with the flow `demo/sftp-invoice` chosen, shows
the invoice as its top row, Completed, with its steps `ingress`, `validate`, `extractKey`, `toPartner` and `partner`. The
host key of the SFTP server is generated when the stack first starts and the node pins its fingerprint; the password is the
demo's, or `RAIL8_SFTP_PASSWORD` when it is exported before the first `up`.

The `demo/order-jq` flow turns a JSON order with a jq filter: it keeps the order's id and customer as `orderId` and
`customer`, adds up the items into `total`, and calls the flow `demo/order-partner` with the result, which sends it to the
partner.

```sh
curl -X POST --data-binary @examples/demo/fixtures/sample-order.json -H 'Content-Type: application/json' localhost:8080/http/order
```

The reply is the partner's echo of what it was sent: `{"orderId":42,"customer":"Acme Corp","total":50}`. In the UI's
**Messages** the order is the top row of the flow `demo/order-partner`, the flow it called, and the row below it is the
order of `demo/order-jq`. Choose the flow `demo/order-jq`, or type the step name `pick` and Search, and open the message:
its timeline lists the steps `ingress`, `pick` and `partner`, and the payload `pick` recorded is that JSON. The same
through the admin API: `curl -H "$auth" 'localhost:8081/admin/messages?flow=demo/order-jq&step=pick'` gives its `<id>`.

## Stop

```sh
docker compose down      # keeps the database and the node's git cache in their volumes
docker compose down -v   # also removes them: the next start is empty, deploy the demo again
```

Ctrl+C stops the UI.

## Notes

`bin/rail8 deploy <repository> <commit|ref> --expect <commit|none|any>` asks the node to deploy that commit of the
repository of that name in the working folder, which the node reads as `file:///workspace/`, over the state `--expect`
names: the commit the node runs, `none` when it runs no such package, or `any` to replace whatever it runs. It sends the
token and prints the node's answer, or exits non-zero on a refusal. `--check` checks the commit, answers the state as
`replaces`, and deploys nothing; `RAIL8_ADMIN` (default `http://localhost:8081`) points it at another node. A repository in the working folder is a full clone with its own `.git` folder: the node
cannot read a git worktree, or a copy whose `.git` points outside the folder. The `.gitattributes` holding `* -text`
keeps git from changing line endings, so the node deploys the bytes you saved.

The `echo` service is the demo's partner: `deploy/quickstart/PartnerEcho.java` on `localhost:8090`, answering every path
with the body it was sent. The `sftp` service is the SFTP server of `demo/sftp-invoice`; its `sftp-keys` one-shot makes the
host key at the first start. The fuller stack, [`deploy/compose/`](../deploy/compose/README.md), also has Valkey
and, with `--profile kafka`, Kafka.
