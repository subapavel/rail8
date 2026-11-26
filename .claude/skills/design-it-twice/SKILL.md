---
name: design-it-twice
description: Design a contract or another hard piece several times before building it — several agents independently draft radically different interfaces, a second model argues against the recommendation, the owner-proxy picks. Use for every class:C issue — a new contract (adapter, step, API for custom code, plugin loading), public API, storage format, security and the other hard work.
---

<!-- Adapted from mattpocock/skills `codebase-design/DESIGN-IT-TWICE.md`, commit
     5863806dd4e15c63174bee4e7515a4256792ecfa (2026-11-12), MIT licence
     (../LICENSE-mattpocock-skills). After John Ousterhout's "Design It Twice": the first idea is
     rarely the best. -->

Required for every `class:C` issue before its implementation.

## 1. Frame the problem

Write a brief of at most 40 lines that every designer gets unchanged:

- what the contract is for, and who calls it;
- the constraints any design must meet: the `AGENTS.md` technical rules it touches, and the
  sections of `docs/ARCHITECTURE.md` that govern it, quoted;
- what sits behind the contract and what it depends on;
- a rough code sketch that makes the constraints concrete. It illustrates; it proposes nothing.

## 2. Draft independently

Start at least three agents in parallel, each in a fresh context with the brief and one
constraint of its own, so the drafts differ radically:

- "Minimise the interface: one to three entry points, as much behind each as possible."
- "Maximise flexibility: many use cases, room to extend."
- "Make the most common caller's case trivial."

The drafts come from your own agents.

Each draft returns:

1. the interface: types, methods, parameters, invariants, ordering, error modes;
2. a usage example from the caller's side;
3. what the implementation hides behind it;
4. its dependencies and how a mock implements it;
5. trade-offs: where it is strong, where it is thin.

## 3. Compare and choose

Put the drafts side by side and compare them by depth (how much a caller gets per entry point),
locality (where a future change lands) and how a mock and the contract suite would test them.
Recommend one, or a hybrid with the parts named.

A second model then argues against the recommendation. It names the weak points, the failure
scenarios and what the other drafts do better. Codex does this, and only this: it writes no draft
and reviews no pull request. Sessions cannot run Codex; the owner runs it by hand in his Codex
cloud environment over this repository, read-only.

1. Post one comment on the issue with the complete prompt for Codex in a single code block, ready
   to copy. The prompt stands on its own: it names the files to read, states the recommendation
   and the alternatives turned down, asks for the weak points and what would break first, and
   tells Codex to change no file and open no pull request. It then says that one write is allowed
   and approved by the owner: `The only thing you may write is one comment with your review on
   issue #N; the owner approves that comment here.` Without that sentence Codex stops and asks
   before it posts. Its last line is `Write your review as
   a comment on issue #N in subapavel/rail8.`: a Codex cloud run posts that comment by itself, but
   only when the prompt says so. Label the issue `codex-wanted`. Never write a mention of Codex in
   a comment.
2. Tell the owner at once. The daily overview lists every issue that still waits with `codex-wanted`.
3. The owner pastes the prompt into Codex. Codex posts its answer as a comment on the issue; when
   it does not, the owner pastes the answer instead.
4. Read the answer. Take each objection, or turn it down with a reason in the issue.
5. Every prompt asks Codex to mark each finding as blocking or as a note, and to open its answer
   with `No blocking objections` or `Revise`. A finding is blocking when the design would show a
   wrong result as right, lose data or a message, open a security fault, or do harm at an upgrade
   that cannot be undone or that stops the platform. Everything else is a note.
   When the answer opens with `Revise`, revise the design, let the owner-proxy judge the revision,
   and post a new prompt as in step 1, as short as a complete prompt can be: it says what the
   earlier reviews confirmed, what changed, and asks for blocking findings in that. Repeat until
   an answer opens with `No blocking objections`. Take each note in or file it as an issue,
   without another round; then remove the label and go on.
   When two rounds in a row go on the same part of the design, take that part out or make it
   simpler before the next prompt, and say so to the control session.
6. A design is agreed, merged and built only after an answer from Codex without blocking
   objections. No deadline ends the wait. While you wait, an agent on a different Claude model
   than yours may argue against the recommendation, so that the design is ready sooner; the
   label stays, and the daily overview lists the issue until the owner has run the prompt.
7. The review of a design of security is not published. Its prompt ends with `Do not post your
   review anywhere; give it as your answer.` in place of the line about the comment, and has no
   sentence that allows one. The owner hands the answer to the control session, which keeps it
   outside this repository and sends it to you. On the issue, write for each finding only its
   number, a neutral title and what the design now says; never the review's text, and never a
   weakness of today's code.

Give the brief, the drafts, the recommendation and the objections to the `owner-proxy` agent; it
chooses.

## 4. Hand the design to the build session

The session that designs does not build. Once the owner-proxy has chosen:

1. Post one comment on the issue that starts with the line `Agreed design`. It stands on its own,
   because the build session reads nothing else of your work: the chosen contract in full (types,
   methods, parameters, invariants, ordering, error modes), the reason it was chosen, the
   alternatives turned down, each objection with what was done about it, and what the
   implementation must not do.
2. Add the label `design-agreed`, remove `in-progress`, tell `@parent` in one message, and stop.

A session that starts on an issue labelled `design-agreed` skips sections 1 to 3. It reads the
`Agreed design` comment, writes the contract before its implementation, and records the contract
and the reason in the pull request that introduces it. When the design turns out wrong while
building, it says why in the issue, removes `design-agreed` and `in-progress`, and stops; the next
session designs again.
