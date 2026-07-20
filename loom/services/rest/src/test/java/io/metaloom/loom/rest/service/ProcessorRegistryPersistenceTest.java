package io.metaloom.loom.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.jooq.dao.cortex.CortexInstanceImpl;
import io.metaloom.loom.db.model.cortex.CortexInstance;
import io.metaloom.loom.db.model.cortex.CortexInstanceDao;
import io.metaloom.loom.rest.model.processor.message.ProcessorRegistration;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry.ConnectedProcessor;

/**
 * The durable {@code cortex_instance} record is the OVERRIDE: an administrator-managed
 * restriction persisted against a worker's stable node id must win over whatever the
 * worker re-announces, and survive a reconnect. When there is no persisted record the
 * announced restriction is the DEFAULT and is seeded into a fresh record.
 */
public class ProcessorRegistryPersistenceTest {

	private ProcessorRegistration registration(String nodeId, Set<String> whitelist, Set<String> blacklist) {
		return new ProcessorRegistration()
			.setNodeId(nodeId)
			.setName(nodeId)
			.setPriority(1)
			.setNodeWhitelist(whitelist)
			.setNodeBlacklist(blacklist);
	}

	@Test
	void testReconnectPicksUpPersistedOverride() {
		CortexInstanceDao dao = mock(CortexInstanceDao.class);
		DaoCollection daos = mock(DaoCollection.class);
		when(daos.cortexInstanceDao()).thenReturn(dao);

		// A record already exists carrying an admin-managed whitelist {embedding} that
		// differs from what the worker announces.
		CortexInstance persisted = new CortexInstanceImpl()
			.setNodeId("w1")
			.setNodeWhitelist(Set.of("embedding"))
			.setNodeBlacklist(Set.of("whisper"));
		when(dao.loadByNodeId("w1")).thenReturn(persisted);
		when(dao.upsertByNodeId(any())).thenAnswer(inv -> inv.getArgument(0));

		ProcessorRegistry registry = new ProcessorRegistry(daos);
		// The worker announces {sha512}; the DB override must win.
		registry.register("w1", registration("w1", Set.of("sha512"), Set.of()), null);

		ConnectedProcessor processor = registry.get("w1");
		assertEquals(Set.of("embedding"), processor.nodeWhitelist, "The persisted override whitelist must win over the announced one");
		assertEquals(Set.of("whisper"), processor.nodeBlacklist);
		assertTrue(processor.accepts("embedding"));
		assertFalse(processor.accepts("sha512"), "A kind outside the override whitelist must be refused");
		assertFalse(processor.accepts("whisper"), "A blacklisted kind must be refused");
	}

	@Test
	void testUpdateRestrictionsAppliesToLiveSelection() {
		CortexInstanceDao dao = mock(CortexInstanceDao.class);
		DaoCollection daos = mock(DaoCollection.class);
		when(daos.cortexInstanceDao()).thenReturn(dao);

		// Seed a fresh record on first registration, then let both load and upsert echo state.
		when(dao.loadByNodeId("w3")).thenReturn(null, new CortexInstanceImpl().setNodeId("w3"));
		when(dao.createCortexInstance("w3", "w3")).thenReturn(new CortexInstanceImpl().setNodeId("w3"));
		when(dao.upsertByNodeId(any())).thenAnswer(inv -> inv.getArgument(0));

		ProcessorRegistry registry = new ProcessorRegistry(daos);
		registry.register("w3", registration("w3", Set.of(), Set.of()), null);

		// Admin narrows the worker to {sha256} and forbids {embedding}.
		registry.updateRestrictions("w3", Set.of("sha256"), Set.of("embedding"), null);

		ConnectedProcessor processor = registry.get("w3");
		assertEquals(Set.of("sha256"), processor.nodeWhitelist);
		assertEquals(Set.of("embedding"), processor.nodeBlacklist);
		// The restriction must take effect for live segment selection immediately.
		assertTrue(registry.selectProcessorForKinds(null, java.util.List.of("sha256")) != null,
			"A whitelisted kind must still select the worker");
		assertTrue(registry.selectProcessorForKinds(null, java.util.List.of("embedding")) == null,
			"A blacklisted kind must exclude the worker from selection");
	}

	@Test
	void testFirstRegistrationSeedsFromAnnounced() {
		CortexInstanceDao dao = mock(CortexInstanceDao.class);
		DaoCollection daos = mock(DaoCollection.class);
		when(daos.cortexInstanceDao()).thenReturn(dao);

		// No record yet: the announced restriction is the default and is seeded.
		when(dao.loadByNodeId("w2")).thenReturn(null);
		when(dao.createCortexInstance("w2", "w2")).thenReturn(new CortexInstanceImpl().setNodeId("w2"));
		when(dao.upsertByNodeId(any())).thenAnswer(inv -> inv.getArgument(0));

		ProcessorRegistry registry = new ProcessorRegistry(daos);
		registry.register("w2", registration("w2", Set.of("sha512"), Set.of("whisper")), null);

		ConnectedProcessor processor = registry.get("w2");
		assertEquals(Set.of("sha512"), processor.nodeWhitelist);
		assertEquals(Set.of("whisper"), processor.nodeBlacklist);
	}
}
