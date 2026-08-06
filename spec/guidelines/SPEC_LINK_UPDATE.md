# Spec Link Update — Prompt

A prompt that repairs the *references* in one spec file: paths, class names, spec links. It never
reads the spec for meaning and never rewrites content — that is a separate, more expensive pass.

Copy the block below, replace `{{SPEC_FILE}}`, run it on one spec file at a time.

---

--- PROMPT START ---

Fix stale **references** in exactly one Markdown spec file:

    TARGET: {{SPEC_FILE}}
    REPO:   /home/defaultuser/workspaces/metaloom/metaloom

For every reference to something outside the file — source file, directory, script, Java class or
package, other spec file — check that the target still exists. If it moved, update the reference. If
it is gone or ambiguous, leave it and report it. That is the whole job.

## Do not

* Do not judge whether described behaviour, values, defaults, tables or diagrams are still accurate.
  That is not your pass.
* Do not touch prose, formatting, headings or table rows.
* Do not delete an unresolvable reference, and do not create files to make one resolve.
* Do not change link text — unless the text *is* the path (`[LOOM.md](../loom/LOOM.md)`), then change both.
* Do not check `http(s)://` URLs.
* Do not run `git stash`, `git reset` or `git checkout --`.

## Reference forms

| Form | Example | Resolved against |
|---|---|---|
| Markdown link | `[PIPELINE.md](../features/pipeline/PIPELINE.md)`, `[pom.xml](../../pom.xml)` | the target file's directory |
| Backticked path | `` `loom/db/jooq/generate.sh` ``, `` `cortex/nodes/` `` | repo root |
| Elided path | `` `cortex/cli/.../NodeCollectionModule.java` `` | repo root, `...`/`…` = any depth |
| Java class / package | `` `PipelineGraphParser` ``, `` `io.metaloom.loom.pipeline.graph` ``, `` `…pipeline.graph` `` | whole repo |

Also covers the rows of "Key Classes Reference" and "Where do I find …?" tables, and references
inside code fences and Mermaid blocks. Anchors (`FILE.md#section`) — check the file part, keep the
anchor verbatim.

## Procedure

1. **Collect** every reference with its line number.
2. **Resolve** with the shell, never from memory:
   * path — `ls -d <REPO>/<path>`; trailing `/` must be a directory
   * elided — `rg --files -g '**/<basename>' <REPO>`, visible head/tail segments must match
   * class — `rg -l -g '*.java' '\b(class|interface|enum|record) <Name>\b' <REPO>`
   * package — `rg -l 'package <fqpackage>;' <REPO>`; for `…tail`, match the tail only
3. **Relocate** anything broken, stopping at the first answer: same basename elsewhere → class
   search → `git log --oneline --diff-filter=DR -20 -- <old path>` plus
   `git show --stat --find-renames <sha>` → for specs, search all of `spec/` (files move between
   `features/`, `concept/`, `plans/`, `tasks/`, `guidelines/`).
   **Exactly one candidate = rewrite. Zero or several = leave it, report it.**
4. **Rewrite** surgically: recompute the relative path for markdown links, keep repo-root form for
   backticked paths, preserve backticks, table pipes, link text, anchor and elision style.
5. **Footer** — only if something changed, update the [SPEC_RULES.md](SPEC_RULES.md) footer:
   `` _Git HEAD revision: `<git rev-parse --short HEAD>`_ `` and
   `_Last updated: <YYYY-MM-DD> (reference sweep — no content changes)_`. Appending to an existing
   footer note, or adding the two lines if absent, is the only addition you may make.
6. **Report** and nothing else:

```
UPDATED (n)
  line 42  ../CONTEXT.md          -> ../METALOOM_CONTEXT.md
  line 118 `loom/services/ai/...` -> `loom/agent/chat/...`
MISSING (n)   # left untouched, needs a human
  line 90  `FooBarService` — 3 classes match, ambiguous
CHECKED: n references, n OK
```

## Examples

* `[../CONTEXT.md](../CONTEXT.md)` — no such file; `spec/METALOOM_CONTEXT.md` is the only match →
  rewrite both text and path.
* Key Classes row says `ChatService` is in `io.metaloom.loom.services.ai`; `rg` finds it in
  `io.metaloom.loom.agent.chat` → fix the Package cell only, leave Purpose alone however wrong.
* Spec says the default timeout is 30s, the code says 60s → ignore, not your pass.

--- PROMPT END ---

---

Link rot is mechanical and high-volume — `ls` and `rg` settle it, so a cheap model can keep the spec
tree navigable while full content re-verification runs only when a feature actually changed. The
"Do not" list is load-bearing: without it the pass drifts into rewriting content.

_Last updated: 2026-08-06 (initial version)_
_Git HEAD revision: `742dae2d`_
_Last updated: 2026-08-06 (reference sweep — no content changes)_