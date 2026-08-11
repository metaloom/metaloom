# Task File Format

Required structure for every `*_TASKS.md` file in this tree (see
[CONTEXT.md](CONTEXT.md) §0.3 and [SPEC_RULES.md](../guidelines/SPEC_RULES.md)). Task files hold
**actionable work items only** — design rationale belongs in the matching spec file and is
linked, not duplicated.

## File skeleton

````markdown
# <Area> — Task List

> Work items for <feature>, derived from a code audit on <YYYY-MM-DD>.
> Format follows [../../TASKS.template.md](TASKS.template.md).
>
> **Context:** [FEATURE.md](FEATURE.md) (technical spec) · [FEATURE_REQUIREMENTS.md](FEATURE_REQUIREMENTS.md)
>
> Ordering / blocking notes: which tasks gate which.

---

## Task 1: <Imperative headline>

**Argumentation Summary:** Why this task is needed — the concrete defect or gap, and what
it costs today. Name the classes and files involved.

**Improvement Summary:** What the improvement entails, in one or two sentences.

```
<Task prompt for an AI coding agent: numbered implementation steps with exact file
paths, method names and the intended behaviour. Detailed enough to execute without
re-deriving the design.>
```

**References:** [FEATURE.md](FEATURE.md) §9.2 · migration `V2.38__add_x.sql` · related task files
**Test Requirements:** The tests that must exist and pass, named explicitly, plus the
command to run them.

---

## Task 2: <Headline> — ✅ DONE (YYYY-MM-DD)

...
````

## Rules

* Number tasks (`## Task 1:`, `## Task 2:`) and keep the numbers stable — other files cite them.
* Mark completion in the heading: `— ✅ DONE (YYYY-MM-DD)`; keep the task text and add an outcome
  note rather than deleting it. Larger files may instead carry an "Implementation Status" table
  plus an "Open Follow-ups" section.
* Order by severity, and say up front which tasks are blocking.
* Separate tasks with `---`.
* All four labelled fields are mandatory: **Argumentation Summary**, **Improvement Summary**,
  **References**, **Test Requirements** — plus the fenced agent prompt between them.
* Cross-reference with relative links instead of restating spec content.
* Close with the two-line footer required by [SPEC_RULES.md](../guidelines/SPEC_RULES.md): git HEAD revision
  and last-updated date.
_Git HEAD revision: `742dae2d`_
_Last updated: 2026-08-06 (reference sweep — no content changes)_