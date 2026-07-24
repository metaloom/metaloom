package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.tables.JooqCortexInstanceNodeKind.CORTEX_INSTANCE_NODE_KIND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.cortex.CortexInstance;
import io.metaloom.loom.db.model.cortex.CortexInstanceDao;
import io.metaloom.loom.db.model.user.User;

public class CortexInstanceDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<CortexInstanceDao, CortexInstance> {

	@Override
	public CortexInstanceDao getDao() {
		return cortexInstanceDao();
	}

	@Override
	public CortexInstance createElement(User user, int i) {
		CortexInstance instance = cortexInstanceDao().createCortexInstance("node_" + i, "worker_" + i);
		instance.setHost("10.0.0." + i + ":9090");
		instance.setPriority(i);
		instance.setState("ONLINE");
		instance.setNodeWhitelist(Set.of("sha512", "facedetect"));
		instance.setNodeBlacklist(Set.of("whisper"));
		return instance;
	}

	@Override
	public void assertCreate(CortexInstance created) {
		assertEquals("node_0", created.getNodeId());
		assertEquals("worker_0", created.getName());
		assertEquals("ONLINE", created.getState());
		assertNotNull(created.getFirstRegistered());
		assertNotNull(created.getLastSeen());
		assertEquals(Set.of("sha512", "facedetect"), created.getNodeWhitelist());
		assertEquals(Set.of("whisper"), created.getNodeBlacklist());
	}

	@Override
	public void updateElement(CortexInstance element) {
		element.setName("updated-worker");
		element.setPriority(99);
		element.setState("OFFLINE");
		element.setNodeWhitelist(Set.of("ocr"));
		element.setNodeBlacklist(Set.of());
	}

	@Override
	public void assertUpdate(CortexInstance updated) {
		assertEquals("updated-worker", updated.getName());
		assertEquals(99, updated.getPriority());
		assertEquals("OFFLINE", updated.getState());
		assertEquals(Set.of("ocr"), updated.getNodeWhitelist());
		assertTrue(updated.getNodeBlacklist().isEmpty(), "The blacklist should have been cleared");
	}

	@Test
	void testLoadByNodeId() {
		AtomicReference<CortexInstance> ref = new AtomicReference<>();
		transaction(t -> {
			CortexInstance instance = createElement(dummyUser(), 7);
			cortexInstanceDao().store(instance);
			ref.set(instance);
		});

		CortexInstance loaded = cortexInstanceDao().loadByNodeId("node_7");
		assertNotNull(loaded, "The instance should be loadable by its node id");
		assertEquals(ref.get().getUuid(), loaded.getUuid());
		assertEquals(Set.of("sha512", "facedetect"), loaded.getNodeWhitelist());
		assertEquals(Set.of("whisper"), loaded.getNodeBlacklist());
	}

	@Test
	void testUpsertOnReregisterDoesNotDuplicate() {
		// First registration seeds the record with an announced whitelist.
		transaction(t -> {
			CortexInstance first = cortexInstanceDao().createCortexInstance("worker-x", "x");
			first.setNodeWhitelist(Set.of("sha512"));
			cortexInstanceDao().upsertByNodeId(first);
		});

		long countAfterFirst = cortexInstanceDao().count();

		// Reconnect: load the existing record, refresh identity, upsert again. The same
		// node id must update the same row rather than inserting a duplicate, and the
		// existing whitelist must be preserved.
		transaction(t -> {
			CortexInstance existing = cortexInstanceDao().loadByNodeId("worker-x");
			assertNotNull(existing);
			existing.setName("x2");
			existing.setHost("h2:9090");
			cortexInstanceDao().upsertByNodeId(existing);
		});

		assertEquals(countAfterFirst, cortexInstanceDao().count(), "Re-registering must not create a duplicate row");

		// loadByNodeId uses fetchOne, so a duplicate would surface here as an exception.
		CortexInstance reloaded = cortexInstanceDao().loadByNodeId("worker-x");
		assertNotNull(reloaded);
		assertEquals("x2", reloaded.getName());
		assertEquals("h2:9090", reloaded.getHost());
		assertEquals(Set.of("sha512"), reloaded.getNodeWhitelist(), "The persisted whitelist must survive a reconnect");
	}

	@Test
	void testNodeKindListsRoundTrip() {
		AtomicReference<CortexInstance> ref = new AtomicReference<>();
		transaction(t -> {
			CortexInstance instance = cortexInstanceDao().createCortexInstance("rt-node", "rt");
			instance.setNodeWhitelist(Set.of("a", "b", "c"));
			instance.setNodeBlacklist(Set.of("x", "y"));
			cortexInstanceDao().store(instance);
			ref.set(instance);
		});

		CortexInstance loaded = cortexInstanceDao().load(ref.get().getUuid());
		assertEquals(Set.of("a", "b", "c"), loaded.getNodeWhitelist());
		assertEquals(Set.of("x", "y"), loaded.getNodeBlacklist());
	}

	private int nodeKindCount(CortexInstance instance) {
		return context.ctx().fetchCount(CORTEX_INSTANCE_NODE_KIND, CORTEX_INSTANCE_NODE_KIND.INSTANCE_UUID.eq(instance.getUuid()));
	}

	/**
	 * Deleting a cortex instance cascades its {@code cortex_instance_node_kind} whitelist/blacklist entries (FK
	 * {@code instance_uuid ... ON DELETE CASCADE} from {@code V2.33__add_cortex_instance.sql}); a stranded row would
	 * otherwise dangle with no owning instance.
	 */
	@Test
	void testDeletingInstanceCascadesNodeKinds() {
		AtomicReference<CortexInstance> ref = new AtomicReference<>();
		transaction(t -> {
			CortexInstance instance = cortexInstanceDao().createCortexInstance("cascade-node", "cascade");
			instance.setNodeWhitelist(Set.of("sha512", "facedetect"));
			instance.setNodeBlacklist(Set.of("whisper"));
			cortexInstanceDao().store(instance);
			ref.set(instance);
		});
		CortexInstance instance = ref.get();

		// 2 whitelist + 1 blacklist entries land in the child table.
		assertEquals(3, nodeKindCount(instance), "The node-kind whitelist/blacklist rows should exist before the delete");

		cortexInstanceDao().delete(instance.getUuid());

		assertNull(cortexInstanceDao().load(instance.getUuid()), "The instance row is gone");
		assertNull(cortexInstanceDao().loadByNodeId("cascade-node"), "The instance is no longer loadable by its node id");
		assertEquals(0, nodeKindCount(instance), "The cortex_instance_node_kind rows must have cascaded with the instance");
	}

}
