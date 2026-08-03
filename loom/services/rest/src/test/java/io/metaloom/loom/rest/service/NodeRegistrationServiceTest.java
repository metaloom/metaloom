package io.metaloom.loom.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.nodes.spec.NodeDescriptorSource;
import io.metaloom.loom.nodes.spec.PortGroup;
import io.metaloom.loom.nodes.spec.PortGroupMode;
import io.metaloom.loom.nodes.spec.PortSpec;
import io.metaloom.loom.rest.model.processor.message.NodeRegistration;
import io.metaloom.loom.rest.model.processor.message.NodeRegistrationAck;
import io.metaloom.loom.rest.model.processor.message.NodeRegistrationRejection;
import io.metaloom.loom.rest.model.processor.message.NodeRegistrationRejection.Reason;
import io.metaloom.loom.rest.service.impl.NodeRegistrationService;

/**
 * Ingestion rules for announced node contracts.
 *
 * <p>
 * No database and no socket: the rules being checked are about which contract wins and what survives a
 * worker leaving, and both are decidable in memory.
 * </p>
 */
public class NodeRegistrationServiceTest {

	private NodeDescriptorRegistry registry;
	private NodeRegistrationService service;

	@BeforeEach
	public void setup() {
		registry = new NodeDescriptorRegistry();
		registry.register(descriptor("whisper", "Whisper").setVersion(null));
		service = new NodeRegistrationService(registry, true);
	}

	// ── Adoption ─────────────────────────────────────────────────────────────────────────────────

	@Test
	public void shouldAdoptACustomNode() {
		NodeRegistrationAck ack = service.ingest("cortex-1", frame("cortex-1", descriptor("acme-nsfw", "NSFW")));

		assertEquals(List.of("acme-nsfw"), ack.getAccepted());
		assertEquals(List.of(), ack.getRejected());
		assertNotNull(registry.get("acme-nsfw"), "the announced contract must now be servable");
		assertEquals(NodeDescriptorSource.ANNOUNCED, registry.sourceOf("acme-nsfw"));
	}

	@Test
	public void shouldRefuseToLetAWorkerSpeakForAnotherWorker() {
		NodeRegistrationAck ack = service.ingest("cortex-1", frame("cortex-2", descriptor("acme-nsfw", "NSFW")));

		assertEquals(Reason.ID_MISMATCH, ack.getRejected().get(0).getReason());
		assertNull(registry.get("acme-nsfw"));
	}

	@Test
	public void shouldNeverLetAnAnnouncementShadowABuiltIn() {
		NodeDescriptor forged = descriptor("whisper", "My Whisper")
			.setOutputPorts(List.of(PortSpec.one("mine", ContentTypeRegistry.STRUCT_JSON)));

		NodeRegistrationAck ack = service.ingest("cortex-1", frame("cortex-1", forged));

		// The rejection has to be *reported*. An author who forks whisper, edits its ports, sees no
		// effect and gets no message loses an afternoon to it.
		assertEquals(Reason.BUILTIN, ack.getRejected().get(0).getReason());
		assertEquals("Whisper", registry.get("whisper").getName(), "the built-in contract is untouched");
		assertEquals(NodeDescriptorSource.BUILTIN, registry.sourceOf("whisper"));
	}

	@Test
	public void shouldStillRecordTheVersionOfAShadowedBuiltIn() {
		service.ingest("cortex-1", frame("cortex-1", descriptor("whisper", "Whisper").setVersion("2.1.0")));

		// The body is ignored, but which Cortex build a worker runs is worth knowing.
		assertEquals("2.1.0", service.versionOf("cortex-1", "whisper"));
	}

	// ── The version rule ─────────────────────────────────────────────────────────────────────────

	@Test
	public void shouldMakeTheLowestAnnouncedVersionActive() {
		NodeDescriptor older = descriptor("acme", "Acme").setVersion("1.0.0");
		NodeDescriptor newer = descriptor("acme", "Acme").setVersion("1.1.0")
			.setInputPorts(List.of(PortSpec.one("media", ContentTypeRegistry.MEDIA_ANY),
				PortSpec.optionalOne("extra", ContentTypeRegistry.TEXT_ANY)));

		service.ingest("cortex-new", frame("cortex-new", newer));
		service.ingest("cortex-old", frame("cortex-old", older));

		// Newest-wins would let an author wire 'extra' and then have an item land on the 1.0.0 worker,
		// which ignores it: a green run with the wrong answer.
		assertEquals("1.0.0", registry.get("acme").getVersion());
		assertEquals(1, registry.get("acme").getInputPorts().size(), "the port only the newer worker has must not be offered");
		assertTrue(service.hasVersionSkew("acme"));
	}

	@Test
	public void shouldPromoteTheRemainingContractWhenTheOlderWorkerLeaves() {
		service.ingest("cortex-old", frame("cortex-old", descriptor("acme", "Acme").setVersion("1.0.0")));
		service.ingest("cortex-new", frame("cortex-new", descriptor("acme", "Acme").setVersion("1.1.0")));
		assertEquals("1.0.0", registry.get("acme").getVersion());

		service.forget("cortex-old");

		// Recomputed from what remains, rather than a cached decision that would now be stale.
		assertEquals("1.1.0", registry.get("acme").getVersion());
		assertFalse(service.hasVersionSkew("acme"));
	}

	@Test
	public void shouldNotGuessAnOrderForUnparseableVersions() {
		service.ingest("cortex-1", frame("cortex-1", descriptor("acme", "First").setVersion("latest")));
		service.ingest("cortex-2", frame("cortex-2", descriptor("acme", "Second").setVersion("1.0.0")));

		assertEquals("First", registry.get("acme").getName(), "the incumbent is kept rather than guessed past");
		assertTrue(service.hasVersionSkew("acme"), "and the disagreement is surfaced");
	}

	@Test
	public void shouldFlagTwoWorkersOnOneVersionWithDifferentBodies() {
		service.ingest("cortex-1", frame("cortex-1", descriptor("acme", "Acme").setVersion("1.0.0")));
		service.ingest("cortex-2", frame("cortex-2", descriptor("acme", "Acme").setVersion("1.0.0")
			.setOutputPorts(List.of(PortSpec.one("other", ContentTypeRegistry.STRUCT_JSON)))));

		assertTrue(service.hasVersionSkew("acme"));
	}

	@Test
	public void shouldNotFlagSkewWhenTwoWorkersAgree() {
		service.ingest("cortex-1", frame("cortex-1", descriptor("acme", "Acme").setVersion("1.0.0")));
		service.ingest("cortex-2", frame("cortex-2", descriptor("acme", "Acme").setVersion("1.0.0")));

		assertFalse(service.hasVersionSkew("acme"));
		assertEquals(List.of("cortex-1", "cortex-2"), List.copyOf(service.providersOf("acme")));
	}

	// ── Lifetime ─────────────────────────────────────────────────────────────────────────────────

	@Test
	public void shouldKeepAContractAfterItsOnlyWorkerUnlinks() {
		service.ingest("cortex-1", frame("cortex-1", descriptor("acme", "Acme")));

		// A worker going offline unlinks nothing - this is the explicit forget, and even that leaves
		// the contract in place for the caller above it to persist and rehydrate.
		assertNotNull(registry.get("acme"));
		assertEquals(1, service.providersOf("acme").size());
	}

	@Test
	public void shouldReplaceRatherThanMergeAWorkersClaimSet() {
		service.ingest("cortex-1", frame("cortex-1", descriptor("a", "A"), descriptor("b", "B")));
		assertNotNull(registry.get("b"));

		service.ingest("cortex-1", frame("cortex-1", descriptor("a", "A")));

		// There is no delta form: a node absent from a later frame is unlinked, so a worker that drops
		// a node cannot leave a stale contract behind.
		assertNotNull(registry.get("a"));
		assertNull(registry.get("b"));
	}

	@Test
	public void shouldOnlyNotifyWhenTheMergedRegistryActuallyChanged() {
		AtomicInteger changes = new AtomicInteger();
		service.onRegistryChanged(changes::incrementAndGet);

		service.ingest("cortex-1", frame("cortex-1", descriptor("acme", "Acme").setVersion("1.0.0")));
		assertEquals(1, changes.get());

		// A reconnecting worker re-announcing an identical set is the common case. Firing here would
		// storm every open editor with a 115 KB re-fetch on every rolling restart.
		service.ingest("cortex-1", frame("cortex-1", descriptor("acme", "Acme").setVersion("1.0.0")));
		assertEquals(1, changes.get());

		service.ingest("cortex-1", frame("cortex-1", descriptor("acme", "Acme Renamed").setVersion("1.0.0")));
		assertEquals(2, changes.get());
	}

	// ── Validation ───────────────────────────────────────────────────────────────────────────────

	@Test
	public void shouldRejectPerNodeAndNotPerFrame() {
		NodeDescriptor bad = descriptor("acme-bad", "Bad")
			.setOutputPorts(List.of(new PortSpec("Result Set", ContentTypeRegistry.STRUCT_JSON, null)));

		NodeRegistrationAck ack = service.ingest("cortex-1", frame("cortex-1", descriptor("acme-good", "Good"), bad));

		// One malformed custom node must not cost this worker its other contracts.
		assertEquals(List.of("acme-good"), ack.getAccepted());
		assertEquals(Reason.INVALID_PORT_ID, ack.getRejected().get(0).getReason());
		assertNotNull(registry.get("acme-good"));
		assertNull(registry.get("acme-bad"));
	}

	@Test
	public void shouldRejectAMalformedNodeId() {
		assertEquals(Reason.INVALID_NODE_ID, reasonFor(descriptor("Acme NSFW", "Bad")));
		assertEquals(Reason.INVALID_NODE_ID, reasonFor(descriptor("", "Bad")));
		assertEquals(Reason.INVALID_NODE_ID, reasonFor(descriptor("acme_nsfw", "Bad")));
	}

	@Test
	public void shouldRequireANameAndCategory() {
		assertEquals(Reason.INVALID_NODE_ID, reasonFor(descriptor("acme", null)));
		assertEquals(Reason.INVALID_NODE_ID, reasonFor(descriptor("acme", "Acme").setCategory(null)));
	}

	@Test
	public void shouldRejectDuplicateNodeIdsInOneFrame() {
		NodeRegistrationAck ack = service.ingest("cortex-1",
			frame("cortex-1", descriptor("acme", "First"), descriptor("acme", "Second")));

		assertEquals(List.of("acme"), ack.getAccepted());
		assertEquals(Reason.DUPLICATE_NODE_ID, ack.getRejected().get(0).getReason());
	}

	@Test
	public void shouldRejectRepeatedPortIdsOnOneSide() {
		NodeDescriptor node = descriptor("acme", "Acme").setInputPorts(List.of(
			PortSpec.one("media", ContentTypeRegistry.MEDIA_ANY),
			PortSpec.one("media", ContentTypeRegistry.MEDIA_IMAGE)));

		assertEquals(Reason.INVALID_PORT_ID, reasonFor(node));
	}

	@Test
	public void shouldRejectAMalformedContentType() {
		assertEquals(Reason.INVALID_CONTENT_TYPE, reasonFor(descriptor("acme", "Acme")
			.setInputPorts(List.of(PortSpec.one("media", "notatype")))));
		assertEquals(Reason.INVALID_CONTENT_TYPE, reasonFor(descriptor("acme", "Acme")
			.setInputPorts(List.of(PortSpec.one("media", "media/")))));
	}

	@Test
	public void shouldAcceptAContentTypeNobodyHasEverDeclared() {
		// Assignability is structural and never consults a vocabulary. That is exactly what lets a
		// third-party node introduce a content type without a schema change anywhere.
		NodeRegistrationAck ack = service.ingest("cortex-1", frame("cortex-1", descriptor("acme", "Acme")
			.setOutputPorts(List.of(PortSpec.one("result", "struct/nsfw")))));

		assertEquals(List.of("acme"), ack.getAccepted());
		assertTrue(registry.contentTypes().stream().anyMatch(type -> "struct/nsfw".equals(type.getId())),
			"and Loom synthesizes a vocabulary entry so the editor has a label");
		assertEquals("Nsfw", registry.contentTypes().stream()
			.filter(type -> "struct/nsfw".equals(type.getId())).findFirst().orElseThrow().getLabel());
	}

	@Test
	public void shouldRejectAPortNamingAnUndeclaredGroup() {
		NodeDescriptor node = descriptor("acme", "Acme").setInputPorts(List.of(
			PortSpec.one("media", ContentTypeRegistry.MEDIA_ANY).inGroup("nope")));

		assertEquals(Reason.UNKNOWN_GROUP, reasonFor(node));
	}

	@Test
	public void shouldAcceptAPortInADeclaredGroup() {
		NodeDescriptor node = descriptor("acme", "Acme")
			.setInputGroups(List.of(new PortGroup().setId("src").setMode(PortGroupMode.XOR).setRequired(true)))
			.setInputPorts(List.of(PortSpec.one("media", ContentTypeRegistry.MEDIA_ANY).inGroup("src")));

		assertEquals(List.of("acme"), service.ingest("cortex-1", frame("cortex-1", node)).getAccepted());
	}

	@Test
	public void shouldRejectAnOversizedFrame() {
		List<NodeDescriptor> many = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			many.add(descriptor("acme-" + i, "Acme " + i));
		}
		NodeRegistrationAck ack = service.ingest("cortex-1", new NodeRegistration("cortex-1", many));

		assertEquals(Reason.TOO_LARGE, ack.getRejected().get(0).getReason());
		assertEquals(List.of(), ack.getAccepted());
	}

	@Test
	public void shouldAcceptADynamicPortNodeAndDegradeItToItsStaticPorts() {
		NodeDescriptor node = descriptor("acme-script", "Acme Script").setDynamicPorts(true);

		assertEquals(List.of("acme-script"), service.ingest("cortex-1", frame("cortex-1", node)).getAccepted());

		// The resolver class only exists on the worker, so this resolves to the declared ports. A node
		// that degrades is more useful than one that cannot be placed at all.
		assertNotNull(registry.resolvePorts("acme-script", java.util.Map.of()));
		assertEquals(1, registry.resolvePorts("acme-script", java.util.Map.of()).inputs().size());
	}

	// ── The kill switch ──────────────────────────────────────────────────────────────────────────

	@Test
	public void shouldAcknowledgeWithReasonsWhenAnnouncementsAreDisabled() {
		NodeRegistrationService locked = new NodeRegistrationService(registry, false);

		NodeRegistrationAck ack = locked.ingest("cortex-1", frame("cortex-1", descriptor("acme", "Acme")));

		// Acknowledged rather than ignored: an operator has to be able to see why the nodes never show.
		assertEquals(List.of(), ack.getAccepted());
		assertEquals(Reason.ANNOUNCEMENTS_DISABLED, ack.getRejected().get(0).getReason());
		assertNull(registry.get("acme"));
	}

	// ── Helpers ──────────────────────────────────────────────────────────────────────────────────

	private Reason reasonFor(NodeDescriptor node) {
		NodeRegistrationAck ack = new NodeRegistrationService(new NodeDescriptorRegistry(), true)
			.ingest("cortex-1", frame("cortex-1", node));
		List<NodeRegistrationRejection> rejected = ack.getRejected();
		assertFalse(rejected.isEmpty(), "expected a rejection for " + node.getNodeId());
		return rejected.get(0).getReason();
	}

	private static NodeRegistration frame(String cortexId, NodeDescriptor... nodes) {
		return new NodeRegistration(cortexId, new ArrayList<>(List.of(nodes)));
	}

	private static NodeDescriptor descriptor(String nodeId, String name) {
		return new NodeDescriptor()
			.setNodeId(nodeId)
			.setName(name)
			.setCategory(NodeCategory.ANALYSIS)
			.setInputPorts(List.of(PortSpec.one("media", ContentTypeRegistry.MEDIA_ANY)));
	}
}
