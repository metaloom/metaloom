# Metaloom — Agent Notes

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
