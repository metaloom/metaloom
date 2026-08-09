package io.metaloom.loom.db.jooq.integrity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.jooq.integrity.check.SearchDocumentEntities;

/**
 * Guards on the registry itself. None of these touch a database.
 *
 * <p>
 * A check that silently stops being registered, or a code that quietly changes spelling, is the
 * failure mode this whole subsystem exists to prevent elsewhere - so it is worth spending a few
 * assertions preventing it here.
 * </p>
 */
public class DbIntegrityChecksTest {

	private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");

	@Test
	public void testRegistryIsNotEmpty() {
		assertFalse(DbIntegrityChecks.all().isEmpty(), "There should be checks registered");
	}

	@Test
	public void testCodesAreUniqueAndWellFormed() {
		Set<String> seen = new HashSet<>();
		for (DbIntegrityCheck check : DbIntegrityChecks.all()) {
			DbIntegrityCheckInfo info = check.info();
			assertTrue(CODE_PATTERN.matcher(info.code()).matches(),
				"Code " + info.code() + " must be SCREAMING_SNAKE_CASE");
			assertTrue(seen.add(info.code()), "Duplicate check code: " + info.code());
		}
	}

	@Test
	public void testEveryCheckDescribesItself() {
		for (DbIntegrityCheck check : DbIntegrityChecks.all()) {
			DbIntegrityCheckInfo info = check.info();
			assertNotNull(info.category(), info.code() + " needs a category");
			assertNotNull(info.severity(), info.code() + " needs a severity");
			assertTrue(info.table() != null && !info.table().isBlank(), info.code() + " needs a table");
			assertTrue(info.description() != null && info.description().length() > 30,
				info.code() + " needs a description that says what a finding means");
		}
	}

	/**
	 * Names are what the admin table and the report list; the code is what a caller branches on.
	 *
	 * <p>
	 * Uniqueness is the assertion that matters. Two checks sharing a name make the catalogue table
	 * read as though one of them is listed twice, and the reader has no way to tell which row is
	 * which without expanding it. The length bound rules out a name that is really an abbreviated
	 * code ({@code "Dangling"}) or a second copy of the description.
	 * </p>
	 */
	@Test
	public void testNamesAreUniqueAndReadable() {
		Set<String> seen = new HashSet<>();
		for (DbIntegrityCheck check : DbIntegrityChecks.all()) {
			DbIntegrityCheckInfo info = check.info();
			String name = info.name();
			assertTrue(name != null && !name.isBlank(), info.code() + " needs a name");
			assertTrue(name.length() >= 8 && name.length() <= 48,
				info.code() + " has a name of " + name.length() + " characters: '" + name
					+ "'. A name is a short label, not a code and not a description.");
			assertFalse(name.equals(name.toUpperCase()),
				info.code() + " has a name that is just the code again: '" + name + "'");
			assertTrue(seen.add(name), "Two checks share the name '" + name + "'");
		}
	}

	/**
	 * The registry and {@code DbIntegrityCodes} must name exactly the same set.
	 *
	 * <p>
	 * The constants are what a test class puts in its ignore list and what a REST client branches on,
	 * so a code that exists in one place and not the other is a silent hole on whichever side is
	 * missing.
	 * </p>
	 */
	@Test
	public void testCodesConstantsMatchTheRegistry() throws Exception {
		Set<String> declared = new TreeSet<>();
		for (Field field : DbIntegrityCodes.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
				declared.add((String) field.get(null));
			}
		}
		Set<String> registered = new TreeSet<>();
		DbIntegrityChecks.all().forEach(c -> registered.add(c.code()));

		assertEquals(declared, registered,
			"Every registered check needs a constant in DbIntegrityCodes and vice versa");
	}

	/**
	 * Every {@link SearchEntityType} must be resolvable to a table.
	 *
	 * <p>
	 * {@code DANGLING_SEARCH_DOCUMENT} works from a hand-written type-to-table map, and a type absent
	 * from it matches no branch and is therefore never checked. Adding an entity type should break
	 * this test rather than quietly shrink the coverage of that check.
	 * </p>
	 */
	@Test
	public void testEverySearchEntityTypeIsMapped() {
		List<String> missing = new ArrayList<>();
		for (SearchEntityType type : SearchEntityType.values()) {
			if (!SearchDocumentEntities.isMapped(type)) {
				missing.add(type.name());
			}
		}
		assertTrue(missing.isEmpty(),
			"SearchDocumentEntities.TABLES does not cover: " + missing
				+ ". A search document of that type would never be checked for dangling.");
	}
}
