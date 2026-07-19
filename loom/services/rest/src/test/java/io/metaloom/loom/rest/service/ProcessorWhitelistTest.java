package io.metaloom.loom.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.rest.model.processor.ProcessorCapability;
import io.metaloom.loom.rest.model.processor.ProcessorState;
import io.metaloom.loom.rest.model.processor.message.ProcessorRegistration;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry.ConnectedProcessor;

/**
 * Routing work to workers that will actually take it.
 *
 * <p>The whitelist is what lets a pool be heterogeneous — GPU boxes for embeddings,
 * the one host with the media mount for filesystem sources — instead of every worker
 * having to run every kind of node.</p>
 */
public class ProcessorWhitelistTest {

	private ConnectedProcessor register(ProcessorRegistry registry, String nodeId, int priority, Set<String> kinds) {
		ProcessorRegistration registration = new ProcessorRegistration()
			.setNodeId(nodeId)
			.setName(nodeId)
			.setPriority(priority)
			.setCapabilities(Set.of(ProcessorCapability.CPU))
			.setNodeKinds(kinds);
		// No socket: selection is a pure decision over registered metadata, so it can
		// be tested without a transport.
		registry.register(nodeId, registration, null);
		ConnectedProcessor processor = registry.get(nodeId);
		processor.state = ProcessorState.ONLINE;
		return processor;
	}

	@Test
	void testAWorkerOnlyReceivesKindsItAccepts() {
		ProcessorRegistry registry = new ProcessorRegistry();
		register(registry, "gpu-box", 10, Set.of("embedding", "facedetect"));
		register(registry, "cpu-box", 1, Set.of("sha512"));

		assertEquals("gpu-box", registry.selectProcessor(ProcessorCapability.CPU, "embedding").nodeId);
		// Despite gpu-box having ten times the priority, hashing must not go there.
		assertEquals("cpu-box", registry.selectProcessor(ProcessorCapability.CPU, "sha512").nodeId);
	}

	@Test
	void testAKindNobodyAcceptsSelectsNothing() {
		ProcessorRegistry registry = new ProcessorRegistry();
		register(registry, "cpu-box", 1, Set.of("sha512"));

		// Must be null rather than a wrong worker: the engine settles the node with a
		// clear "no worker available" instead of sending work somewhere it cannot run.
		assertNull(registry.selectProcessor(ProcessorCapability.CPU, "embedding"));
	}

	@Test
	void testAnUnrestrictedWorkerAcceptsEverything() {
		ProcessorRegistry registry = new ProcessorRegistry();
		register(registry, "generalist", 1, null);

		// A worker registered before whitelisting existed declares no kinds. Treating
		// that as "accepts nothing" would silently remove every existing deployment
		// from the pool on upgrade.
		assertNotNull(registry.selectProcessor(ProcessorCapability.CPU, "embedding"));
		assertNotNull(registry.selectProcessor(ProcessorCapability.CPU, "sha512"));
	}

	@Test
	void testAnEmptyWhitelistIsAlsoUnrestricted() {
		ProcessorRegistry registry = new ProcessorRegistry();
		register(registry, "generalist", 1, Set.of());

		assertNotNull(registry.selectProcessor(ProcessorCapability.CPU, "anything"));
	}

	@Test
	void testOfflineWorkersAreNeverSelected() {
		ProcessorRegistry registry = new ProcessorRegistry();
		ConnectedProcessor processor = register(registry, "cpu-box", 1, Set.of("sha512"));
		processor.state = ProcessorState.OFFLINE;

		assertNull(registry.selectProcessor(ProcessorCapability.CPU, "sha512"));
	}

	@Test
	void testPriorityStillDecidesAmongEqualCandidates() {
		ProcessorRegistry registry = new ProcessorRegistry();
		register(registry, "spare", 1, Set.of("sha512"));
		register(registry, "preferred", 100, Set.of("sha512"));

		assertEquals("preferred", registry.selectProcessor(ProcessorCapability.CPU, "sha512").nodeId);
	}

	@Test
	void testSelectionWithoutAKindIgnoresWhitelists() {
		ProcessorRegistry registry = new ProcessorRegistry();
		register(registry, "restricted", 1, Set.of("sha512"));

		// The work-order path has no node kind and must keep working unchanged.
		assertNotNull(registry.selectProcessor(ProcessorCapability.CPU));
	}

	@Test
	void testAcceptsIsTheSingleSourceOfTruth() {
		ProcessorRegistry registry = new ProcessorRegistry();
		ConnectedProcessor restricted = register(registry, "restricted", 1, Set.of("sha512", "md5"));

		assertTrue(restricted.accepts("sha512"));
		assertTrue(restricted.accepts("md5"));
		assertTrue(!restricted.accepts("embedding"));
	}

}
