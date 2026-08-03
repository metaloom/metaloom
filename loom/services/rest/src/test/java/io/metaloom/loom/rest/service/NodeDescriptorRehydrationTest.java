package io.metaloom.loom.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.dagger.DaoProvider;
import io.metaloom.loom.db.model.nodes.NodeDescriptorRecord;
import io.metaloom.loom.db.model.nodes.NodeDescriptorRecordDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.nodes.spec.NodeDescriptorSource;
import io.metaloom.loom.nodes.spec.PortSpec;
import io.metaloom.loom.rest.service.impl.NodeRegistrationService;

/**
 * A custom node must stay authorable across a Loom restart with no worker connected.
 *
 * <p>
 * This is the property the whole design rests on, and the easiest one to lose. If contracts lived only
 * in memory, restarting Loom during a worker outage would make every pipeline using a custom node fail
 * to <em>parse</em> — not degrade, not warn, fail — and the operator would be looking at a Loom problem
 * caused by a Cortex outage.
 * </p>
 *
 * <p>
 * The stored round trip through Postgres is covered by {@code NodeDescriptorRecordDaoTest}. What is
 * checked here is the other half: that {@code rehydrate()} turns those rows back into a servable,
 * validatable registry.
 * </p>
 */
public class NodeDescriptorRehydrationTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	public void shouldRestoreAnAnnouncedContractWithNoWorkerConnected() throws Exception {
		NodeDescriptor announced = new NodeDescriptor()
			.setNodeId("acme-nsfw")
			.setName("NSFW Classifier")
			.setIcon("shield")
			.setCategory(NodeCategory.ANALYSIS)
			.setInputPorts(List.of(PortSpec.one("media", ContentTypeRegistry.MEDIA_IMAGE)
				.describedAs("Image", "The image to classify")))
			.setOutputPorts(List.of(PortSpec.one("result", "struct/nsfw")
				.describedAs("Result", "Per-class probabilities")));

		NodeDescriptorRegistry registry = new NodeDescriptorRegistry();
		assertNull(registry.get("acme-nsfw"), "a freshly booted registry knows nothing about custom nodes");

		StubDao dao = new StubDao(record("acme-nsfw", "1.0.0-SNAPSHOT", mapper.writeValueAsString(announced)));
		NodeRegistrationService service = new NodeRegistrationService(registry, true, new StubDaos(dao));

		assertEquals(1, service.rehydrate());

		NodeDescriptor restored = registry.get("acme-nsfw");
		assertNotNull(restored, "the contract must come back without a worker to re-announce it");
		assertEquals(NodeDescriptorSource.ANNOUNCED, registry.sourceOf("acme-nsfw"));
		assertEquals("NSFW Classifier", restored.getName());
		assertEquals("1.0.0-SNAPSHOT", restored.getVersion());

		// The ports are what validation needs. Losing them would let a saved graph reopen with its
		// edges silently dropped, which is worse than failing outright.
		assertEquals("media", restored.getInputPorts().get(0).getId());
		assertEquals("The image to classify", restored.getInputPorts().get(0).getDescription());
		assertEquals("struct/nsfw", restored.getOutputPorts().get(0).getContentType());
		assertNotNull(registry.resolvePorts("acme-nsfw", Map.of()),
			"port resolution is what PortGraphAnalyzer calls; without it a saved graph does not validate");

		// A content type no Loom build has ever heard of survives the restart too.
		assertTrue(registry.contentTypes().stream().anyMatch(type -> "struct/nsfw".equals(type.getId())));
	}

	@Test
	public void shouldLetABuiltInWinAfterTheNodeGraduates() throws Exception {
		NodeDescriptorRegistry registry = new NodeDescriptorRegistry();
		registry.register(new NodeDescriptor().setNodeId("promoted").setName("Built-in").setCategory(NodeCategory.ANALYSIS));

		NodeDescriptor stored = new NodeDescriptor().setNodeId("promoted").setName("Announced Copy")
			.setCategory(NodeCategory.ANALYSIS);
		StubDao dao = new StubDao(record("promoted", "1.0.0", mapper.writeValueAsString(stored)));

		assertEquals(0, new NodeRegistrationService(registry, true, new StubDaos(dao)).rehydrate());

		// Serving the stored copy would mean serving a contract this build's engine no longer
		// implements - the node has since shipped with Loom.
		assertEquals("Built-in", registry.get("promoted").getName());
		assertEquals(NodeDescriptorSource.BUILTIN, registry.sourceOf("promoted"));
	}

	@Test
	public void shouldSkipAnUnparseableRowRatherThanFailTheBoot() {
		NodeDescriptorRegistry registry = new NodeDescriptorRegistry();
		StubDao dao = new StubDao(
			record("broken", "1.0.0", "{ this is not json"),
			record("fine", "1.0.0", "{\"nodeId\":\"fine\",\"name\":\"Fine\",\"category\":\"ANALYSIS\"}"));

		// One corrupt row must not cost every other custom node its contract, and must certainly not
		// stop Loom starting.
		assertEquals(1, new NodeRegistrationService(registry, true, new StubDaos(dao)).rehydrate());
		assertNotNull(registry.get("fine"));
		assertNull(registry.get("broken"));
	}

	@Test
	public void shouldDoNothingWithoutADatabase() {
		// The unit-test construction path: no DAOs, no persistence, no crash.
		assertEquals(0, new NodeRegistrationService(new NodeDescriptorRegistry(), true).rehydrate());
	}

	@Test
	public void shouldReplaceRatherThanAddToTheAnnouncedLayer() throws Exception {
		NodeDescriptorRegistry registry = new NodeDescriptorRegistry();
		registry.putAnnounced(new NodeDescriptor().setNodeId("stale").setName("Stale").setCategory(NodeCategory.ANALYSIS));

		NodeDescriptor stored = new NodeDescriptor().setNodeId("current").setName("Current").setCategory(NodeCategory.ANALYSIS);
		new NodeRegistrationService(registry, true, new StubDaos(new StubDao(
			record("current", "1.0.0", mapper.writeValueAsString(stored))))).rehydrate();

		assertNotNull(registry.get("current"));
		assertNull(registry.get("stale"), "rehydration is the authoritative state, not an addition to it");
	}

	// ── Stubs ────────────────────────────────────────────────────────────────────────────────────

	private static NodeDescriptorRecord record(String nodeId, String version, String json) {
		return new StubRecord(nodeId, version, json);
	}

	/**
	 * Only what {@code rehydrate()} reads is implemented; everything else throws.
	 *
	 * <p>
	 * Deliberately explicit rather than a mock: it documents the exact surface the boot path depends
	 * on, so a future change that starts calling something else fails loudly here instead of quietly
	 * widening what has to work before Loom can start.
	 * </p>
	 */
	private static final class StubDao implements NodeDescriptorRecordDao {

		private final List<NodeDescriptorRecord> records;

		StubDao(NodeDescriptorRecord... records) {
			this.records = new ArrayList<>(List.of(records));
		}

		@Override
		public List<? extends NodeDescriptorRecord> loadAll() {
			return records;
		}

		@Override
		public String getTypeName() {
			return "Stub";
		}

		@Override
		public long count() {
			return records.size();
		}

		@Override
		public void clear() {
			throw new UnsupportedOperationException();
		}

		@Override
		public NodeDescriptorRecord createNodeDescriptor(String nodeId) {
			throw new UnsupportedOperationException();
		}

		@Override
		public NodeDescriptorRecord loadByNodeId(String nodeId) {
			throw new UnsupportedOperationException();
		}

		@Override
		public NodeDescriptorRecord upsertByNodeId(NodeDescriptorRecord record) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean deleteByNodeId(String nodeId) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void replaceClaims(UUID instanceUuid, Map<String, String[]> claims) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Set<UUID> instancesClaiming(String nodeId) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void delete(UUID id) {
			throw new UnsupportedOperationException();
		}

		@Override
		public NodeDescriptorRecord update(NodeDescriptorRecord element) {
			throw new UnsupportedOperationException();
		}

		@Override
		public NodeDescriptorRecord load(UUID id) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void store(NodeDescriptorRecord element) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Page<NodeDescriptorRecord> loadPage(UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
			SortDirection sortDirection) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Stream<? extends NodeDescriptorRecord> findAll() {
			return records.stream();
		}
	}

	/** A {@link DaoCollection} that answers exactly one accessor. */
	private record StubDaos(NodeDescriptorRecordDao dao) implements DaoProvider {

		@Override
		public DaoCollection daos() {
			throw new UnsupportedOperationException("Only nodeDescriptorDao() is available in this stub");
		}

		@Override
		public NodeDescriptorRecordDao nodeDescriptorDao() {
			return dao;
		}
	}

	private static final class StubRecord implements NodeDescriptorRecord {

		private final String nodeId;
		private String version;
		private String descriptor;
		private String bodyHash;
		private String source = "ANNOUNCED";
		private String status = "ACTIVE";
		private Instant firstSeen = Instant.now();
		private Instant lastAnnounced = Instant.now();
		private UUID uuid = UUID.randomUUID();
		private Instant created = Instant.now();
		private Instant edited = Instant.now();
		private UUID creatorUuid;
		private UUID editorUuid;

		StubRecord(String nodeId, String version, String descriptor) {
			this.nodeId = nodeId;
			this.version = version;
			this.descriptor = descriptor;
		}

		@Override
		public String getNodeId() {
			return nodeId;
		}

		@Override
		public NodeDescriptorRecord setNodeId(String nodeId) {
			return this;
		}

		@Override
		public String getVersion() {
			return version;
		}

		@Override
		public NodeDescriptorRecord setVersion(String version) {
			this.version = version;
			return this;
		}

		@Override
		public String getDescriptor() {
			return descriptor;
		}

		@Override
		public NodeDescriptorRecord setDescriptor(String descriptor) {
			this.descriptor = descriptor;
			return this;
		}

		@Override
		public String getBodyHash() {
			return bodyHash;
		}

		@Override
		public NodeDescriptorRecord setBodyHash(String bodyHash) {
			this.bodyHash = bodyHash;
			return this;
		}

		@Override
		public String getSource() {
			return source;
		}

		@Override
		public NodeDescriptorRecord setSource(String source) {
			this.source = source;
			return this;
		}

		@Override
		public String getStatus() {
			return status;
		}

		@Override
		public NodeDescriptorRecord setStatus(String status) {
			this.status = status;
			return this;
		}

		@Override
		public Instant getFirstSeen() {
			return firstSeen;
		}

		@Override
		public NodeDescriptorRecord setFirstSeen(Instant firstSeen) {
			this.firstSeen = firstSeen;
			return this;
		}

		@Override
		public Instant getLastAnnounced() {
			return lastAnnounced;
		}

		@Override
		public NodeDescriptorRecord setLastAnnounced(Instant lastAnnounced) {
			this.lastAnnounced = lastAnnounced;
			return this;
		}

		@Override
		public UUID getUuid() {
			return uuid;
		}

		@Override
		public NodeDescriptorRecord setUuid(UUID uuid) {
			this.uuid = uuid;
			return this;
		}

		@Override
		public Instant getCreated() {
			return created;
		}

		@Override
		public NodeDescriptorRecord setCreated(Instant created) {
			this.created = created;
			return this;
		}

		@Override
		public Instant getEdited() {
			return edited;
		}

		@Override
		public NodeDescriptorRecord setEdited(Instant edited) {
			this.edited = edited;
			return this;
		}

		@Override
		public UUID getCreatorUuid() {
			return creatorUuid;
		}

		@Override
		public NodeDescriptorRecord setCreatorUuid(UUID creatorUuid) {
			this.creatorUuid = creatorUuid;
			return this;
		}

		@Override
		public UUID getEditorUuid() {
			return editorUuid;
		}

		@Override
		public NodeDescriptorRecord setEditorUuid(UUID editorUuid) {
			this.editorUuid = editorUuid;
			return this;
		}

		@Override
		public io.vertx.core.json.JsonObject getMeta() {
			return null;
		}

		@Override
		public NodeDescriptorRecord setMeta(io.vertx.core.json.JsonObject meta) {
			return this;
		}
	}

	@Test
	public void shouldNotHaveRestoredAnythingIntoTheBuiltInLayer() {
		NodeDescriptorRegistry registry = new NodeDescriptorRegistry();
		new NodeRegistrationService(registry, true, new StubDaos(new StubDao(
			record("x", "1.0.0", "{\"nodeId\":\"x\",\"name\":\"X\",\"category\":\"ANALYSIS\"}")))).rehydrate();

		assertFalse(registry.isBuiltin("x"), "a restored contract is ANNOUNCED, never promoted to BUILTIN");
	}
}
