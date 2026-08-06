# Metaloom — Agent Notes

* Don't git stash / git reset changes. Never do that!

## Start here: the spec tree

[spec/METALOOM_CONTEXT.md](../spec/METALOOM_CONTEXT.md) is the **entry point** for coding tasks in
this repository.
It catalogues every specification file under `spec/`, routes you to the right one for your task,
and carries the project-wide cheat sheets, conventions and gotchas. Read it before starting work.

Two files in that tree are **rules, not background**:

- [spec/guidelines/CODING.md](../spec/guidelines/CODING.md) — definition of done for a code change
  (plural REST paths, endpoint + permission tests, DAO and delete-cascade tests, customer-facing
  website docs, demo data, and the obligation to update the matching spec file).
- [spec/SPEC_RULES.md](../spec/SPEC_RULES.md) — definition of done for a spec change.

When a spec and the code disagree, the code wins — and fix the spec in the same change.

## Test database pool setup (IMPORTANT)

Tests run against a pooled test database. Before running tests — and again **after any Flyway
migration change** (adding/editing files under `loom/db/flyway/.../db/migration/`) — you must
(re)initialize the pool:

```bash
./setup-pool.sh
```

This runs `io.metaloom.loom.test.PoolSetupRunner` (via `mvn exec:java -pl loom/fixture`), which
provisions and populates the test databases used by the suite.

If you skip this:
- The test database will not be populated, and tests will fail.
- After a Flyway change, the pooled databases will be stale/outdated relative to the new schema.

Re-run `./setup-pool.sh` whenever schema/migrations change.
