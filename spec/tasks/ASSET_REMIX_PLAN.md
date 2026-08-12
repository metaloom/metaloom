# Asset Remix — Implementation Plan

> End-to-end plan for the Remix feature, derived from a code audit on 2026-08-12.
> Format follows [TASKS.template.md](TASKS.template.md).
>
> **Supersedes** [PERSISTENCE_TASKS.md](PERSISTENCE_TASKS.md) Task 7, which proposed adding
> `addRemix`/`removeRemix`/`loadRemixes` to `AssetDao` over the existing pair table. That scope is
> too small for the intended feature (see Section 1), so Task 7 is closed as superseded by Task 14
> of this file rather than implemented.
>
> **Context:** [../loom/DOMAIN.md](../loom/DOMAIN.md) (entities) ·
> [../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) (how the DAO layer works) ·
> [../loom/RESTAPI.md](../loom/RESTAPI.md) (route conventions) ·
> [../loom/MCP.md](../loom/MCP.md) (tool registration) ·
> [../guidelines/CODING.md](../guidelines/CODING.md) (definition of done) ·
> migration `V2.8__add_asset.sql` (the table being replaced) ·
> migration `V2.61__add_dedup_group.sql` (the structural precedent being copied)
>
> **Status: all 14 tasks complete (2026-08-12).** The design now lives in
> [../features/remix/REMIX.md](../features/remix/REMIX.md); Sections 1-3 below are kept as the
> record of what was decided and why, and the task list as the record of what was done.
>
> **Blocking order:** Task 1 gates everything. Then 1 -> 2 -> 3 and 1 -> 4 -> 5 -> 6 -> 7.
> Tasks 8-13 need Task 5 done. Task 14 runs last so the specs describe what actually shipped.
> Tasks 2 and 5 both touch Dagger registries: run them one at a time and clean-rebuild `loom/core`
> in between, or `setup-pool.sh` and every dependent test fail with `NoSuchMethodError` on a
> generated factory.

---

## 1. Why the existing table is being replaced

`asset_remix` (`V2.8`) is the oldest unreachable table in the schema:

```sql
CREATE TABLE "asset_remix" (
  "asset_a_uuid" uuid NOT NULL,
  "asset_b_uuid" uuid NOT NULL,
  "meta" jsonb,
  "created" timestamp NOT NULL DEFAULT (now()), "creator_uuid" uuid NOT NULL,
  "edited"  timestamp NOT NULL DEFAULT (now()), "editor_uuid"  uuid NOT NULL
);
```

No primary key, no unique constraint, no relation type, no enforced direction, and no foreign key on
`editor_uuid` (which is why `DANGLING_ASSET_REMIX_EDITOR` exists). It is the only PK-less entry in
`AuditedTables.ALL`, which forces the `hasUuid` exception at `AuditedTables.java:73-75` and the
pair-keyed special case in `DanglingUserReferenceCheck.assetRemixEditor()`. Outside generated jOOQ
code and the integrity checks the only reference in the repository is a deferral comment at
`AssetCascadeTest.java:93-94`. It has never had a writer, so it holds no rows in any installation.

The feature it is meant to carry is a **group**: a named thing that appears in the asset grid, that
a user opens like a folder, and that holds an original plus everything derived from it. A pair table
cannot express a group, a name, or which member is the source. Rather than bolt a partial API onto a
model that does not fit, the table is dropped and replaced.

## 2. Design decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Group entity, not edges: `remix` + `remix_member` replacing `asset_remix` | Mirrors `dedup_group`/`dedup_group_member` (`V2.61`), the closest structural precedent in the schema, which already has a complete DAO to REST to client to UI slice to copy |
| D2 | Remix cards render in a pinned band above the normal asset grid; member assets stay visible in the main feed | No separate view, per the product intent. Hiding members would need an anti-join on the hottest query in the product and would make assets disappear from the grid the moment somebody remixes them |
| D3 | Creation via a selection tray (select mode plus a bulk-bar overflow menu) and via `Add to remix...` in the asset detail overflow menu | The selection path is how a user builds a group of six without six round trips; the detail path is how a single asset joins an existing group |
| D4 | Clicking a remix card opens a modal that writes `?remix=<uuid>` into the URL | Keeps the user in the asset view and keeps the state deep-linkable, so an e2e spec and the screenshot script can land directly on an open dialog |

Out of scope, deliberately:

* ~~No `SearchEntityType.REMIX`.~~ **Delivered afterwards** (2026-08-12) as a follow-up: `V2.103`
  indexes remixes in `search_document` and the asset browser gained a `Remixes` type filter. The
  reasoning for deferring it was sound - it does pull in the `V2.58`/`V2.59` trigger machinery, and
  `DbIntegrityChecksTest` asserts every type is mapped in `SearchDocumentEntities.TABLES` - it was
  simply wanted. See [../features/remix/REMIX.md](../features/remix/REMIX.md) §6.
* No node-generated lineage. [../concept/ASSET_METADATA_WRITE.md](../concept/ASSET_METADATA_WRITE.md)
  G3 (typed machine lineage from a source asset to a derived one, written by a node that produces new
  bytes) stays open. A human-curated group does not answer it, and Task 14 says so in that file.
* No nesting. A remix holds assets, never other remixes.

## 3. Target schema

Highest existing migration is `V2.99`. Three new files, because Postgres cannot use an enum value in
the transaction that added it, and because the house style since `V2.89` is one concern per file.

`V2.100__add_remix.sql`:

```sql
DROP TABLE "asset_remix";   -- V2.8, never had a writer; superseded by the model below

CREATE TABLE "remix" (
  "uuid"              uuid NOT NULL DEFAULT uuid_generate_v4(),
  "name"              varchar NOT NULL,
  "description"       varchar,
  "source_asset_uuid" uuid,
  "meta"              jsonb,
  "created" timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(), "creator_uuid" uuid,
  "edited"  timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(), "editor_uuid"  uuid,
  CONSTRAINT "remix_pkey" PRIMARY KEY ("uuid"),
  CONSTRAINT "remix_source_asset_uuid_fkey" FOREIGN KEY ("source_asset_uuid")
      REFERENCES "asset" ("uuid") ON DELETE SET NULL,
  CONSTRAINT "remix_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
  CONSTRAINT "remix_editor_uuid_fkey"  FOREIGN KEY ("editor_uuid")  REFERENCES "user" ("uuid")
);

CREATE TABLE "remix_member" (
  "uuid"        uuid NOT NULL DEFAULT uuid_generate_v4(),
  "remix_uuid"  uuid NOT NULL,
  "asset_uuid"  uuid NOT NULL,
  "role"        varchar NOT NULL DEFAULT 'DERIVED',
  "ordinal"     int,
  "created" timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(), "creator_uuid" uuid,
  "edited"  timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(), "editor_uuid"  uuid,
  CONSTRAINT "remix_member_pkey" PRIMARY KEY ("uuid"),
  CONSTRAINT "remix_member_remix_fkey" FOREIGN KEY ("remix_uuid")
      REFERENCES "remix" ("uuid") ON DELETE CASCADE,
  CONSTRAINT "remix_member_asset_fkey" FOREIGN KEY ("asset_uuid")
      REFERENCES "asset" ("uuid") ON DELETE CASCADE,
  CONSTRAINT "remix_member_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
  CONSTRAINT "remix_member_editor_uuid_fkey"  FOREIGN KEY ("editor_uuid")  REFERENCES "user" ("uuid"),
  CONSTRAINT "remix_member_role_check" CHECK ("role" IN ('SOURCE','DERIVED')),
  CONSTRAINT "remix_member_unique" UNIQUE ("remix_uuid","asset_uuid")
);

CREATE INDEX "idx_remix_source_asset" ON "remix" ("source_asset_uuid");
CREATE INDEX "idx_remix_member_remix" ON "remix_member" ("remix_uuid");
CREATE INDEX "idx_remix_member_asset" ON "remix_member" ("asset_uuid");

-- Added during implementation: "at most one SOURCE per remix" is a real invariant, so the database
-- enforces it rather than trusting every write path. A plain UNIQUE (remix_uuid, role) would also
-- forbid a second DERIVED member, so it has to be a partial index.
CREATE UNIQUE INDEX "remix_member_single_source" ON "remix_member" ("remix_uuid") WHERE "role" = 'SOURCE';
```

`V2.101__remix_permissions.sql` adds `CREATE_REMIX`, `READ_REMIX`, `UPDATE_REMIX`, `DELETE_REMIX` to
the `loom_permission` enum and contains nothing else. `V2.102__grant_remix_permissions.sql` grants
all four to `admin-role` with `INSERT ... SELECT ... ON CONFLICT DO NOTHING`, which is the upgrade
path (`DatabaseInitializer` only grants on first creation).

Points worth keeping when writing the migration comments:

* `ON DELETE SET NULL` on `remix.source_asset_uuid` but `CASCADE` on the member row: deleting the
  source asset must not silently delete the whole remix, while a member row without its asset is
  meaningless. Same reasoning as `dedup_group.keep_asset_uuid` in `V2.61`.
* The member with `role='SOURCE'` is authoritative; `source_asset_uuid` is a denormalised
  convenience the DAO keeps consistent, exactly as `keep_asset_uuid` mirrors the `KEEP` member.
* `varchar` plus a named `CHECK` instead of a Postgres enum, so the role vocabulary can grow without
  an `ALTER TYPE` migration.
* Both new tables carry `created` and `edited`, so both belong in `AuditedTables.ALL`.

---

## Task 1: Replace the schema and retarget the integrity checks

**Argumentation Summary:** `asset_remix` cannot express the feature (Section 1) and simultaneously
costs the integrity subsystem three special cases: `AuditedTables.ALL` lists it (`AuditedTables.java:35`),
`AuditedTables.hasUuid` exists solely to exempt it (`:73-75`), and
`DanglingUserReferenceCheck.assetRemixEditor()` (`:63-73`) has to name findings by the asset pair
because there is no uuid to name them by. Nothing writes the table, so no installation holds rows in
it and the drop is lossless.

**Improvement Summary:** Three migrations replacing `asset_remix` with `remix` + `remix_member`,
followed by the mandatory codegen and pool ritual, plus the integrity-check retargeting the drop
makes possible.

```
1. Write loom/db/flyway/src/main/resources/db/migration/V2.100__add_remix.sql exactly as in
   Section 3 of this file. Open with a prose header in the V2.97/V2.99 style: why a group
   replaces the pair table, which alternative was rejected (directed typed edges), and a
   cross-reference to V2.8 and V2.61. Add COMMENT ON TABLE and COMMENT ON COLUMN for every
   non-obvious column, each a full sentence carrying its rationale.
2. Write V2.101__remix_permissions.sql: ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS for
   CREATE_REMIX, READ_REMIX, UPDATE_REMIX, DELETE_REMIX. Nothing else may go in this file - a
   value added by ALTER TYPE cannot be used in the same transaction. Copy the explanatory comment
   from V2.62 / V2.96.
3. Write V2.102__grant_remix_permissions.sql granting the four to 'admin-role' via
   INSERT ... SELECT ... ON CONFLICT DO NOTHING, modelled on V2.98.
4. Run the ritual in order, or the pool keeps the old schema while still printing "Pool Created":
     mvn -o install -pl loom/db/flyway
     loom/db/jooq/generate.sh
     ./setup-pool.sh
   Codegen adds JooqRemix / JooqRemixMember plus records (both UpdatableRecordImpl - they have
   PKs, unlike JooqAssetRemixRecord which was a TableRecordImpl) and drops JooqAssetRemix, Keys
   and Indexes entries. The `meta` column picks up JsonObjectConverter automatically via the
   forcedTypes includeExpression `.*\.meta.*` in loom/db/jooq/pom.xml - verify it did.
5. JooqLoomPermission is hand-written in this repository despite living under src/jooq - add the
   four new values there by hand.
6. Retarget loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/integrity/check/:
   - AuditedTables.java:35 - replace "asset_remix" with "remix" and "remix_member".
   - AuditedTables.java:73-75 - hasUuid() exists only to exempt asset_remix. Both new tables have
     a uuid, so the exception is gone; simplify the method (or remove it and its call sites in
     TimestampEditedBeforeCreatedCheck / TimestampImplausibleCheck). Do not leave it naming a
     dropped table.
   - DanglingUserReferenceCheck.java:63-73 - delete assetRemixEditor() and add ordinary uuid-keyed
     creator/editor checks for remix and remix_member.
   - loom/db/api/.../db/integrity/DbIntegrityCodes.java:31-32 - retire
     DANGLING_ASSET_REMIX_EDITOR, add DANGLING_REMIX_ACTOR and DANGLING_REMIX_MEMBER_ACTOR.
   Note: AuditedTables' javadoc claims DbIntegrityChecksTest asserts the list against the live
   schema. That claim is stale - no test references the class. Verify by hand rather than trusting
   a guard that is not there.
```

**References:** Section 3 of this file · `V2.8__add_asset.sql:65-80` · `V2.61__add_dedup_group.sql`
(shape) · `V2.97__add_share.sql` (comment and constraint-naming style) · `V2.62`/`V2.96` (enum-only
migration) · `V2.98` (grant migration) · [../guidelines/CODING.md](../guidelines/CODING.md) §DAO ·
[../features/db/DB_INTEGRITY.md](../features/db/DB_INTEGRITY.md)

**Test Requirements:** `mvn -o test -pl loom/db/jooq -Dtest=DbIntegrityChecksTest` green.
`./setup-pool.sh` completes and the pooled databases actually contain `remix` and `remix_member` -
check, do not assume, since a stale `loom/db/flyway` jar makes the pool skip new migrations silently.
`mvn -o test-compile -q -DskipTests` clean across `loom/db`.

---

## Task 2: Remix DAO stack

**Argumentation Summary:** After Task 1 the tables exist with generated jOOQ classes but no model,
DAO, POJO or `DaoCollection` registration - the same unreachable state `asset_remix` was in, and the
state `vector_config` is still in. Nothing above the database can read or write a remix.

**Improvement Summary:** A full DAO stack for `remix` with the membership half mirrored from
`CollectionDao`, which is the closest existing asset-membership API.

```
Follow PERSISTENCE.md §"Adding a New Entity" steps 2-9; the migration already exists after Task 1.

1. loom/db/api/src/main/java/io/metaloom/loom/db/model/remix/:
   - RemixRole enum: SOURCE, DERIVED.
   - Remix extends CUDElement<Remix>, MetaElement<Remix> - name, description, sourceAssetUuid.
   - RemixMember - the join-carrying view: remixUuid, assetUuid, role, ordinal, plus the asset
     fields a card needs. Model it on AssetTag in .../model/tag/AssetTag.java, which is the
     established shape for "join row plus the fields of the thing it points at".
   - RemixDao extends CRUDDao<Remix>:
       Remix createRemix(UUID creatorUuid, String name);
       default void link(Remix, Asset, RemixRole);  void linkAsset(UUID remixUuid, UUID assetUuid, RemixRole);
       default void unlink(Remix, Asset);           void unlinkAsset(UUID remixUuid, UUID assetUuid);
       boolean containsAsset(UUID remixUuid, UUID assetUuid);
       Page<RemixMember> loadMembers(UUID remixUuid, UUID fromId, int pageSize);
       Page<Remix> loadPageByAsset(UUID assetUuid, UUID fromId, int pageSize);
       long countAssets(UUID remixUuid);
       void setSource(UUID remixUuid, UUID assetUuid);
     setSource must, in one transaction, demote any existing SOURCE member to DERIVED, promote the
     named member, and update remix.source_asset_uuid. Javadoc every method in the TagDao style.
2. loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/remix/: RemixImpl, RemixMemberImpl,
   RemixDaoImpl (@Singleton, @Inject DSLContext, extends AbstractJooqDao<Remix>, overriding
   getTypeName/getTable/getPojoClass). Copy CollectionDaoImpl.java:57-93 for the link/unlink/
   fetchExists/fetchCount/join-page bodies. linkAsset is idempotent (ON CONFLICT DO NOTHING on
   remix_member_unique). Guard every public method with DaoUtils.requireUuid(x, "name").
   loadMembers joins REMIX_MEMBER and ASSET; uuid, created, creator_uuid and edited exist on both
   tables, so alias the join-side columns the way TagDaoImpl.assetTags() does or fetchInto picks
   the wrong ones.
3. Register in exactly four places:
   - loom/db/api/.../db/dagger/DaoCollection.java - import plus accessor, under a section comment.
   - loom/db/api/.../db/dagger/DaoCollectionImpl.java - THREE edits: Lazy<RemixDao> field, a
     parameter in the @Inject constructor plus its assignment, and the @Override accessor.
   - loom/db/api/.../db/dagger/DaoProvider.java - default RemixDao remixDao() { return daos().remixDao(); }
     This is what gives the DAO tests their accessor.
   - loom/db/jooq/.../db/jooq/dagger/JooqLoomDaoBindModule.java - @Binds abstract RemixDao remixDao(RemixDaoImpl).
4. After the DI change: mvn -o clean install -pl loom/core -DskipTests, then ./setup-pool.sh again.
   Skipping this yields NoSuchMethodError on the generated Dagger factory in every dependent test.
```

**References:** [../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) §Adding a New Entity, §Cross-Table
Operations · `CollectionDao` / `CollectionDaoImpl.java:57-93` (membership template) ·
`TagDaoImpl.assetTags()` (alias handling) · `DetectionDao` (plain CRUD entity template)

**Test Requirements:** Covered by Task 3. This task is done when
`mvn -o test-compile -q -DskipTests` is clean across `loom/db` and `loom/core` and `./setup-pool.sh`
still succeeds.

---

## Task 3: DAO, membership and cascade tests

**Argumentation Summary:** `CODING.md` §DAO requires impl tests plus delete-cascade tests asserting
that only the targeted rows disappear. The `asset_remix` cascade declared in `V2.8` has never been
tested at all, which is precisely the debt recorded at `AssetCascadeTest.java:93-94`.

**Improvement Summary:** Three new test classes plus the extension of `AssetCascadeTest` that
finally discharges the deferral comment.

```
1. loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/RemixDaoTest.java -
   AbstractJooqTest implements CRUDDaoTestcases<RemixDao, Remix>. Vary `name` by the index i in
   createElement(User, int): the paging testcase inserts 1024 rows and a constant would violate
   nothing today but makes the page assertions meaningless. Add meta round-trip tests in the
   AssetDaoTest style (set, update, remove, nested object).
2. RemixMemberDaoTest.java - keep this SEPARATE from RemixDaoTest. A class much over ~20 test
   methods exhausts the pooled-database provider; TagPlacementDaoTest.java:38 documents exactly
   this split. Cover: linkAsset is idempotent, unlinkAsset, loadMembers ordering, loadPageByAsset
   from the asset side, countAssets, containsAsset, setSource demotes the previous SOURCE and
   updates remix.source_asset_uuid in the same transaction, and that a second SOURCE cannot be
   created by two concurrent setSource calls.
3. RemixCascadeTest.java - deleting a remix removes its member rows and leaves every asset intact;
   a second untouched remix with an identical row set survives. Follow ShareCascadeTest and the
   assertLinkRowsGone / assertLinkRowsPresent / assertSharedIntact trio in AssetCascadeTest.
4. Extend AssetCascadeTest:
   - add a remix_member row for the asset in attach(...),
   - assert it is gone in assertLinkRowsGone via
     countFor(REMIX_MEMBER, REMIX_MEMBER.ASSET_UUID, l.asset) with a message naming V2.100,
   - assert the bystander asset's member row survives,
   - assert the remix row itself survives the deletion of its source asset, with
     source_asset_uuid nulled (the ON DELETE SET NULL contract),
   - DELETE the deferral comment at AssetCascadeTest.java:93-94.
```

**References:** `CRUDDaoTestcases` (`loom/db/api-test/.../CRUDDaoTestcases.java`) ·
`CollectionDaoTest.java:87-150` (link/unlink/cascade testcases) · `TagPlacementDaoTest.java:38`
(class-size limit) · `ShareCascadeTest` · `AssetCascadeTest.java:456-538`

**Test Requirements:**
`mvn -o test -pl loom/db/jooq -Dtest='RemixDaoTest,RemixMemberDaoTest,RemixCascadeTest,AssetCascadeTest'`
all green, including the `@AfterEach assertDatabaseIsStillConsistent()` integrity assertion each
test in that package inherits.

---

## Task 4: REST model classes

**Argumentation Summary:** The REST layer has no request or response types for a remix, and the
model tree is where four separate registries pick a new entity up: `Examples`, `LoomModelValidator`,
`LoomModelBuilder` and the AssertJ `Assertions` facade. Missing any of them fails at runtime or
leaves the endpoint untestable.

**Improvement Summary:** The `loom-shared/rest-model` package for remixes plus its four registrations
and the model builder.

```
1. loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/remix/:
   RemixModel, RemixResponse (uuid, name, description, sourceAssetUuid, memberCount, creator,
   editor, created, edited), RemixListResponse, RemixCreateRequest, RemixUpdateRequest,
   RemixMemberModel (assetUuid, role, ordinal, plus filename/mimeType/sha512 so the UI can render a
   card without a second call), RemixMemberListResponse, RemixMemberRequest (a LIST of asset uuids
   plus an optional source uuid - the bulk "combine" call posts one request, not N),
   and RemixExamples.
2. Register: add RemixExamples to the extends list of
   loom-shared/rest-model/.../rest/model/example/Examples.java:66.
3. RemixModelValidator in loom-shared/rest-model/.../rest/validation/ plus its entry in
   LoomModelValidator. Validate: name non-blank, member list non-empty, no duplicate asset uuids,
   at most one SOURCE.
4. RemixModelBuilder in loom/services/rest/.../rest/builder/ plus its entry in LoomModelBuilder.
5. RemixModelAssert in loom-shared/rest-model-test/.../rest/model/assertj/ plus its entry in
   Assertions.java.
6. Fixture uuids in loom-test-env/.../loom/test/data/TestValues.java (REMIX_UUID, ...).
```

**References:** the share commit `e42aa8a9` added the identical set for `share` - use it as the
checklist · [../loom/RESTAPI.md](../loom/RESTAPI.md)

**Test Requirements:** `mvn -o test-compile -q -DskipTests` clean across `loom-shared`; the response
snapshot test comes with Task 6.

---

## Task 5: Endpoints, route registration, permissions, OpenAPI

**Argumentation Summary:** Nothing exposes remixes over HTTP. Route registration in this codebase has
two independent registries (`EndpointModule` and `LoomOpenAPI`) plus a positional argument list, and
each has its own silent failure mode: a param added to `EndpointModule` without an entry in its
`Arrays.asList` drops the endpoint at runtime, and an endpoint absent from `LoomOpenAPI` produces the
"STALE SPEC" class of failure documented in `clients/python/tests/test_parity.py:50`.

**Improvement Summary:** A `/remixes` resource with nested member routes, a `/assets/:uuid/remixes`
sub-route, four new permissions wired through all five places they must appear, and a regenerated
API description.

```
1. loom/services/rest/.../rest/endpoint/impl/RemixEndpoint.java extends AbstractEndpoint,
   basePath() = API_V1_PATH + "/remixes" (plural - CODING.md §REST), register() calls
   secure(basePath() + "*") first. Routes:
     GET    /remixes                          READ_REMIX      (addListRoute)
     POST   /remixes                          CREATE_REMIX
     GET    /remixes/:uuid                    READ_REMIX
     POST   /remixes/:uuid                    UPDATE_REMIX    (update is POST here, not PUT)
     DELETE /remixes/:uuid                    DELETE_REMIX
     GET    /remixes/:uuid/assets             READ_REMIX + READ_ASSET
     POST   /remixes/:uuid/assets             UPDATE_REMIX + READ_ASSET   (add N members)
     DELETE /remixes/:uuid/assets/:assetUuid  UPDATE_REMIX
     POST   /remixes/:uuid/source             UPDATE_REMIX                (set the SOURCE member)
   Register literal segments BEFORE /:uuid or the uuid route swallows them (RESTAPI.md §577).
2. RemixEndpointService extends AbstractCRUDEndpointService<RemixDao, Remix>, every body wrapped
   in checkPerms(lrc, () -> {...}, PERM...) as ShareLinkEndpointService does.
3. GET /assets/:uuid/remixes: no new endpoint class. Constructor-inject RemixEndpointService into
   AssetEndpoint and add the route under basePath() + "/:uuid/remixes", following the
   /assets/:uuid/tags pattern at AssetEndpoint.java:263-286.
4. Registration, both mandatory:
   - EndpointModule.java: import, constructor parameter, AND an entry in the returned
     Arrays.asList(...). The parameter alone silently drops the endpoint.
   - LoomOpenAPI.java: import, endpoints.add(new RemixEndpoint(null, deps, examples)) in
     endpoints(deps), a tagDescriptions() entry, AND one more `null` in the AssetEndpoint
     construction at LoomOpenAPI.java:242, because AssetEndpoint gained a constructor argument.
5. Permissions, five places:
   - loom/db/api/.../db/model/perm/Permission.java - four constants with the mandatory
     `// doc:... ui:... test:...` audit comment.
   - loom-shared/rest-model/.../rest/model/role/RolePermission.java - mirror them. The share
     commit skipped this mirror, so check whether RolePermissionParityTest is ALREADY red before
     starting; a pre-existing failure must not be attributed to this work, and fixing it belongs
     in its own commit.
   - JooqLoomPermission - done in Task 1.
   - loom-ui/src/features/admin/AdminArea.tsx PERMISSION_GROUPS - add a Remix group.
   - loom-ui/src/i18n/locales/en.json and de.json - admin.roles.permission.* descriptions.
6. Regenerate the API description FROM INSIDE loom/doc (its output paths are relative) and stage it
   for the website:
     cd loom/doc && mvn -o -q exec:java -Dexec.mainClass=io.metaloom.loom.doc.ExampleGenerator
     cp src/main/generated/openapi.json ../../website/static/docs/examples/
     cp src/main/generated/openapi.yaml ../../website/static/docs/examples/
7. Clean-rebuild loom/core after the DI changes before running any test.
```

**References:** `ShareLinkEndpoint` / `ShareLinkEndpointService` (newest self-contained endpoint) ·
`AssetEndpoint.java:263-286` (nested sub-resource) · [../loom/RESTAPI.md](../loom/RESTAPI.md) §4, §9 ·
[../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md) ·
[../website/WEBSITE.md](../website/WEBSITE.md) §openapi staging

**Test Requirements:** `mvn -o test -pl loom/services/rest -Dtest='LoomOpenAPITest,RolePermissionParityTest'`
green; `openapi.json` contains all eight new paths.

---

## Task 6: Endpoint tests

**Argumentation Summary:** `CODING.md` §REST: an endpoint without a `*EndpointTest` is unfinished,
and the tests must assert fine-grained permission handling rather than only the admin path.

**Improvement Summary:** A CRUD endpoint test, a nested-route test and per-permission 403 cases, plus
a model-builder snapshot.

```
1. loom/core/src/test/java/io/metaloom/loom/core/endpoint/test/RemixEndpointTest.java extends
   AbstractCRUDEndpointTest - supplies testRead/Create/Update/Delete/ReadPaged plus four 403 cases
   from the abstract createRequest/loadRequest/listRequest/deleteRequest builders. Note there is no
   generic 403 case for update (AbstractCRUDEndpointTest.java:65-68) - write it by hand.
2. RemixMemberEndpointTest extends AbstractEndpointTest, driving the nested routes through the Java
   client the way TagAssetEndpointTest does: add members, list them, remove one, set the source,
   and assert 404 for an unknown remix and 400 for a duplicate member.
3. Permission cases in the ShareLinkEndpointTest.java:114-150 shape - one client per MISSING
   permission expecting 403, then a fully granted client succeeding. Always via loginClientWith(...),
   which grants through a group plus a role: user_permission has PK (user_uuid) and allows exactly
   one direct grant per user.
4. Do NOT redeclare @RegisterExtension LoomCoreTestExtension in these subclasses; configure the
   inherited `loom` field.
5. RemixModelBuilderTest in loom/services/rest/src/test/java/.../rest/builder/ with a
   src/test/resources/model/remix.response snapshot.
```

**References:** `AbstractCRUDEndpointTest` · `AbstractEndpointTest.java:85-106` (`loginClientWith`) ·
`ShareLinkEndpointTest.java:114-150` · `TagAssetEndpointTest` ·
[../guidelines/CODING.md](../guidelines/CODING.md) §REST

**Test Requirements:** `mvn -o test -pl loom/core -Dtest='RemixEndpointTest,RemixMemberEndpointTest'`
and `mvn -o test -pl loom/services/rest -Dtest=RemixModelBuilderTest` green.

---

## Task 7: Java and Python clients

**Argumentation Summary:** `CODING.md` §REST requires `loom-client` and `loom-ui/src/api/` to move
with any route change, and the Python client's parity test is the independent oracle that catches a
route both clients get wrong. Its `EXPECTED_JAVA_METHOD_COUNT` tripwire fails by design when the Java
client grows.

**Improvement Summary:** Remix methods in both clients, models regenerated, tripwire updated.

```
1. loom-client/common/.../method/RemixMethods.java declaring LoomClientRequest<T> methods for all
   eight routes; add RemixMethods to the ClientMethods extends composite; implement in
   loom-client/rest/.../http/impl/LoomHttpClientImpl.java.
   Gotcha: getRequest percent-encodes its argument as a path, so an inlined "?limit=25" 404s -
   query parameters go through the paging helper.
2. clients/python/loom_client/methods/remix.py plus methods/__init__.py; the snake_case names must
   mirror the Java ones exactly or check 1 and 2 of the parity test fail.
3. Regenerate models: cd clients/python && python3 tools/generate_models.py
   (rewrites models/remix.py, models/__init__.py, models/enums.py and tools/coverage.txt -
   review that diff rather than committing it blind).
4. Bump EXPECTED_JAVA_METHOD_COUNT in clients/python/tests/test_parity.py:44 from 324 by the number
   of new distinct method names, and extend the comment above it saying which interface raised it,
   as the share entry does.
5. Task 5 step 6 must have run first: the third parity check validates every path this client builds
   against loom/doc/src/main/generated/openapi.json.
```

**References:** `clients/python/tests/test_parity.py:1-60` · `ShareMethods` / `LoomHttpClientImpl:1093-1130`
· [../guidelines/CODING.md](../guidelines/CODING.md) §REST

**Test Requirements:** `cd clients/python && python3 -m pytest tests/test_parity.py` green (all three
checks).

---

## Task 8: Demo data

**Argumentation Summary:** `CODING.md` §Demo: new features must ship meaningful default demo data in
`DemoDatabaseInitializer`. Without it the demo container shows an empty remix band and every
screenshot in Task 13 has nothing to photograph.

**Improvement Summary:** One seeded demo remix over the existing demo video assets.

```
In loom/core/src/main/java/io/metaloom/loom/core/boot/DemoDatabaseInitializer.java:
1. RemixDao as a final field plus a constructor parameter and assignment.
2. A fixed public constant for the demo remix name/uuid so the docs and e2e specs can quote it,
   following DEMO_SHARE_SLUG_OPEN at :187.
3. seedDemoRemix(admin, videoAssets) called from the main seeding sequence near seedDemoShares
   (:976), creating one remix holding the first demo video as SOURCE plus two further assets as
   DERIVED, with a realistic name and description, and a log.info line naming the result.
```

**References:** `DemoDatabaseInitializer.java:187, :976, :1395-1420` (the share seeding pattern) ·
[../guidelines/CODING.md](../guidelines/CODING.md) §Demo

**Test Requirements:** The demo container boots and `GET /api/v1/remixes` returns the seeded remix
with three members.

---

## Task 9: UI - API layer, remix cards, remix dialog

**Argumentation Summary:** `spec/loom/ui/TASK_UI_ASSETS_MEDIA.md:209-210` records asset-to-asset
browsing as a backend task with no endpoint and no UI type. After Task 5 the backend exists, and the
asset browser is where the feature has to surface: per decision D2 there is no separate view.

**Improvement Summary:** A hand-written API module, a visually distinct remix card in a pinned band
above the asset grid, and a URL-addressable remix dialog.

```
1. loom-ui/src/api/remixes.ts mirroring src/api/collections.ts and assets.ts verbatim: hand-written
   TS interfaces matching the Java response models (there is no OpenAPI codegen in this project),
   the local authHeaders/handleResponse helpers each api file repeats, pagingQuery from
   src/api/paging.ts, and POST rather than PUT for update. Plus src/api/remixes.test.ts.
2. loom-ui/src/features/assets/AssetBrowser.tsx (the real grid; src/Asset/AssetList.tsx is a dead
   stub that just redirects):
   - load remixes with usePagedList(...) alongside the existing asset list,
   - render a pinned remix band above the asset grid at :690, using the same card geometry,
   - branch the AssetCard <Paper sx> border expression at :95-111 for remixes: dashed accent
     border, a member-count badge and a stacked-card motif, so a remix reads as a different kind
     of object at a glance,
   - branch handleClick at :86-92 so a remix opens the dialog instead of navigate(/assets/:id).
3. New loom-ui/src/features/remix/RemixDialog.tsx - prop-driven like ShareDialog.tsx
   ({ open, onClose, remixUuid }), MUI Dialog with the house PaperProps styling used at
   AssetBrowser.tsx:735. Shows member cards with the SOURCE member marked, and supports rename,
   remove member, set source, add members and delete remix. There is no shared dialog wrapper in
   this codebase - copy the styling, do not invent an abstraction.
4. Sync the open state to ?remix=<uuid> with useSearchParams, so a deep link opens the dialog
   directly. This is what makes Task 11's e2e spec and Task 13's screenshot script possible.
5. i18n: a new `remix` namespace in BOTH loom-ui/src/i18n/locales/en.json and de.json.
```

**References:** `AssetBrowser.tsx:82-144, :324-328, :613-629, :690-731` · `ShareDialog.tsx:29-35` ·
`src/api/collections.ts` · `src/hooks/usePagedList.ts:47` · [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md)

**Test Requirements:** Covered by Task 11.

---

## Task 10: UI - creation paths

**Argumentation Summary:** A remix that can only be created through the REST API is not a feature.
`AssetBrowser` already has a select mode with a bulk action bar (Tag, Delete) and a local
`Set<string>` of selected ids, so the selection half of decision D3 is a small addition rather than
new infrastructure. `AssetDetail` already has the overflow menu the second path needs.

**Improvement Summary:** "Combine into remix" in the bulk bar and "Add to remix..." on the asset
detail page, plus remix chips showing membership.

```
1. AssetBrowser.tsx bulk action bar (:613-629): add a MoreVertOutlined overflow menu beside the
   existing Tag and Delete buttons, holding "Combine into remix". It opens a small name dialog and
   then issues POST /remixes followed by one POST /remixes/:uuid/assets carrying every selected
   uuid, then exits selection mode and reloads the remix band.
   Selection state is the existing local Set<string> at :324-328. Keep it local. Only if the
   selection must survive navigation between views does it become a SelectionContext in
   src/context/ alongside the existing eight contexts - and then say so in REMIX.md, because that
   is a different feature (a clipboard) with its own UX questions.
2. AssetDetail.tsx: add "Add to remix..." to the existing overflow Menu at :841-880, opening a
   picker dialog built on MUI Autocomplete over GET /remixes (follow AssigneeSelect in
   TasksView.tsx:110-139) with a "New remix" affordance. There is no existing asset-picker dialog
   in the codebase to copy - this is the first one.
3. AssetDetail.tsx: a remix chip row beside the collection chips at :797-806, fed by
   GET /assets/:uuid/remixes loaded inside the existing Promise.all at :200-270. Clicking a chip
   navigates to /assets?remix=<uuid>, which Task 9 step 4 already resolves to an open dialog.
4. i18n keys for every new label in both locale files.
```

**References:** `AssetBrowser.tsx:324-328, :384-392, :441-484, :613-629` (existing selection and bulk
handlers) · `AssetDetail.tsx:200-270, :797-806, :841-880` · `TasksView.tsx:110-139`

**Test Requirements:** Covered by Task 11.

---

## Task 11: UI tests

**Argumentation Summary:** This project has no RTL/jsdom layer: pure logic is tested with vitest in a
node environment, and anything that renders is tested as a Playwright spec against mocked routes.
Both tiers are needed here, because the API module is logic and the card/dialog/selection behaviour
is not.

**Improvement Summary:** A vitest spec for the API module and a mocked Playwright spec covering the
whole remix interaction.

```
1. loom-ui/src/api/remixes.test.ts - vitest, node env, request shapes and response parsing only.
2. loom-ui/e2e/remix-mocked.spec.ts, cloned from e2e/assets-crud-mocked.spec.ts:
   - register the catch-all page.route("**/api/v1/**", ...) FIRST; Playwright matches the
     most recently registered handler first,
   - match the list route as /\/api\/v1\/remixes(\?|$)/ because the client appends ?limit=,
   - cover: the remix band renders with a visually distinct card; clicking it opens the dialog and
     sets ?remix= in the URL; loading the page with ?remix= opens the dialog directly; select two
     assets, open the bulk overflow menu, "Combine into remix" issues POST /remixes then
     POST /remixes/:uuid/assets with both uuids; removing a member issues the DELETE; the asset
     detail page shows the remix chip.
   - use { exact: true } on role-name queries: a new label is a substring hazard for existing specs.
3. Run the binaries directly - npx hangs in this sandbox:
     cd loom-ui
     ./node_modules/.bin/vitest run src/api/remixes.test.ts
     ./node_modules/.bin/playwright test e2e/remix-mocked.spec.ts
4. Re-run the neighbouring specs (assets-crud-mocked, share-mocked) to confirm the new bulk-bar
   button and nav labels did not break their queries.
```

**References:** `e2e/assets-crud-mocked.spec.ts:16-120` · [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §8
(the two tiers and the route-matching gotchas)

**Test Requirements:** Both commands above green, and no regression in `assets-crud-mocked.spec.ts`.

---

## Task 12: MCP tools

**Argumentation Summary:** The agent can already list collections and read assets but would have no
way to see or build a remix, so any chat about derived versions of a file is blind to the model. Tool
registration has one silent failure mode: a tool added as a constructor parameter of
`MCPToolModule.mcpTools(...)` but not to the returned set is simply absent, and no test asserts a
total tool count.

**Improvement Summary:** Two read tools and one write tool, registered and individually tested.

```
1. loom/services/mcp/src/main/java/io/metaloom/loom/mcp/tool/impl/:
   - ListRemixesTool ("list_remixes", requires READ_REMIX) - modelled on ListCollectionsTool,
     returns name, uuid and member count, with a reference() entry per remix.
   - GetRemixTool ("get_remix", requires READ_REMIX and READ_ASSET) - returns the remix plus its
     members, using mcpResultWithReferences so the chat UI renders asset reference cards.
   - CreateRemixTool ("create_remix", requires CREATE_REMIX) - takes a name and a list of asset
     uuids. This is the only mutating tool of the three; drop it if the write path is not wanted.
   Each is @Singleton with an @Inject DaoCollection constructor, a descriptor() returning
   MCPToolDescriptor.buildInputSchema(...) and the required permission list, and an execute()
   returning Future<JsonObject>.
2. Register in loom/services/mcp/.../mcp/dagger/MCPToolModule.java: import, constructor parameter,
   AND an entry in the returned Arrays.asList. All three edits per tool.
3. loom/services/mcp/src/test/java/io/metaloom/loom/mcp/tool/impl/RemixToolTest.java, following
   PipelineToolTest - nothing guards the tool count, so per-tool tests are the only safety net.
```

**References:** `ListCollectionsTool` / `GetAssetTool` (templates) · `MCPToolModule.java:39-75` ·
[../loom/MCP.md](../loom/MCP.md) §197-200 (how to add a tool), §237-264 and §277-332 (the tool tables
Task 14 updates)

**Test Requirements:** `mvn -o test -pl loom/services/mcp` green; `tools/list` over the running
server includes the new tools for a user holding `READ_REMIX` and omits them for one who does not.

---

## Task 13: Customer documentation and screenshots

**Argumentation Summary:** `CODING.md` §Docs: customer-facing features must be documented under
`website/content/english/docs/` with screenshots embedded, in customer tone, without class names or
spec references.

**Improvement Summary:** A documentation page with four generated screenshots, registered in the two
index pages that route to it.

```
1. website/content/english/docs/loom/remixes/index.adoc - YAML front matter, then `= Remixes`.
   Explain what a remix is (a named group holding an original and everything made from it), how to
   create one from the selection tray, how to open one, how it differs from a collection (a
   collection organises unrelated assets by topic; a remix records that these files are versions of
   the same thing), and what happens to a remix when a member asset is deleted.
   No class names, no package paths, no spec-file references, no ASCII diagrams - SVG if a diagram
   is needed. Embed each image as image::name.png[<descriptive alt>,role=img-fluid].
2. loom-ui/scripts/capture-remix-screenshots.mjs, cloned from capture-share-screenshots.mjs - the
   MOCKED-network flavour, so the pictures are stable and need no demo container. ensureDevServer()
   from scripts/lib/devserver.mjs, OUT_DIR defaulting to the docs directory above so PNGs land next
   to the adoc. Capture: the remix band in the asset grid, the open remix dialog, the selection tray
   with the overflow menu open, and the remix chips on the asset detail page.
   Run with: cd loom-ui && node scripts/capture-remix-screenshots.mjs
3. Register the page: a bullet in website/content/english/docs/loom/_index.adoc, and a Content
   bullet in website/content/english/docs/ui/index.adoc.
4. Add the new tools to the tables in website/content/english/docs/loom/mcp/index.adoc.
5. Verify the site still builds: cd website && ./build.sh
```

**References:** `website/content/english/docs/loom/sharing/index.adoc` (page template) ·
`loom-ui/scripts/capture-share-screenshots.mjs` · [../website/WEBSITE.md](../website/WEBSITE.md) ·
[../guidelines/CODING.md](../guidelines/CODING.md) §Docs

**Test Requirements:** `./build.sh` in `website/` completes; all four PNGs exist next to the adoc and
render in the built page.

---

## Task 14: Specification files

**Argumentation Summary:** `CODING.md` §Spec makes updating the matching spec files part of the
change. Ten files currently describe `asset_remix` as a dead pair table, and one of them
(`PERSISTENCE_TASKS.md` Task 7) prescribes an implementation this plan deliberately does not follow.
Left alone they would send the next agent down the superseded path.

**Improvement Summary:** A new feature spec, plus a sweep of every file that asserts the old model.

```
1. NEW spec/features/remix/REMIX.md, per SPEC_RULES.md: architecture diagram (Mermaid ER), Key
   Classes Reference table, Conventions and Gotchas, "Where do I find ...?" cheat sheet, Test Setup,
   Progress Assessment checkboxes, and the two-line git-HEAD + date footer. No emojis. Move
   Sections 1-3 of this plan file there and leave links behind.
2. Sweep:
   - spec/tasks/PERSISTENCE_TASKS.md:52-77 - mark Task 7 superseded by this file, keep the task
     text per the template rule, and update the Progress Assessment list.
   - spec/loom/DOMAIN.md - the Asset Remix row in section 2, the group-2 entity list at :55, the
     "no DAO" gap list at :45, and the Mermaid nodes at :421 and :441.
   - spec/loom/PERSISTENCE.md - the migration table and the open-gap item at :544.
   - spec/GLOSSAR.md:78 - rewrite the Asset Remix entry.
   - spec/features/db/DB_SCHEMA_FEEDBACK.md:24 and :420 - remove the dead-table row and the
     asset_remix.editor_uuid finding, both resolved by Task 1.
   - spec/features/db/DB_INTEGRITY.md:107, :295, :363 - the retired check code, the
     "hasUuid exception list of size one" note, and the missing-foreign-key backlog line.
   - spec/concept/ASSET_METADATA_WRITE.md:74, :371 (G3), :531 - a human-curated group now exists;
     typed machine lineage remains unmodelled. Do not close G3.
   - spec/loom/RESTAPI.md - endpoint inventory section 4 and gotchas section 9.
   - spec/loom/MCP.md:237-264 and :277-332 - the tool tables.
   - spec/loom/ui/LOOM_UI.md and spec/loom/ui/TASK_UI_ASSETS_MEDIA.md:209-210 - replace
     "asset-to-asset derivation browsing is a backend task" with what shipped.
3. Update the git-HEAD + date footer of every file touched.
```

**References:** [../guidelines/SPEC_RULES.md](../guidelines/SPEC_RULES.md) ·
[../guidelines/CODING.md](../guidelines/CODING.md) §Spec · [TASKS.template.md](TASKS.template.md)

**Test Requirements:** No automated test. Grep for `asset_remix` across `spec/` and the code and
confirm the only remaining hits are historical references inside migration prose.

---

## Progress Assessment

- [x] Task 1 - Schema refactor, codegen, integrity checks
- [x] Task 2 - Remix DAO stack
- [x] Task 3 - DAO, membership and cascade tests
- [x] Task 4 - REST model classes
- [x] Task 5 - Endpoints, route registration, permissions, OpenAPI
- [x] Task 6 - Endpoint tests
- [x] Task 7 - Java and Python clients
- [x] Task 8 - Demo data
- [x] Task 9 - UI: API layer, remix cards, remix dialog
- [x] Task 10 - UI: creation paths
- [x] Task 11 - UI tests
- [x] Task 12 - MCP tools
- [x] Task 13 - Customer documentation and screenshots
- [x] Task 14 - Specification files

## Test Setup

```bash
# after every Flyway change, in this order (install flyway first or the pool keeps the old schema)
mvn -o install -pl loom/db/flyway
loom/db/jooq/generate.sh
./setup-pool.sh

# persistence
mvn -o test -pl loom/db/jooq -Dtest='RemixDaoTest,RemixMemberDaoTest,RemixCascadeTest,AssetCascadeTest,DbIntegrityChecksTest'

# REST (clean-rebuild loom/core after any DI change, or NoSuchMethodError)
mvn -o clean install -pl loom/core -DskipTests
mvn -o test -pl loom/core -Dtest='RemixEndpointTest,RemixMemberEndpointTest'
mvn -o test -pl loom/services/rest -Dtest='RolePermissionParityTest,LoomOpenAPITest,RemixModelBuilderTest'

# clients (regenerate the API description first)
cd loom/doc && mvn -o -q exec:java -Dexec.mainClass=io.metaloom.loom.doc.ExampleGenerator && cd -
cd clients/python && python3 -m pytest tests/test_parity.py && cd -

# MCP
mvn -o test -pl loom/services/mcp

# UI (npx hangs in this environment - call the binaries directly)
cd loom-ui
./node_modules/.bin/vitest run src/api/remixes.test.ts
./node_modules/.bin/playwright test e2e/remix-mocked.spec.ts

# docs
node scripts/capture-remix-screenshots.mjs
cd ../website && ./build.sh
```

End-to-end smoke test: start the demo container, open `/ui/assets`, confirm the seeded demo remix
appears as a distinct card in the pinned band, click it and confirm the dialog opens and the URL
gains `?remix=`, select two assets and use the bulk overflow menu to combine them, then confirm the
new remix comes back from `GET /api/v1/remixes` and from the `list_remixes` MCP tool.

## Conventions and Gotchas

* `CRUDDaoTestcases` builds **1024** elements for its paging test - every entity's unique column must
  vary with `i` or the create loop fails on a constraint violation.
* A DAO test class much over ~20 methods exhausts the pooled-database provider. Split, as
  `TagPlacementDaoTest` was split out of `TagDaoTest`.
* `user_permission` has PK `(user_uuid)`: exactly one direct grant per user. Endpoint tests grant
  through a group plus a role via `loginClientWith(...)`.
* Adding a DAO changes the `DaoCollectionImpl` constructor signature, and adding a sub-service
  changes `AssetEndpoint`'s. Both break `setup-pool.sh` and every dependent test with
  `NoSuchMethodError` until `loom/core` is clean-rebuilt.
* `EndpointModule` needs the endpoint in its returned list, not only as a constructor parameter.
  `LoomOpenAPI` needs it too, plus a matching `null` in the positional `AssetEndpoint` construction.
* `loom-client`'s `getRequest` percent-encodes its argument as a path segment; an inlined `"?x="`
  becomes part of the path and 404s.
* Playwright route handlers match most-recently-registered first, and list clients append `?limit=`,
  so collection matchers must be `/\/api\/v1\/<name>(\?|$)/`.
* `npx` hangs in this environment - run `./node_modules/.bin/vitest` and
  `./node_modules/.bin/playwright` directly.

## Known pre-existing conditions

Not caused by this work; check them before starting so a pre-existing failure is not attributed here.

* `RolePermissionParityTest` may already be red: commit `e42aa8a9` added four `SHARE` permissions to
  `Permission.java` without mirroring them into `RolePermission.java`, `PERMISSION_GROUPS` or the
  i18n files, while marking them `ui:yes doc:yes`.
* `AuditedTables`' javadoc claims `DbIntegrityChecksTest` asserts its list against the live schema.
  No test references the class.
* `PipelineRun*EndpointTest` failures in `loom/core` are a known enum-versus-String assertion bug.

## Where do I find ...?

| Concept | Path |
|---|---|
| Migrations | `loom/db/flyway/src/main/resources/db/migration/` |
| Model + DAO interfaces | `loom/db/api/src/main/java/io/metaloom/loom/db/model/remix/` |
| jOOQ DAO implementations | `loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/remix/` |
| Generated jOOQ tables | `loom/db/jooq/src/jooq/java/io/metaloom/loom/db/jooq/tables/` |
| DAO tests | `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/` |
| Integrity checks | `loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/integrity/check/` |
| DI registration | `DaoCollection`, `DaoCollectionImpl`, `DaoProvider`, `JooqLoomDaoBindModule` |
| REST models | `loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/remix/` |
| Endpoints + services | `loom/services/rest/src/main/java/io/metaloom/loom/rest/{endpoint/impl,service/impl}/` |
| Route registries | `rest/dagger/EndpointModule.java`, `rest/openapi/LoomOpenAPI.java` |
| Endpoint tests | `loom/core/src/test/java/io/metaloom/loom/core/endpoint/test/` |
| Java client | `loom-client/common/.../method/`, `loom-client/rest/.../impl/LoomHttpClientImpl.java` |
| Python client | `clients/python/loom_client/` |
| MCP tools | `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/tool/impl/` |
| UI api modules | `loom-ui/src/api/` |
| UI asset grid | `loom-ui/src/features/assets/AssetBrowser.tsx` |
| UI asset detail | `loom-ui/src/features/assetDetail/AssetDetail.tsx` |
| UI e2e specs | `loom-ui/e2e/` |
| Screenshot scripts | `loom-ui/scripts/` |
| Customer docs | `website/content/english/docs/loom/` |
| Demo data | `loom/core/src/main/java/io/metaloom/loom/core/boot/DemoDatabaseInitializer.java` |

_Git HEAD revision: `e42aa8a9`_
_Last updated: 2026-08-12 (all 14 tasks implemented and verified; see ../features/remix/REMIX.md
for the resulting design and its open follow-ups. Created the same day, superseding
PERSISTENCE_TASKS.md Task 7.)_
