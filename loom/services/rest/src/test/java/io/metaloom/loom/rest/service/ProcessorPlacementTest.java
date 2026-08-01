package io.metaloom.loom.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.rest.model.processor.ProcessorCapability;
import io.metaloom.loom.rest.model.processor.ProcessorState;
import io.metaloom.loom.rest.model.processor.SystemStatusInfo;
import io.metaloom.loom.rest.model.processor.message.ProcessorRegistration;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry.ConnectedProcessor;

/**
 * Where work lands when several workers would all take it.
 *
 * <p>Priority says which machines an operator wants used; live load decides between
 * the ones they treated as interchangeable. Getting the order wrong the other way
 * round - load overruling priority - would quietly ignore the only placement control
 * an operator actually has.</p>
 */
public class ProcessorPlacementTest {

	private ConnectedProcessor register(ProcessorRegistry registry, String nodeId, int priority) {
		ProcessorRegistration registration = new ProcessorRegistration()
			.setNodeId(nodeId)
			.setName(nodeId)
			.setPriority(priority)
			.setCapabilities(Set.of(ProcessorCapability.CPU));
		// No socket: selection is a pure decision over registered metadata.
		registry.register(nodeId, registration, null);
		ConnectedProcessor processor = registry.get(nodeId);
		processor.state = ProcessorState.ONLINE;
		return processor;
	}

	/** Report a load as if it had just arrived over the status socket. */
	private ConnectedProcessor withLoad(ConnectedProcessor processor, Double cpuLoad, Double ioLoad) {
		processor.systemStatus = new SystemStatusInfo().setCpuLoad(cpuLoad).setIoLoad(ioLoad);
		processor.systemStatusAt = Instant.now();
		return processor;
	}

	private String selected(ProcessorRegistry registry) {
		ConnectedProcessor processor = registry.selectProcessor(ProcessorCapability.CPU);
		return processor == null ? null : processor.nodeId;
	}

	@Test
	void testTheLeastLoadedOfEqualWorkersWins() {
		ProcessorRegistry registry = new ProcessorRegistry();
		withLoad(register(registry, "busy", 10), 80.0d, 5.0d);
		withLoad(register(registry, "idle", 10), 10.0d, 5.0d);

		assertEquals("idle", selected(registry));
	}

	@Test
	void testPriorityIsNotOverruledByLoad() {
		ProcessorRegistry registry = new ProcessorRegistry();
		withLoad(register(registry, "preferred", 100), 90.0d, 90.0d);
		withLoad(register(registry, "spare", 1), 1.0d, 1.0d);

		// An operator who set a priority meant it. Load only breaks ties.
		assertEquals("preferred", selected(registry));
	}

	@Test
	void testASaturatedDiskCountsAsLoadedEvenWithAnIdleCpu() {
		ProcessorRegistry registry = new ProcessorRegistry();
		// Its CPU is asleep because every thread is waiting on the disk. Averaging the
		// two would rank this machine as half free; it is not free at all.
		withLoad(register(registry, "io-bound", 10), 2.0d, 99.0d);
		withLoad(register(registry, "working", 10), 40.0d, 40.0d);

		assertEquals("working", selected(registry));
	}

	@Test
	void testAStaleLoadIsNotTrusted() {
		ProcessorRegistry registry = new ProcessorRegistry();
		ConnectedProcessor quiet = withLoad(register(registry, "quiet-but-silent", 10), 1.0d, 1.0d);
		// It said it was idle five minutes ago and has said nothing since - a machine
		// that got busy and stopped reporting looks exactly like this.
		quiet.systemStatusAt = Instant.now().minus(5, ChronoUnit.MINUTES);
		withLoad(register(registry, "recently-moderate", 10), 30.0d, 30.0d);

		assertEquals("recently-moderate", selected(registry));
	}

	@Test
	void testAWorkerThatNeverReportsRanksBetweenIdleAndBusy() {
		ProcessorRegistry registry = new ProcessorRegistry();
		register(registry, "silent", 10);
		withLoad(register(registry, "idle", 10), 5.0d, 5.0d);
		assertEquals("idle", selected(registry), "a proven idle worker beats an unknown one");

		ProcessorRegistry other = new ProcessorRegistry();
		register(other, "silent", 10);
		withLoad(register(other, "busy", 10), 95.0d, 95.0d);
		assertEquals("silent", selected(other), "an unknown worker beats a proven busy one");
	}

	@Test
	void testADrainingWorkerIsNeverGivenWork() {
		ProcessorRegistry registry = new ProcessorRegistry();
		ConnectedProcessor draining = withLoad(register(registry, "draining", 100), 0.0d, 0.0d);
		draining.state = ProcessorState.TERMINATING;
		withLoad(register(registry, "staying", 1), 90.0d, 90.0d);

		// Idle and top priority, but on its way out: work placed here is work the run
		// waits out a lease timeout for.
		assertEquals("staying", selected(registry));
	}

	@Test
	void testADrainingWorkerIsAlsoSkippedForSegments() {
		ProcessorRegistry registry = new ProcessorRegistry();
		ConnectedProcessor draining = register(registry, "draining", 100);
		draining.state = ProcessorState.TERMINATING;

		assertNull(registry.selectProcessorForKinds(ProcessorCapability.CPU, List.of("sha512")));
	}

	@Test
	void testAPausedOrStartingWorkerIsNotACandidate() {
		ProcessorRegistry registry = new ProcessorRegistry();
		register(registry, "paused", 10).state = ProcessorState.PAUSED;
		register(registry, "starting", 10).state = ProcessorState.STARTING;

		assertNull(selected(registry));
	}

	@Test
	void testEqualPriorityAndEqualLoadResolveTheSameWayEveryTime() {
		ProcessorRegistry registry = new ProcessorRegistry();
		withLoad(register(registry, "worker-b", 10), 20.0d, 20.0d);
		withLoad(register(registry, "worker-a", 10), 20.0d, 20.0d);

		// Map iteration order is not a scheduling decision: the same work must not
		// bounce between two indistinguishable workers on repeated dispatch.
		assertEquals("worker-a", selected(registry));
		assertEquals("worker-a", selected(registry));
	}

	@Test
	void testAPartiallyReportedLoadStillCounts() {
		ProcessorRegistry registry = new ProcessorRegistry();
		// A non-Linux worker reports no I/O figure; the CPU figure it does report is
		// still better information than none.
		withLoad(register(registry, "cpu-only-busy", 10), 90.0d, null);
		withLoad(register(registry, "cpu-only-idle", 10), 3.0d, null);

		assertEquals("cpu-only-idle", selected(registry));
	}
}
