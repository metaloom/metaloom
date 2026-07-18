package io.metaloom.cortex.impl.loom;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.PipelineExecutor;
import io.metaloom.cortex.pipeline.api.PipelineManager;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.PipelineRunContext;
import io.metaloom.cortex.pipeline.core.DefaultPipeline;
import io.metaloom.cortex.pipeline.core.DefaultPipelineManager;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.cortex.pipeline.loader.LoomPipelineLoader;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrder;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderResult;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderStatus;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrderType;
import io.reactivex.rxjava3.core.Flowable;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Covers the work-order dispatch seam between Loom and Cortex: command
 * resolution, each of the four commands, glob-based media selection, and the
 * failure path.
 */
class PipelineWorkOrderHandlerTest {

	@TempDir
	File tempDir;

	private RecordingExecutor executor;
	private PipelineManager manager;
	private CountingPipelineLoader loader;
	private PipelineWorkOrderHandler handler;

	@BeforeEach
	void setUp() {
		executor = new RecordingExecutor();
		manager = new DefaultPipelineManager();
		loader = new CountingPipelineLoader(manager);
		handler = new PipelineWorkOrderHandler(executor, manager, loader, new StubMediaLoader());
	}

	// --- helpers ---

	private static WorkOrder workOrder(JsonObject parameters) {
		return new WorkOrder().setWorkOrderId(UUID.randomUUID()).setParameters(parameters);
	}

	private static WorkOrder command(String command) {
		return workOrder(new JsonObject().put("command", command));
	}

	private static Pipeline pipeline(String name) {
		AbstractPipelineNode source = new AbstractPipelineNode(name + "-source", name + "-source",
				NodeMode.PARALLEL, true, 1) {
			@Override
			public boolean isSource() {
				return true;
			}

			@Override
			public NodeResult process(LoomMedia media, java.util.Map<String, NodeResult> upstreamResults) {
				return NodeResult.success(id(), 0);
			}
		};
		return DefaultPipeline.builder(name).source(source).build();
	}

	private Path writeFile(String name) throws IOException {
		Path path = new File(tempDir, name).toPath();
		Files.writeString(path, "content");
		return path;
	}

	// --- command resolution ---

	@Test
	void testExplicitCommandParameterWins() {
		WorkOrder order = new WorkOrder()
				.setWorkOrderId(UUID.randomUUID())
				.setType(WorkOrderType.FINGERPRINT)
				.setParameters(new JsonObject().put("command", "list-pipelines"));

		WorkOrderResult result = handler.handle(order);

		assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.COMPLETED);
		assertThat(result.getResult().containsKey("pipelineNames"))
				.as("the explicit command ran, not the FINGERPRINT fallback")
				.isTrue();
		assertThat(executor.flushCount()).isZero();
	}

	@Test
	void testWorkOrderTypeFallsBackToACommandWhenNoParameterIsGiven() {
		WorkOrderResult scan = handler.handle(new WorkOrder()
				.setWorkOrderId(UUID.randomUUID())
				.setType(WorkOrderType.FILESYSTEM_SCAN));
		assertThat(scan.getStatus()).isEqualTo(WorkOrderStatus.COMPLETED);
		assertThat(scan.getResult().containsKey("pipelinesLoaded"))
				.as("FILESYSTEM_SCAN maps to reload-pipelines")
				.isTrue();

		WorkOrderResult fingerprint = handler.handle(new WorkOrder()
				.setWorkOrderId(UUID.randomUUID())
				.setType(WorkOrderType.FINGERPRINT));
		assertThat(fingerprint.getStatus()).isEqualTo(WorkOrderStatus.COMPLETED);
		assertThat(fingerprint.getResult().containsKey("flushedSyncEntries"))
				.as("FINGERPRINT maps to flush-sync")
				.isTrue();

		WorkOrderResult run = handler.handle(new WorkOrder()
				.setWorkOrderId(UUID.randomUUID())
				.setType(WorkOrderType.PIPELINE_RUN));
		assertThat(run.getStatus())
				.as("PIPELINE_RUN maps to run-pipeline, which then fails on the absent parameters")
				.isEqualTo(WorkOrderStatus.FAILED);
		assertThat(run.getErrorMessage()).isEqualTo("run-pipeline: missing parameters");
	}

	@Test
	void testBlankCommandParameterFallsBackToTheType() {
		WorkOrder order = new WorkOrder()
				.setWorkOrderId(UUID.randomUUID())
				.setType(WorkOrderType.FINGERPRINT)
				.setParameters(new JsonObject().put("command", "   "));

		WorkOrderResult result = handler.handle(order);

		assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.COMPLETED);
		assertThat(result.getResult().containsKey("flushedSyncEntries")).isTrue();
	}

	@Test
	void testWorkOrderWithNeitherCommandNorTypeFails() {
		WorkOrderResult result = handler.handle(new WorkOrder().setWorkOrderId(UUID.randomUUID()));

		assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.FAILED);
		assertThat(result.getErrorMessage()).isEqualTo("Work order has no type");
	}

	@Test
	void testUnknownCommandFails() {
		WorkOrderResult result = handler.handle(command("defragment-everything"));

		assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.FAILED);
		assertThat(result.getErrorMessage())
				.isEqualTo("Unsupported work-order command: defragment-everything");
	}

	@Test
	void testTheWorkOrderIdIsEchoedOnBothSuccessAndFailure() {
		WorkOrder ok = command("list-pipelines");
		WorkOrder bad = command("nope");

		assertThat(handler.handle(ok).getWorkOrderId()).isEqualTo(ok.getWorkOrderId());
		assertThat(handler.handle(bad).getWorkOrderId()).isEqualTo(bad.getWorkOrderId());
	}

	// --- reload-pipelines ---

	@Test
	void testReloadPipelinesReportsTheLoadedCount() {
		loader.setLoadCount(3);

		WorkOrderResult result = handler.handle(command("reload-pipelines"));

		assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.COMPLETED);
		assertThat(result.getResult().getInteger("pipelinesLoaded")).isEqualTo(3);
		assertThat(loader.invocations()).isEqualTo(1);
	}

	// --- flush-sync ---

	@Test
	void testFlushSyncReportsTheFlushedCount() {
		executor.setFlushResult(17);

		WorkOrderResult result = handler.handle(command("flush-sync"));

		assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.COMPLETED);
		assertThat(result.getResult().getInteger("flushedSyncEntries")).isEqualTo(17);
		assertThat(executor.flushCount()).isEqualTo(1);
	}

	// --- list-pipelines ---

	@Test
	void testListPipelinesOnAnEmptyManager() {
		WorkOrderResult result = handler.handle(command("list-pipelines"));

		assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.COMPLETED);
		assertThat(result.getResult().getInteger("pipelineCount")).isZero();
		assertThat(result.getResult().getJsonArray("pipelineNames")).isEmpty();
	}

	@Test
	void testListPipelinesReportsRegisteredNames() {
		manager.register(pipeline("alpha"));
		manager.register(pipeline("beta"));

		WorkOrderResult result = handler.handle(command("list-pipelines"));

		assertThat(result.getResult().getInteger("pipelineCount")).isEqualTo(2);
		assertThat(result.getResult().getJsonArray("pipelineNames"))
				.containsExactlyInAnyOrder("alpha", "beta");
	}

	// --- run-pipeline ---

	@Test
	void testRunPipelineWithoutParametersFails() {
		WorkOrder order = new WorkOrder()
				.setWorkOrderId(UUID.randomUUID())
				.setType(WorkOrderType.PIPELINE_RUN)
				.setParameters(null);

		WorkOrderResult result = handler.handle(order);

		assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.FAILED);
		assertThat(result.getErrorMessage()).isEqualTo("run-pipeline: missing parameters");
	}

	@Test
	void testRunPipelineWithoutAPipelineNameFails() {
		WorkOrderResult result = handler.handle(workOrder(new JsonObject().put("command", "run-pipeline")));

		assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.FAILED);
		assertThat(result.getErrorMessage()).isEqualTo("run-pipeline: missing 'pipelineName' parameter");
	}

	@Test
	void testRunPipelineWithAnUnregisteredPipelineNameFails() {
		WorkOrderResult result = handler.handle(workOrder(new JsonObject()
				.put("command", "run-pipeline")
				.put("pipelineName", "ghost")));

		assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.FAILED);
		assertThat(result.getErrorMessage())
				.isEqualTo("run-pipeline: no pipeline registered with name 'ghost'");
	}

	@Test
	void testRunPipelineWithNoMatchingMediaDoesNotExecute() {
		manager.register(pipeline("alpha"));

		WorkOrderResult result = handler.handle(workOrder(new JsonObject()
				.put("command", "run-pipeline")
				.put("pipelineName", "alpha")
				.put("pathGlobs", new JsonArray().add(new File(tempDir, "*.nope").getAbsolutePath()))));

		assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.COMPLETED);
		assertThat(result.getResult().getInteger("mediaCount")).isZero();
		assertThat(result.getResult().getString("message")).isEqualTo("no media resolved from selection");
		assertThat(executor.executions()).as("nothing to process, so no execution").isEmpty();
	}

	@Test
	void testRunPipelineExpandsAWildcardGlob() throws IOException {
		manager.register(pipeline("alpha"));
		writeFile("one.mp4");
		writeFile("two.mp4");
		writeFile("ignored.txt");

		WorkOrderResult result = handler.handle(workOrder(new JsonObject()
				.put("command", "run-pipeline")
				.put("pipelineName", "alpha")
				.put("pathGlobs", new JsonArray().add(new File(tempDir, "*.mp4").getAbsolutePath()))));

		assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.COMPLETED);
		assertThat(result.getResult().getInteger("mediaCount")).isEqualTo(2);
		assertThat(result.getResult().getString("pipelineName")).isEqualTo("alpha");
		assertThat(result.getResult().getString("message")).isEqualTo("dispatched 2 media items");
		assertThat(executor.executions()).hasSize(1);
	}

	@Test
	void testRunPipelineAcceptsALiteralPath() throws IOException {
		manager.register(pipeline("alpha"));
		Path file = writeFile("single.mp4");

		WorkOrderResult result = handler.handle(workOrder(new JsonObject()
				.put("command", "run-pipeline")
				.put("pipelineName", "alpha")
				.put("pathGlobs", new JsonArray().add(file.toAbsolutePath().toString()))));

		assertThat(result.getResult().getInteger("mediaCount")).isEqualTo(1);
	}

	@Test
	void testRunPipelineSkipsLiteralPathsThatDoNotExist() {
		manager.register(pipeline("alpha"));

		WorkOrderResult result = handler.handle(workOrder(new JsonObject()
				.put("command", "run-pipeline")
				.put("pipelineName", "alpha")
				.put("pathGlobs", new JsonArray().add(new File(tempDir, "absent.mp4").getAbsolutePath()))));

		assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.COMPLETED);
		assertThat(result.getResult().getInteger("mediaCount")).isZero();
	}

	@Test
	void testRunPipelineIgnoresBlankAndNullGlobs() throws IOException {
		manager.register(pipeline("alpha"));
		writeFile("one.mp4");

		WorkOrderResult result = handler.handle(workOrder(new JsonObject()
				.put("command", "run-pipeline")
				.put("pipelineName", "alpha")
				.put("pathGlobs", new JsonArray()
						.add("")
						.add("   ")
						.add(new File(tempDir, "*.mp4").getAbsolutePath()))));

		assertThat(result.getResult().getInteger("mediaCount")).isEqualTo(1);
	}

	@Test
	void testRunPipelineGlobIsRecursive() throws IOException {
		manager.register(pipeline("alpha"));
		File nested = new File(tempDir, "sub");
		assertThat(nested.mkdirs()).isTrue();
		Files.writeString(new File(nested, "deep.mp4").toPath(), "content");

		WorkOrderResult result = handler.handle(workOrder(new JsonObject()
				.put("command", "run-pipeline")
				.put("pipelineName", "alpha")
				.put("pathGlobs", new JsonArray().add(new File(tempDir, "**/*.mp4").getAbsolutePath()))));

		assertThat(result.getResult().getInteger("mediaCount")).isEqualTo(1);
	}

	@Test
	void testRunPipelineCorrelatesTheRunUuid() throws IOException {
		manager.register(pipeline("alpha"));
		writeFile("one.mp4");
		UUID runUuid = UUID.randomUUID();

		WorkOrderResult result = handler.handle(workOrder(new JsonObject()
				.put("command", "run-pipeline")
				.put("pipelineName", "alpha")
				.put("pipelineRunUuid", runUuid.toString())
				.put("pathGlobs", new JsonArray().add(new File(tempDir, "*.mp4").getAbsolutePath()))));

		assertThat(result.getResult().getString("pipelineRunUuid")).isEqualTo(runUuid.toString());
		assertThat(executor.executions()).hasSize(1);
		assertThat(executor.executions().get(0).runContext().pipelineRunUuid())
				.as("the run id reaches the executor so tracking events can be correlated")
				.isEqualTo(runUuid.toString());
	}

	@Test
	void testRunPipelineWithoutARunUuidStillExecutes() throws IOException {
		manager.register(pipeline("alpha"));
		writeFile("one.mp4");

		WorkOrderResult result = handler.handle(workOrder(new JsonObject()
				.put("command", "run-pipeline")
				.put("pipelineName", "alpha")
				.put("pathGlobs", new JsonArray().add(new File(tempDir, "*.mp4").getAbsolutePath()))));

		assertThat(result.getResult().containsKey("pipelineRunUuid")).isFalse();
		assertThat(executor.executions()).hasSize(1);
		assertThat(executor.executions().get(0).runContext().pipelineRunUuid()).isNull();
	}

	@Test
	void testRunPipelineWithAMalformedRunUuidFails() {
		manager.register(pipeline("alpha"));

		WorkOrderResult result = handler.handle(workOrder(new JsonObject()
				.put("command", "run-pipeline")
				.put("pipelineName", "alpha")
				.put("pipelineRunUuid", "not-a-uuid")));

		assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.FAILED);
		assertThat(result.getErrorMessage()).contains("not-a-uuid");
	}

	/**
	 * UUID-based media selection is advertised in the work-order schema but not
	 * implemented — it resolves nothing rather than failing.
	 */
	@Test
	void testMediaUuidsSelectionResolvesNoMedia() {
		manager.register(pipeline("alpha"));

		WorkOrderResult result = handler.handle(workOrder(new JsonObject()
				.put("command", "run-pipeline")
				.put("pipelineName", "alpha")
				.put("mediaUuids", new JsonArray().add(UUID.randomUUID().toString()))));

		assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.COMPLETED);
		assertThat(result.getResult().getInteger("mediaCount")).isZero();
		assertThat(result.getResult().getString("message")).isEqualTo("no media resolved from selection");
	}

	// --- test doubles ---

	private record Execution(Pipeline pipeline, PipelineRunContext runContext) {
	}

	private static class RecordingExecutor implements PipelineExecutor {

		private final List<Execution> executions = new CopyOnWriteArrayList<>();
		private final AtomicInteger flushCount = new AtomicInteger();
		private volatile int flushResult;

		void setFlushResult(int flushResult) {
			this.flushResult = flushResult;
		}

		List<Execution> executions() {
			return executions;
		}

		int flushCount() {
			return flushCount.get();
		}

		@Override
		public PipelineResult execute(Pipeline pipeline, LoomMedia media) {
			throw new UnsupportedOperationException("the handler uses the reactive overload");
		}

		@Override
		public Flowable<PipelineResult> execute(Pipeline pipeline, Flowable<LoomMedia> media,
				PipelineRunContext runContext) {
			executions.add(new Execution(pipeline, runContext));
			return Flowable.empty();
		}

		@Override
		public int flushSync() {
			flushCount.incrementAndGet();
			return flushResult;
		}

		@Override
		public void shutdown() {
			// NOOP
		}
	}

	/**
	 * {@link LoomPipelineLoader} talks to a Loom server; only the count it
	 * returns matters to the handler.
	 */
	private static class CountingPipelineLoader extends LoomPipelineLoader {

		private final AtomicInteger invocations = new AtomicInteger();
		private volatile int loadCount;

		CountingPipelineLoader(PipelineManager manager) {
			super(null, manager);
		}

		void setLoadCount(int loadCount) {
			this.loadCount = loadCount;
		}

		int invocations() {
			return invocations.get();
		}

		@Override
		public int loadAndRegister() {
			invocations.incrementAndGet();
			return loadCount;
		}
	}

	/**
	 * Wraps resolved paths as {@link StubLoomMedia} rather than building the real
	 * Dagger-provided media component.
	 */
	private static class StubMediaLoader extends LoomMediaLoader {

		StubMediaLoader() {
			super(null);
		}

		@Override
		public LoomMedia load(Path path) {
			return StubLoomMedia.ofFile(path.toFile());
		}
	}
}
