package io.metaloom.loom.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.MemoryOptions;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.space.Space;
import io.metaloom.loom.db.model.space.SpaceDao;
import io.metaloom.loom.mcp.model.MCPCallerContext;

public class MemoryScopeResolverTest {

	private static final UUID USER_UUID = UUID.randomUUID();
	private static final UUID GROUP_UUID = UUID.randomUUID();
	private static final UUID SPACE_UUID = UUID.randomUUID();

	private DaoCollection daos;
	private LoomOptions options;
	private MemoryScopeResolver resolver;

	@BeforeEach
	public void setup() {
		daos = mock(DaoCollection.class);

		GroupDao groupDao = mock(GroupDao.class);
		Group group = mock(Group.class);
		when(group.getName()).thenReturn("editors");
		when(groupDao.load(GROUP_UUID)).thenReturn(group);
		when(daos.groupDao()).thenReturn(groupDao);

		SpaceDao spaceDao = mock(SpaceDao.class);
		Space space = mock(Space.class);
		when(space.getName()).thenReturn("Marketing");
		when(spaceDao.load(SPACE_UUID)).thenReturn(space);
		when(daos.spaceDao()).thenReturn(spaceDao);

		options = new LoomOptions().setMemory(new MemoryOptions().setEnabled(true));
		resolver = new MemoryScopeResolver(daos, options);
	}

	@Test
	public void testAnonymousCallerHasNoScopes() {
		assertTrue(resolver.resolve(MCPCallerContext.ANONYMOUS).isEmpty());
		assertTrue(resolver.resolve(null).isEmpty());
	}

	@Test
	public void testUserScopeIsAlwaysPresentAndFirst() {
		List<MemoryScopeRef> scopes = resolver.resolve(ctx(null, Set.of()));
		assertEquals(1, scopes.size());
		assertEquals(MemoryScope.USER, scopes.get(0).scope());
		assertEquals(USER_UUID, scopes.get(0).scopeUuid());
	}

	@Test
	public void testChatWithoutSpaceHasNoSpaceScope() {
		List<MemoryScopeRef> scopes = resolver.resolve(ctx(null, Set.of()));
		assertTrue(scopes.stream().noneMatch(s -> s.scope() == MemoryScope.SPACE));

		// ...and asking for it says what to do instead rather than silently writing to the private scope.
		MemoryException e = assertThrows(MemoryException.class, () -> resolver.select(scopes, MemoryScope.SPACE, null));
		assertTrue(e.getMessage().contains("not associated with a space"));
		assertTrue(e.getMessage().contains("'user'"));
	}

	@Test
	public void testResolvesSpaceAndGroupScopes() {
		List<MemoryScopeRef> scopes = resolver.resolve(ctx(SPACE_UUID, Set.of(GROUP_UUID)));
		assertEquals(3, scopes.size());
		assertEquals("Marketing", resolver.select(scopes, MemoryScope.SPACE, null).label());
		assertEquals("editors", resolver.select(scopes, MemoryScope.GROUP, null).label());
	}

	@Test
	public void testSharedScopesCanBeDisabledEntirely() {
		options.getMemory().setSharedScopesEnabled(false);
		List<MemoryScopeRef> scopes = resolver.resolve(ctx(SPACE_UUID, Set.of(GROUP_UUID)));
		assertEquals(1, scopes.size());
		assertEquals(MemoryScope.USER, scopes.get(0).scope());
	}

	@Test
	public void testUnresolvableGroupIsDropped() {
		UUID unknown = UUID.randomUUID();
		List<MemoryScopeRef> scopes = resolver.resolve(ctx(null, Set.of(unknown)));
		assertEquals(1, scopes.size());
	}

	@Test
	public void testSelectByLabelOrUuid() {
		List<MemoryScopeRef> scopes = resolver.resolve(ctx(SPACE_UUID, Set.of(GROUP_UUID)));
		assertEquals(GROUP_UUID, resolver.select(scopes, MemoryScope.GROUP, "editors").scopeUuid());
		assertEquals(GROUP_UUID, resolver.select(scopes, MemoryScope.GROUP, GROUP_UUID.toString()).scopeUuid());
	}

	@Test
	public void testUnknownScopeRefGivesTheSameMessageAsAnAbsentScope() {
		List<MemoryScopeRef> withGroup = resolver.resolve(ctx(null, Set.of(GROUP_UUID)));
		List<MemoryScopeRef> withoutGroup = resolver.resolve(ctx(null, Set.of()));

		// A group the caller is not in must be indistinguishable from no group scope at all,
		// otherwise the tool becomes an oracle for which groups exist.
		String unknownRef = assertThrows(MemoryException.class, () -> resolver.select(withGroup, MemoryScope.GROUP, "secret-team")).getMessage();
		String noScope = assertThrows(MemoryException.class, () -> resolver.select(withoutGroup, MemoryScope.GROUP, "secret-team")).getMessage();
		assertTrue(unknownRef.startsWith("No 'group' memory scope is available"));
		assertTrue(noScope.startsWith("No 'group' memory scope is available"));
	}

	@Test
	public void testDefaultsToUserScopeWhenNoneRequested() {
		List<MemoryScopeRef> scopes = resolver.resolve(ctx(SPACE_UUID, Set.of(GROUP_UUID)));
		assertEquals(MemoryScope.USER, resolver.select(scopes, null, null).scope());
	}

	private MCPCallerContext ctx(UUID spaceUuid, Set<UUID> groupUuids) {
		return new MCPCallerContext(USER_UUID, "jdoe", groupUuids, spaceUuid, UUID.randomUUID());
	}

}
