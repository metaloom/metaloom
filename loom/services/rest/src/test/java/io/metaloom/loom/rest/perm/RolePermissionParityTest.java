package io.metaloom.loom.rest.perm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.model.role.RolePermission;

/**
 * Guards the REST enum {@link RolePermission} against the database enum {@link Permission}.
 *
 * <p>
 * The two enums are physically separate because <code>loom-rest-model</code> must not depend on <code>loom-db-api</code>, but they describe the very
 * same vocabulary: a role's grants arrive as {@link RolePermission} over REST and are persisted as {@link Permission} rows in
 * <code>role_permission</code>. {@code RoleEndpointService} bridges them by name, so a constant that exists on only one side is either unreachable
 * over REST or a runtime {@code IllegalArgumentException} at persist time.
 * </p>
 *
 * <p>
 * This is the test the task asked for: it fails as soon as the enums drift again. When it fails, mirror the missing constants - do not relax the
 * assertion. {@link Permission} is the source of truth (it carries the per-constant audit comments).
 * </p>
 */
public class RolePermissionParityTest {

	private Set<String> restNames() {
		return Arrays.stream(RolePermission.values()).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private Set<String> dbNames() {
		return Arrays.stream(Permission.values()).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
	}

	@Test
	public void testNoRestOnlyConstants() {
		Set<String> restOnly = new LinkedHashSet<>(restNames());
		restOnly.removeAll(dbNames());
		assertTrue(restOnly.isEmpty(),
			"RolePermission declares constants which Permission does not: " + restOnly
				+ ". Granting one of these over REST would fail when it is mapped onto the loom_permission type."
				+ " Remove them from RolePermission or add them to Permission (and to a Flyway migration).");
	}

	@Test
	public void testNoDbOnlyConstants() {
		Set<String> dbOnly = new LinkedHashSet<>(dbNames());
		dbOnly.removeAll(restNames());
		assertTrue(dbOnly.isEmpty(),
			"Permission declares constants which RolePermission does not: " + dbOnly
				+ ". These permissions cannot be granted to a role over the REST API at all."
				+ " Mirror them into RolePermission.");
	}

	@Test
	public void testConstantCountMatches() {
		assertEquals(Permission.values().length, RolePermission.values().length,
			"The two permission enums must declare the same number of constants");
	}

	/**
	 * Every REST constant must round-trip through the DB enum by name, which is exactly the conversion the endpoint service performs.
	 */
	@Test
	public void testNameRoundTrip() {
		for (RolePermission restPerm : RolePermission.values()) {
			Permission dbPerm = Permission.valueOf(restPerm.name());
			assertEquals(restPerm.name(), dbPerm.name());
			assertEquals(restPerm, RolePermission.valueOf(dbPerm.name()));
		}
	}
}
