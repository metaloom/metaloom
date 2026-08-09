package io.metaloom.loom.db.jooq.integrity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.db.integrity.DbIntegrityCategory;
import io.metaloom.loom.db.integrity.DbIntegrityCheckResult;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.integrity.DbIntegrityReport;
import io.metaloom.loom.db.integrity.DbIntegrityScope;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.user.User;

/**
 * Proves the checks fire.
 *
 * <p>
 * This is the half of the test suite that matters most, and the easiest one to skip. Everything else
 * asserts that a clean database reports nothing - which a check that always returns zero would also
 * satisfy. Each case here breaks the database in one specific way, through raw SQL so the DAO layer
 * cannot refuse, and asserts the matching check notices and names the row.
 * </p>
 */
public class DbIntegrityServiceTest extends AbstractJooqTest {

	/**
	 * Deliberately broken rows are the point of this class, so the {@code @AfterEach} sweeps the other
	 * DAO tests inherit would fail every case here. Each test asserts precisely which check fired
	 * instead.
	 */

	// ── DANGLING ───────────────────────────────────────────────────────────

	@Test
	public void testDanglingTokenEditorIsFound() {
		UUID ghost = UUID.randomUUID();
		User admin = adminUser();
		context.ctx().execute("""
			insert into "token" ("uuid", "name", "token", "created", "creator_uuid", "edited", "editor_uuid")
			values (?, 'broken-token', 'secret', now(), ?, now(), ?)
			""", UUID.randomUUID(), admin.getUuid(), ghost);

		DbIntegrityCheckResult result = expectFinding(DbIntegrityCodes.DANGLING_TOKEN_EDITOR);
		assertEquals(1, result.count());
		assertEquals(1, result.samples().size());
		assertTrue(result.samples().get(0).detail().contains(ghost.toString()),
			"The finding should name the uuid that does not resolve");
	}

	@Test
	public void testDanglingVectorConfigActorIsFound() {
		UUID ghost = UUID.randomUUID();
		context.ctx().execute("""
			insert into "vector_config" ("uuid", "name", "created", "creator_uuid", "edited", "editor_uuid")
			values (?, 'broken-config', now(), ?, now(), ?)
			""", UUID.randomUUID(), ghost, ghost);

		assertEquals(1, expectFinding(DbIntegrityCodes.DANGLING_VECTOR_CONFIG_ACTOR).count());
	}

	@Test
	public void testDanglingSearchDocumentIsFound() {
		// A document for an asset that was never there. The real-world shape of this is a delete that
		// did not fire the cleanup trigger; the effect on the index is identical.
		context.ctx().execute("""
			insert into "search_document" ("entity_type", "entity_uuid", "title", "keywords", "dirty", "synced_at")
			values ('asset', ?, 'ghost', '', true, now())
			""", UUID.randomUUID());

		assertEquals(1, expectFinding(DbIntegrityCodes.DANGLING_SEARCH_DOCUMENT).count());
	}

	@Test
	public void testSoftDeletedUserHoldingATokenIsFound() {
		User victim = createUser("integrity_soft_deleted");
		context.ctx().execute("""
			insert into "token" ("uuid", "name", "token", "created", "creator_uuid", "edited", "editor_uuid")
			values (?, 'still-valid', 'secret', now(), ?, now(), ?)
			""", UUID.randomUUID(), victim.getUuid(), victim.getUuid());
		context.ctx().execute("update \"user\" set \"deleted\" = true where \"uuid\" = ?", victim.getUuid());

		DbIntegrityCheckResult result = expectFinding(DbIntegrityCodes.SOFT_DELETED_USER_HAS_LIVE_WORK);
		assertTrue(result.samples().stream().anyMatch(f -> f.detail().contains("token")),
			"A deleted user's API token still authenticates, which is the finding that matters");
	}

	// ── TIMESTAMP ──────────────────────────────────────────────────────────

	@Test
	public void testEditedBeforeCreatedIsFound() {
		User user = createUser("integrity_inverted_clock");
		context.ctx().execute("update \"user\" set \"edited\" = \"created\" - interval '1 hour' where \"uuid\" = ?",
			user.getUuid());

		DbIntegrityCheckResult result = expectFinding(DbIntegrityCodes.TIMESTAMP_EDITED_BEFORE_CREATED);
		assertEquals(1, result.count());
		assertEquals(user.getUuid(), result.samples().get(0).entityUuid());
	}

	/**
	 * The inverse, and the reason the comparison is strictly {@code <}. A row created and never edited
	 * carries identical timestamps - three DAOs write it that way on purpose - and must not be
	 * reported.
	 */
	@Test
	public void testEqualTimestampsAreNotAFinding() {
		User user = createUser("integrity_equal_clock");
		context.ctx().execute("update \"user\" set \"edited\" = \"created\" where \"uuid\" = ?", user.getUuid());

		assertClean(DbIntegrityCodes.TIMESTAMP_EDITED_BEFORE_CREATED);
	}

	@Test
	public void testImplausibleTimestampIsFound() {
		User user = createUser("integrity_epoch");
		context.ctx().execute("update \"user\" set \"created\" = ?, \"edited\" = ? where \"uuid\" = ?",
			LocalDateTime.of(1970, 1, 1, 0, 0), LocalDateTime.of(1970, 1, 1, 0, 0), user.getUuid());

		assertEquals(1, expectFinding(DbIntegrityCodes.TIMESTAMP_IMPLAUSIBLE).count());
	}

	/** A timezone-sized offset is not corruption, and the tolerance exists so it is not reported. */
	@Test
	public void testTimezoneSizedSkewIsNotAFinding() {
		User user = createUser("integrity_skewed_clock");
		context.ctx().execute(
			"update \"user\" set \"created\" = now() + interval '11 hours',"
				+ " \"edited\" = now() + interval '11 hours' where \"uuid\" = ?",
			user.getUuid());

		assertClean(DbIntegrityCodes.TIMESTAMP_IMPLAUSIBLE);
	}

	// ── MANDATORY_FIELD ────────────────────────────────────────────────────

	@Test
	public void testBlankNameIsFound() {
		User user = createUser("integrity_blank_name");
		context.ctx().execute("update \"user\" set \"username\" = '   ' where \"uuid\" = ?", user.getUuid());

		assertEquals(1, expectFinding(DbIntegrityCodes.BLANK_NAME).count());
	}

	@Test
	public void testMissingTokenNameIsFound() {
		User admin = adminUser();
		context.ctx().execute("""
			insert into "token" ("uuid", "token", "created", "creator_uuid", "edited", "editor_uuid")
			values (?, 'secret-unnamed', now(), ?, now(), ?)
			""", UUID.randomUUID(), admin.getUuid(), admin.getUuid());

		assertEquals(1, expectFinding(DbIntegrityCodes.MISSING_TOKEN_NAME).count());
	}

	// ── VOCABULARY ─────────────────────────────────────────────────────────

	@Test
	public void testInvalidReactionTypeIsFound() {
		User admin = adminUser();
		context.ctx().execute("""
			insert into "reaction" ("uuid", "type", "created", "creator_uuid", "edited", "editor_uuid")
			values (?, 'image/jpeg', now(), ?, now(), ?)
			""", UUID.randomUUID(), admin.getUuid(), admin.getUuid());

		DbIntegrityCheckResult result = expectFinding(DbIntegrityCodes.INVALID_REACTION_TYPE);
		assertTrue(result.samples().get(0).detail().contains("image/jpeg"),
			"The finding should name the value, since that is what a fix has to look for");
	}

	/** Every real {@link ReactionType} passes, so the check is comparing against the right list. */
	@Test
	public void testEveryReactionTypeIsAccepted() {
		User admin = adminUser();
		for (ReactionType type : ReactionType.values()) {
			context.ctx().execute("""
				insert into "reaction" ("uuid", "type", "created", "creator_uuid", "edited", "editor_uuid")
				values (?, ?, now(), ?, now(), ?)
				""", UUID.randomUUID(), type.name(), admin.getUuid(), admin.getUuid());
		}

		assertClean(DbIntegrityCodes.INVALID_REACTION_TYPE);
	}

	@Test
	public void testInvalidSearchEntityTypeIsFound() {
		context.ctx().execute("""
			insert into "search_document" ("entity_type", "entity_uuid", "title", "keywords", "dirty", "synced_at")
			values ('sasquatch', ?, 'unreachable', '', true, now())
			""", UUID.randomUUID());

		assertEquals(1, expectFinding(DbIntegrityCodes.VOCABULARY_SEARCH_DOCUMENT_ENTITY_TYPE).count());
	}

	// ── CARDINALITY ────────────────────────────────────────────────────────

	/**
	 * The fixture template carries no {@code loom} row at all - {@code PoolSetupRunner} seeds
	 * fixtures without booting the server - so two inserts are needed to get past a legitimate
	 * single row. The check reports the surplus, not the total.
	 */
	@Test
	public void testSecondLoomRowIsFound() {
		context.ctx().execute("insert into \"loom\" (\"db_rev\", \"last_used_timestamp\") values ('one', now())");
		assertClean(DbIntegrityCodes.LOOM_SINGLETON);

		context.ctx().execute("insert into \"loom\" (\"db_rev\", \"last_used_timestamp\") values ('two', now())");
		assertEquals(1, expectFinding(DbIntegrityCodes.LOOM_SINGLETON).count());
	}

	/**
	 * The CHECK constraint refuses this insert, which is exactly why the check exists: it is written
	 * for rows that got in while the constraint was not there. Dropping it first is the only honest
	 * way to reproduce that - a backfill, a NOT VALID constraint or a bulk load with triggers off all
	 * arrive at the same place.
	 */
	@Test
	public void testAssetPoolWithoutABackendIsFound() {
		User admin = adminUser();
		context.ctx().execute("alter table \"asset_pool\" drop constraint \"asset_pool_type_check\"");
		context.ctx().execute("""
			insert into "asset_pool" ("uuid", "name", "created", "creator_uuid", "edited", "editor_uuid")
			values (?, 'nowhere', now(), ?, now(), ?)
			""", UUID.randomUUID(), admin.getUuid(), admin.getUuid());

		assertEquals(1, expectFinding(DbIntegrityCodes.XOR_ASSET_POOL_BACKEND).count());
	}

	// ── Service behaviour ──────────────────────────────────────────────────

	@Test
	public void testCatalogListsEveryCheckWithoutQuerying() {
		assertEquals(DbIntegrityChecks.all().size(), dbIntegrity().catalog().size());
		dbIntegrity().catalog().forEach(info -> assertNotNull(info.code()));
	}

	@Test
	public void testScopeNarrowsTheSweep() {
		DbIntegrityReport single = dbIntegrity().check(DbIntegrityScope.of(DbIntegrityCodes.LOOM_SINGLETON));
		assertEquals(1, single.results().size());
		assertEquals(DbIntegrityCodes.LOOM_SINGLETON, single.results().get(0).code());

		DbIntegrityReport byCategory = dbIntegrity()
			.check(DbIntegrityScope.ofCategories(DbIntegrityCategory.CARDINALITY));
		assertFalse(byCategory.results().isEmpty());
		byCategory.results()
			.forEach(r -> assertEquals(DbIntegrityCategory.CARDINALITY, r.check().category()));

		DbIntegrityReport errorsOnly = dbIntegrity().check(DbIntegrityScope.errorsOnly());
		errorsOnly.results().forEach(r -> assertEquals(DbIntegritySeverity.ERROR, r.severity()));
		assertTrue(errorsOnly.results().size() < dbIntegrity().catalog().size(),
			"errorsOnly must actually drop the WARN checks");
	}

	@Test
	public void testSampleLimitIsHonoured() {
		User admin = adminUser();
		for (int i = 0; i < 5; i++) {
			// token.token and (creator_uuid, name) are both UNIQUE, so vary each.
			context.ctx().execute("""
				insert into "token" ("uuid", "name", "token", "created", "creator_uuid", "edited", "editor_uuid")
				values (?, ?, ?, now(), ?, now(), ?)
				""", UUID.randomUUID(), "broken-" + i, "secret-" + i, admin.getUuid(), UUID.randomUUID());
		}

		DbIntegrityReport report = dbIntegrity().check(
			DbIntegrityScope.of(DbIntegrityCodes.DANGLING_TOKEN_EDITOR).withSampleLimit(2));
		DbIntegrityCheckResult result = report.results().get(0);

		assertEquals(5, result.count(), "The count is the whole truth");
		assertEquals(2, result.samples().size(), "The samples are capped");
		assertTrue(report.describe(DbIntegritySeverity.ERROR).contains("and 3 more"),
			"The description should say how much it is not showing");
	}

	// ── helpers ────────────────────────────────────────────────────────────

	private DbIntegrityCheckResult expectFinding(String code) {
		DbIntegrityReport report = dbIntegrity().check(DbIntegrityScope.of(code));
		DbIntegrityCheckResult result = report.result(code)
			.orElseThrow(() -> new AssertionError("Check " + code + " is not registered"));
		assertTrue(result.count() > 0, "Check " + code + " should have found the row this test broke");
		assertFalse(result.samples().isEmpty(), "Check " + code + " found rows but could not name any");
		return result;
	}

	private void assertClean(String code) {
		DbIntegrityReport report = dbIntegrity().check(DbIntegrityScope.of(code));
		DbIntegrityCheckResult result = report.result(code)
			.orElseThrow(() -> new AssertionError("Check " + code + " is not registered"));
		assertTrue(result.isClean(),
			"Check " + code + " should not have fired: " + report.describe(DbIntegritySeverity.INFO));
	}
}
