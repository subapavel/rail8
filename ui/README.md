# Rail8 web application

Open Canvas keeps workspace tabs in the top bar. Use the left-edge navigation or press Ctrl+K (Cmd+K on macOS) to open a screen or switch to an open tab. The shell discovers each module through its `module.tsx`. Each editor owns its working copy until its tab closes. Right-click a tab, or press the menu key or Shift+F10 on it, to close it, the others, those to its left or right, or all but the pinned ones, and to pin or unpin it. When tabs about to close have unsaved changes, the shell asks once whether to save them; an editor tells it through `unsaved`, which also saves them. The button with three dots at the end of the row lists the tabs that do not fit.

## Development

Use Node 22.12 or newer and run these commands from `ui/`.

```sh
npm ci
npm run dev
```

Open `http://localhost:5173`. On Windows, run Vite in the same WSL distribution as the working folder to avoid reading each workspace directory across the Windows filesystem bridge. If another application uses the default port, run `RAIL8_UI_PORT=5175 npm run dev` and open `http://localhost:5175` in Windows. Set `RAIL8_WORKSPACE` to the directory of packages to inspect or edit. Set `RAIL8_ADMIN_URL` to the node's admin origin (default `http://127.0.0.1:8081`) and `RAIL8_ADMIN_TOKEN` in the environment of the Vite server. Setting `RAIL8_READ_ONLY=true` makes the file editors read-only. The quickstart instructions cover starting a node. The production build produces static assets under `dist/`; serving those assets also requires the application's HTTP routes.

## Presentation

Use Ant Design 6 for navigation, controls, forms, dialogs and status feedback. ProTable fits file listings and monitor results. The owner approved the exact ProComponents v3 prerelease pinned in the lockfile for its Ant Design 6 peer support. The workspace uses Ant Layout with Rail8's own tab lifecycle.

`Rail8Theme` configures the Ant application context, English locale, compact theme and component size. The theme initially follows the OS preference. The switch stores the chosen mode in `rail8.theme`. Use `theme.useToken()` in components and `var(--ant-...)` in CSS. Both graph editors keep their React Flow layouts. The virtualized schema pane uses native table rows and token-styled field buttons to limit rendering work while scrolling.

### Migration inventory and sequence

| Area | Presentation | Behavior under test |
| --- | --- | --- |
| Shell and Home | Responsive sidebar, registered shortcuts, workspace tabs | Navigation order, focus, close guards, child tabs and empty workspace |
| Monitoring | Cursor-paged message list, status filters, attempt timeline and payload dialog | Admin reads, counts, retained payload limits and restart |
| Endpoints | File list, schema-driven settings and raw JSON controls | Read-only access, validation, environments and conditional YAML writes |
| Flows | File list, framed React Flow diagram, connection actions and resizable bottom properties | Selection, process calls, edits, undo, validation and save conflicts |
| Mappings | List and creation, Overview, Definition, Signature, Functions and Test | Working copies, ETags, structure selection, compile diagnostics and engine runs |

The migration introduces the theme first, then converts the shell and lists, the editors and finally their browser checks. Domain operations stay in the existing API clients, working-copy stores and file middleware. A future monitoring chart can consume a supported aggregate from the admin API. The current screen displays its actual status counts directly.

### Ant Design tooling

The official CLI includes the Ant Design skill and MCP server. From the repository root, verify the project skill with `npx --yes @ant-design/cli@6.7.1 setup --client codex --check`. The skill is checked in under `.agents/skills/antd`. The CLI's Codex setup supports skill installation. Register its stdio server once in Codex if needed:

```sh
codex mcp add antd -- npx --yes @ant-design/cli@6.7.1 mcp --version 6.7.1
codex mcp get antd
```

On Windows use `codex mcp add antd -- cmd.exe /d /c npx.cmd --yes @ant-design/cli@6.7.1 mcp --version 6.7.1`. The repository's `.mcp.json` supplies the equivalent configuration for clients that read it. Use `antd_info`, `antd_demo` and `antd_token` for the installed version. From `ui/`, check for obsolete properties with `npx --yes @ant-design/cli@6.7.1 lint ./src --only deprecated --format json`.

## Verification

```sh
npx playwright install chromium
npm run licences
npm run gen:types
git diff --exit-code -- src/mapping/generated
npm run typecheck
npm run lint
npm test
npm run test:browser
npm run e2e
npm run build
```

Run `npm run e2e:stack` on Linux or WSL with the quickstart node running and the matching admin token in the environment. Its Vite server uses a temporary working copy of the mapping corpus. The file middleware unit tests exercise POSIX symlinks, permissions and paths. CI runs these tests on Linux. From the repository root, run `python3 ci/change_guard.py --base origin/main` for the committed diff.
