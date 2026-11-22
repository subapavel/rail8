# Rail8 frontend handoff for Claude Code

Continue on **`migration/ant-design-6`**. The last verified **application** commit is `f33d18d4bbc255eec70b01573d08ae48330f1aab`. This document was added after that commit. Use the current branch tip to pick up both the code and the handoff.

On 22 November 2026 the implementation comprised 21 commits and 142 changed files relative to `main` at `4d53c19f8805136cbb2bcad59a690fb350d3cc2a`. The Windows checkout was clean, the implementation was pushed, the class B issue was open, and the draft PR had passed DCO. Pavol reserved approval of the merge to `main`. [Draft PR](https://github.com/subapavel/rail8/pull/321) · [Migration issue](https://github.com/subapavel/rail8/issues/320) · [Implementation diff at handoff](https://github.com/subapavel/rail8/compare/4d53c19f8805136cbb2bcad59a690fb350d3cc2a...f33d18d4bbc255eec70b01573d08ae48330f1aab)

## Where to work

These are the locations verified on Pavol's machine at handoff.

| Location | Purpose |
|---|---|
| Windows `%USERPROFILE%\Documents\Projekty\rail8` | Editable checkout on the migration branch. |
| WSL Ubuntu `~/rail8-fast/ui` · [application on port 5175](http://localhost:5175/) | Vite serving the integrated application. This checkout had detached HEAD at `f33d18d4b`. Windows edits reach this server only after its checkout is updated. |
| WSL `~/rail8-workspace` | The running UI's `RAIL8_WORKSPACE`. Demo files are in `demo/` and mappings in `mapping-corpus/`. The [admin health endpoint](http://localhost:8081/health) reported `UP`. |
| [Open Canvas prototype on port 5188](http://127.0.0.1:5188/studio.html#canvas) | Selected interactive study with sample data. Use port 5175 to verify working-folder and node behavior. |
| WSL `~/rail8-open-canvas/ui`, port 5176 | An older development copy at `3a98fb3ff`. |
| Windows `%USERPROFILE%\.codex\visualizations\2026\11\21\01a20071-e63f-72a3-a7bf-01a886bd01bc` | Local design artifacts. The selected study is in `rail8-design-studies/`, the alternatives in `rail8-design-studies/archive/rail8-layout-studies.zip`, and screenshots in `ant-design-review/`. These artifacts live outside the repository. |

## Implemented changes

| Area | Behavior and decisions | Entry points |
|---|---|---|
| Design system | Replaced PatternFly Core, Icons and Table with Ant Design 6.7.1, Ant Icons 6.3.4 and ProComponents 3.1.15-9. Pavol approved that exact beta for Ant 6 compatibility. Added Monaco 0.57.0. Both graphical editors use the existing React Flow 12.12.1 dependency. The shared provider supplies compact sizing, English UI strings and a persisted light or dark theme. | [Dependencies](../ui/package.json), [theme](../ui/src/theme.tsx), [modal and focus handling](../ui/src/MountedModal.tsx) |
| Workspace and Home | Open Canvas keeps the tabs at the top, the R8 mark in the header and the Rail8 logo inside the expanded navigation. The left Ant Sider overlays the workspace without shifting the canvas. Ctrl+K or ⌘K finds screens and open tabs. Opening an existing document reselects its tab. Editors retain their working copy and guard unsaved changes on close. | [Shell](../ui/src/Shell.tsx), [navigation](../ui/src/WorkspaceNavigation.tsx), [Quick open](../ui/src/WorkspaceQuickOpen.tsx), [Home](../ui/src/Home.tsx), [workspace styles](../ui/src/workspace.css), [brand assets](../ui/public/) |
| Messages | Migrated the list, filters, status counts, cursor paging, attempt timeline and retained-payload dialog. Message details open in child tabs. The existing Restart action remains available for eligible failed messages. | [Message list](../ui/src/messages/MessageList.tsx), [message detail](../ui/src/messages/MessageView.tsx) |
| Endpoints | Migrated the list and schema-driven editor, direction-dependent fields, environments, raw JSON options, validation and read-only mode. Saves use the existing conditional file-write contract. | [Endpoint list](../ui/src/endpoints/EndpointList.tsx), [form](../ui/src/endpoints/EndpointForm.tsx), [fields](../ui/src/endpoints/FieldControl.tsx), [raw options](../ui/src/endpoints/RawOptions.tsx) |
| Mappings and Mapping Studio | Migrated list, search, creation, Overview, Definition, Signature, Functions and Test. Definition keeps its virtualized trees and React Flow expression canvas. Fixed context menus on scrolled pages, focus after closing dialogs, selection and graph-state visibility. Test loads Monaco and its XML worker locally on demand. Mapping checks, compilation, engine tests and save-conflict handling remain available. | [List](../ui/src/mapping/list/), [Studio](../ui/src/mapping/studio/), [schema tree](../ui/src/mapping/studio/definition/SchemaPane.tsx), [expression canvas](../ui/src/mapping/studio/definition/expression/), [XML editor](../ui/src/mapping/studio/test/XmlEditor.tsx) |
| First-open performance | Removed sequential waits for screen code and data. Short reads finish before the loading indicator appears. The flow diagram can render while node metadata is loading. The working-folder scan reads files in batches of eight. Mappings renders 25 rows per page and searches the full loaded list. E2E scenarios can find mappings on any page. Vite accepts `RAIL8_UI_PORT`. | [Working-folder scan](../ui/server/workingFolder.ts), [port selection](../ui/server/uiPort.ts), [loading feedback](../ui/src/ReadSpinner.tsx), [mapping list](../ui/src/mapping/list/MappingList.tsx), [E2E helper](../ui/e2e/studio.ts) |
| Flow diagram | React Flow draws a labeled, resizable, collapsible flow frame. Calling and receiving systems have their own frames outside it. Smaller nodes and longer connections leave room for insertion and edge actions. The toolbar has step search and, while editing, add, delete and undo. The canvas provides zoom and fit. A searched step stays visible when its properties open. YAML remains the file representation. The editor presents the diagram only. | [Flow editor](../ui/src/endpoints/flows/FlowPicture.tsx), [canvas](../ui/src/endpoints/flows/FlowCanvas.tsx), [graph projection](../ui/src/endpoints/flows/canvasGraph.ts), [canvas styles](../ui/src/endpoints/flows/FlowCanvas.css), [editor styles](../ui/src/endpoints/flows/flow-editor.css) |
| Bottom properties panel | Selecting a step shows its properties. Clicking the flow frame or empty canvas shows flow properties. The panel supports drag resizing, minimizing, maximizing and restoring the previous split. | [Inspector](../ui/src/endpoints/flows/FlowInspector.tsx), [fields](../ui/src/endpoints/flows/FlowPanel.tsx) |
| Flow simulation preview | Mark start and end on connections. Play opens for a nonempty forward range in one step list, including a single-step subprocess. Body, Attachments, Properties and Headers can be edited and previewed. The draft belongs to its open flow tab. Up to eight local files of 1 MiB each are supported. A structural edit invalidates old markers. Pavol approved an interactive input preview. Pressing Play opens the dialog without executing steps or uploading files. | [Range semantics](../ui/src/endpoints/flows/simulationRange.ts), [dialog](../ui/src/endpoints/flows/SimulationDialog.tsx), [canvas interaction tests](../ui/src/endpoints/flows/FlowCanvas.browser.test.tsx), [dialog tests](../ui/src/endpoints/flows/SimulationDialog.browser.test.tsx) |
| Agent tooling and documentation | Decision D-166 adopts Ant Design, its tokens, the official Ant MCP and React Flow. Claude Code has repository configuration for Context7 and the Ant MCP. React and Mapping Studio guidance, the color guard and the development and verification instructions were updated. | [Agent rules](../AGENTS.md), [MCP configuration](../.mcp.json), [Ant skill](../.agents/skills/antd/SKILL.md), [decisions](DECISIONS.md), [architecture](ARCHITECTURE.md), [UI development](../ui/README.md), [change guard](../ci/change_guard.py) |

## Open work and scope

- **Release.** The branch is ready for review. The local app runs through Vite. Serving the static build in production also needs a server for the application's `/admin`, `/api` and `/files` routes. Production deployment is still open.
- **Running a selected flow range.** The CLI can test a whole flow. Executing the selected range and returning results would need an engine interface and a separate decision. Mapping Studio already executes mapping tests on the engine.
- **Git and quick actions.** Quick open works today. Editors write working-folder files and the CLI can deploy a commit. Branch management, diff, commit and deploy inside the UI remain future work. Open Canvas was selected from the twenty layout studies.
- **Documentation drift.** Parts of the [quickstart](QUICKSTART.md) still describe the former side inspector. Use the [flow editor guide](FLOW-EDITOR.md) and [interaction tests](../ui/src/endpoints/flows/Flows.browser.test.tsx) for the integrated layout. The draft PR description reports test counts from an earlier migration stage.

## Verification and performance evidence

| Scope | Recorded result |
|---|---|
| Application commit `f33d18d4b` in WSL | Passed 959/959 unit tests, 363/363 Chromium component tests, TypeScript, the production build and `python3 ci/change_guard.py --base origin/main`. ESLint reported zero errors and two existing TanStack Virtual warnings in `SchemaPane.tsx`. |
| Running application on port 5175 | Chromium verified both flow-properties click targets, start/end marking and a preview containing a body, a local file, a property and a header. No write, upload or execution request was sent. Independent review confirmed the first connection in a subprocess and distinct endpoints with identical labels. |
| Earlier migration revisions | A repeat run passed all 14 Playwright scenarios against the real node after earlier failed runs. The standalone 20,128-field tree scenario, contract type regeneration and dependency licence checks also passed during the migration. |
| GitHub and native Windows | DCO passed on the application commit. GitHub skipped the build and nightly jobs on that revision. A native Windows run during implementation had 41 file-test failures involving, among other things, POSIX permissions and symlinks. Linux or WSL is the reference for the full suite. |

**First open on identical data.** Twelve alternating pairs used fresh Chrome contexts, running development servers and the same response hash for 254 mappings. Median time from clicking Mappings to the first actionable row was **443 ms with PatternFly and 358 ms with optimized Ant**, an 85 ms reduction (about 19%). PatternFly rendered 254 rows and Ant rendered the first page of 25. This measurement predates Open Canvas. Raw local data is in `ui/.logs/mapping-compare-5659657b2d484c8bafecf2cfc7c27dce.jsonl` in the Windows checkout.

A separate before/after comparison of the Ant implementation measured 887 ms and 452 ms for Mappings. The changes that included running Vite alongside workspace files in WSL reduced the Flows list median from 3,425 ms to 223 ms and opening the `order-partner` diagram from 3,387 ms to 223 ms. Local test logs were kept in the temporary WSL directory `/tmp/rail8-open-canvas.Vn1VUFfM/`, including `sim-final-*.log` and `stack-rerun.log`. [Recorded benchmark conditions](https://github.com/subapavel/rail8/pull/321)

## Get the branch and check the app

For a fresh checkout:

```sh
git clone --branch migration/ant-design-6 https://github.com/subapavel/rail8.git rail8-review
cd rail8-review
git rev-parse HEAD
git log --reverse --oneline origin/main..HEAD
git diff --name-status origin/main...HEAD
```

On Pavol's machine use the existing server on 5175. For a fresh WSL launch, use Node 22.12 or newer, provision the demo working folder and export the running node's admin token as described in the [quickstart](QUICKSTART.md). Starting from the repository root:

```sh
cd ui
npm ci
RAIL8_WORKSPACE="$HOME/rail8-workspace" RAIL8_UI_PORT=5175 npm run dev
```

The quickstart also covers starting the node and sending a test message. To exercise the preview, open **Flows → order-partner**. Choose **Set simulation start here** on the connection before `partner`, then **Set simulation end here** on the connection toward `partner-domestic`. Press **▶**, fill in the input and choose **Preview input**. Close the dialog and click the empty canvas to see the flow properties.

## Implementation commit map

Use `git show <hash>` to inspect any of the 21 application commits, listed in chronological order.

| Area | Commits |
|---|---|
| Foundation and screens | `4765d6c05` dependencies and theme · `40d41d563` shell and tabs · `3dd4fddde` Messages · `b1067c723` endpoints and flows · `18aed5fac` mapping graphs and schema trees · `d8adde03e` Mapping Studio and Monaco |
| Loading and migration cleanup | `f25b3ef7c` screen loading and visibility of inserted steps · `799383d3c` PatternFly removal, developer tooling and documentation |
| Interaction and performance | `d8ee364e4` context menu coordinates · `1b61e6d92` focus and graph selection · `ce3acebfd` automatic diagram fitting · `a123e2956` faster schema rows · `2f1fa3c85` nested loader removal · `69accb492` paging and concurrent file reads · `fa694c83d` cross-page lookup in Studio scenarios |
| Integrated Open Canvas | `a306caf8a` overlay, branding and Quick open · `0a5c43193` frames, edge actions and bottom properties · `3a98fb3ff` searched step visibility · `95c697042` flow properties on frame and background clicks |
| Simulation preview | `1f2aa5634` range markers, Play and input dialog · `f33d18d4b` first step of a subprocess and ranges whose endpoints share a label |
