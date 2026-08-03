package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.tables.JooqNodeDescriptorInstance.NODE_DESCRIPTOR_INSTANCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.cortex.CortexInstance;
import io.metaloom.loom.db.model.nodes.NodeDescriptorRecord;
import io.metaloom.loom.db.model.nodes.NodeDescriptorRecordDao;
import io.metaloom.loom.db.model.user.User;

public class NodeDescriptorRecordDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<NodeDescriptorRecordDao, NodeDescriptorRecord> {

	private static final String DESCRIPTOR_JSON = """
		{"nodeId":"acme-nsfw","kind":"acme-nsfw","name":"NSFW","category":"ANALYSIS",\
		"inputPorts":[{"id":"media","contentType":"media/image","cardinality":"ONE","required":true}],\
		"outputPorts":[],"inputGroups":[],"outputGroups":[],"parameters":[],"events":[]}""";

	@Override
	public NodeDescriptorRecordDao getDao() {
		return nodeDescriptorDao();
	}

	@Override
	public NodeDescriptorRecord createElement(User user, int i) {
		NodeDescriptorRecord record = nodeDescriptorDao().createNodeDescriptor("acme-node-" + i);
		record.setVersion("1.0." + i);
		record.setDescriptor(DESCRIPTOR_JSON);
		record.setBodyHash("hash-" + i);
		return record;
	}

	@Override
	public void assertCreate(NodeDescriptorRecord created) {
		assertEquals("acme-node-0", created.getNodeId());
		assertEquals("1.0.0", created.getVersion());
		assertEquals("ANNOUNCED", created.getSource());
		assertEquals("ACTIVE", created.getStatus());
		assertNotNull(created.getFirstSeen());
		assertNotNull(created.getLastAnnounced());
		// The contract has to survive the jsonb round trip intact - it is what a freshly booted Loom
		// rehydrates the palette from, with no worker connected to re-announce it. Asserted
		// semantically: jsonb is a parsed document, not the text that was handed to it (see below).
		assertTrue(created.getDescriptor().contains("acme-nsfw"), created.getDescriptor());
	}

	/**
	 * {@code jsonb} stores a parsed document, not the bytes it was given: key order and whitespace
	 * both come back changed.
	 *
	 * <p>
	 * Pinned explicitly because it is exactly the assumption someone would otherwise build on. The
	 * body hash is computed in Java from a canonical, key-sorted rendering <em>before</em> the write —
	 * hashing what comes back out instead would make every contract look changed on the first read, on
	 * every worker in the fleet.
	 * </p>
	 */
	@Test
	void testJsonbDoesNotPreserveTheExactText() throws Exception {
		transaction(t -> {
			NodeDescriptorRecord record = nodeDescriptorDao().createNodeDescriptor("jsonb-shape");
			record.setDescriptor(DESCRIPTOR_JSON);
			record.setBodyHash("h");
			nodeDescriptorDao().store(record);
		});

		String stored = nodeDescriptorDao().loadByNodeId("jsonb-shape").getDescriptor();

		assertFalse(DESCRIPTOR_JSON.equals(stored), "jsonb normalizes; do not compare the stored text byte for byte");
		// What must hold is that it still parses back to the same contract.
		com.fasterxml.jackson.databind.JsonNode parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(stored);
		assertEquals("acme-nsfw", parsed.get("nodeId").asText());
		assertEquals(1, parsed.get("inputPorts").size());
		assertEquals("media/image", parsed.get("inputPorts").get(0).get("contentType").asText());
	}

	@Override
	public void updateElement(NodeDescriptorRecord element) {
		element.setVersion("2.0.0");
		element.setStatus("CONFLICTED");
		element.setBodyHash("hash-updated");
	}

	@Override
	public void assertUpdate(NodeDescriptorRecord updated) {
		assertEquals("2.0.0", updated.getVersion());
		assertEquals("CONFLICTED", updated.getStatus());
		assertEquals("hash-updated", updated.getBodyHash());
	}

	@Test
	void testLoadByNodeId() {
		transaction(t -> {
			NodeDescriptorRecord record = createElement(dummyUser(), 7);
			nodeDescriptorDao().store(record);
		});

		NodeDescriptorRecord loaded = nodeDescriptorDao().loadByNodeId("acme-node-7");
		assertNotNull(loaded, "The contract should be loadable by its node type id");
		assertEquals("1.0.7", loaded.getVersion());
		assertTrue(loaded.getDescriptor().contains("acme-nsfw"));
	}

	@Test
	void testUpsertOnReannounceDoesNotDuplicate() {
		transaction(t -> {
			NodeDescriptorRecord first = nodeDescriptorDao().createNodeDescriptor("re-announced");
			first.setVersion("1.0.0");
			first.setDescriptor(DESCRIPTOR_JSON);
			first.setBodyHash("h1");
			nodeDescriptorDao().upsertByNodeId(first);
		});

		long countAfterFirst = nodeDescriptorDao().count();
		java.time.Instant firstSeen = nodeDescriptorDao().loadByNodeId("re-announced").getFirstSeen();

		// Every worker reconnect re-announces the same set. That must rewrite the row, not add one.
		transaction(t -> {
			NodeDescriptorRecord again = nodeDescriptorDao().loadByNodeId("re-announced");
			again.setVersion("1.1.0");
			nodeDescriptorDao().upsertByNodeId(again);
		});

		assertEquals(countAfterFirst, nodeDescriptorDao().count(), "Re-announcing must not create a duplicate row");
		NodeDescriptorRecord reloaded = nodeDescriptorDao().loadByNodeId("re-announced");
		assertEquals("1.1.0", reloaded.getVersion());
		assertEquals(firstSeen, reloaded.getFirstSeen(),
			"first_seen distinguishes a node that has been around for months from one that appeared this morning");
	}

	@Test
	void testReplaceClaimsIsAReplaceNotAMerge() {
		AtomicReference<UUID> instanceUuid = new AtomicReference<>();
		transaction(t -> {
			CortexInstance worker = cortexInstanceDao().createCortexInstance("claims-worker", "w");
			cortexInstanceDao().store(worker);
			instanceUuid.set(worker.getUuid());
		});

		transaction(t -> nodeDescriptorDao().replaceClaims(instanceUuid.get(), Map.of(
			"node-a", new String[] { "1.0.0", "ha" },
			"node-b", new String[] { "1.0.0", "hb" })));

		assertEquals(Set.of(instanceUuid.get()), nodeDescriptorDao().instancesClaiming("node-b"));

		// There is no delta frame on the wire, so a node missing from a later announcement has
		// genuinely gone away and must not leave a stale claim behind.
		transaction(t -> nodeDescriptorDao().replaceClaims(instanceUuid.get(), Map.of(
			"node-a", new String[] { "1.0.0", "ha" })));

		assertEquals(Set.of(instanceUuid.get()), nodeDescriptorDao().instancesClaiming("node-a"));
		assertTrue(nodeDescriptorDao().instancesClaiming("node-b").isEmpty(), "the dropped claim must be gone");
	}

	@Test
	void testClaimsAcceptANullVersion() {
		AtomicReference<UUID> instanceUuid = new AtomicReference<>();
		transaction(t -> {
			CortexInstance worker = cortexInstanceDao().createCortexInstance("unversioned-worker", "w");
			cortexInstanceDao().store(worker);
			instanceUuid.set(worker.getUuid());
		});

		// An unversioned contract is legal - it simply cannot take part in the ordering.
		transaction(t -> nodeDescriptorDao().replaceClaims(instanceUuid.get(), Map.of(
			"node-unversioned", new String[] { null, "h" })));

		assertEquals(1, nodeDescriptorDao().instancesClaiming("node-unversioned").size());
	}

	/**
	 * Deleting a cortex instance cascades its claim rows.
	 *
	 * <p>
	 * The FK is {@code instance_uuid ... ON DELETE CASCADE}, so forgetting a worker cannot leave rows
	 * pointing at an instance that no longer exists. Note what does <em>not</em> cascade: the contract
	 * in {@code node_descriptor} stays. That is the whole design — spec knowledge is durable, worker
	 * presence is live, and a saved pipeline keeps validating after its last worker is gone.
	 * </p>
	 */
	@Test
	void testDeletingAWorkerCascadesItsClaimsButNotTheContract() {
		AtomicReference<UUID> instanceUuid = new AtomicReference<>();
		transaction(t -> {
			CortexInstance worker = cortexInstanceDao().createCortexInstance("doomed-worker", "w");
			cortexInstanceDao().store(worker);
			instanceUuid.set(worker.getUuid());

			NodeDescriptorRecord contract = nodeDescriptorDao().createNodeDescriptor("survivor");
			contract.setDescriptor(DESCRIPTOR_JSON);
			contract.setBodyHash("h");
			nodeDescriptorDao().store(contract);
		});
		transaction(t -> nodeDescriptorDao().replaceClaims(instanceUuid.get(), Map.of(
			"survivor", new String[] { "1.0.0", "h" })));

		assertEquals(1, claimCount(instanceUuid.get()));

		transaction(t -> cortexInstanceDao().delete(instanceUuid.get()));

		assertEquals(0, claimCount(instanceUuid.get()), "claim rows must not dangle after their worker is forgotten");
		assertNotNull(nodeDescriptorDao().loadByNodeId("survivor"),
			"the contract outlives its worker: that is what keeps a saved pipeline openable");
	}

	@Test
	void testDeleteByNodeIdRemovesTheContractAndItsClaims() {
		AtomicReference<UUID> instanceUuid = new AtomicReference<>();
		transaction(t -> {
			CortexInstance worker = cortexInstanceDao().createCortexInstance("admin-delete-worker", "w");
			cortexInstanceDao().store(worker);
			instanceUuid.set(worker.getUuid());

			NodeDescriptorRecord contract = nodeDescriptorDao().createNodeDescriptor("removable");
			contract.setDescriptor(DESCRIPTOR_JSON);
			contract.setBodyHash("h");
			nodeDescriptorDao().store(contract);
		});
		transaction(t -> nodeDescriptorDao().replaceClaims(instanceUuid.get(), Map.of(
			"removable", new String[] { "1.0.0", "h" })));

		AtomicReference<Boolean> deleted = new AtomicReference<>();
		transaction(t -> deleted.set(nodeDescriptorDao().deleteByNodeId("removable")));

		assertTrue(deleted.get());
		assertNull(nodeDescriptorDao().loadByNodeId("removable"));
		assertTrue(nodeDescriptorDao().instancesClaiming("removable").isEmpty());

		AtomicReference<Boolean> secondDelete = new AtomicReference<>();
		transaction(t -> secondDelete.set(nodeDescriptorDao().deleteByNodeId("removable")));
		assertFalse(secondDelete.get(), "deleting an absent contract reports that nothing was removed");
	}

	private int claimCount(UUID instanceUuid) {
		return context.ctx().fetchCount(NODE_DESCRIPTOR_INSTANCE, NODE_DESCRIPTOR_INSTANCE.INSTANCE_UUID.eq(instanceUuid));
	}
}
