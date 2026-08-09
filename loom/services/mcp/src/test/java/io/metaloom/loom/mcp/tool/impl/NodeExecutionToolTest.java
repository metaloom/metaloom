package io.metaloom.loom.mcp.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.options.AiOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.NodeExecOptions;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.rest.model.noderun.NodeProbeResponse;
import io.metaloom.loom.rest.model.noderun.NodeRunItemResult;
import io.metaloom.loom.rest.model.noderun.NodeRunResponse;
import io.metaloom.loom.rest.model.noderun.NodeRunStatusResponse;
import io.metaloom.loom.rest.service.impl.NodeRunService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The four ad-hoc execution tools, against a mocked {@link NodeRunService}.
 *
 * <p>
 * The property under test throughout is the one the MCP layer keeps getting wrong: <b>a refusal is a
 * tool result, not a failed future</b>. A failed future reaches the model as a bare JSON-RPC error
 * string it can only report; a result reaches it as text it can act on - narrow the set, pick another
 * node, wait and poll again. Every rejection path below is asserted to succeed.
 * </p>
 */
public class NodeExecutionToolTest {

	private static final UUID USER_UUID = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
	private static final UUID ASSET_UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID RUN_UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

	private NodeRunService service;
	private LoomOptions options;
	private MCPCallerContext ctx;

	@BeforeEach
	public void setup() {
		service = mock(NodeRunService.class);
		options = new LoomOptions();
		ctx = new MCPCallerContext(USER_UUID, "tester", Set.of(), null, null);
	}

	private static String textOf(JsonObject result) {
		return result.getJsonArray("content").getJsonObject(0).getString("text");
	}

	// ── Descriptors ──────────────────────────────────────────────────────

	@Test
	public void testEveryExecutionToolIsIdentityScopedAndGatedOnExecutePermission() {
		List<MCPToolDescriptor> descriptors = List.of(
			new RunNodeProbeTool(service, options).descriptor(),
			new RunNodeGraphTool(service).descriptor(),
			new GetJobTool(service, options).descriptor(),
			new CancelJobTool(service).descriptor());

		for (MCPToolDescriptor descriptor : descriptors) {
			// Identity-scoped tools get no EventBus address, so there is no unauthenticated route
			// through which worker time can be spent.
			assertTrue(descriptor.requiresIdentity(), descriptor.name() + " must require an identity");
			assertThat(descriptor.requiredPermissions())
				.as(descriptor.name() + " must be gated on EXECUTE_MCP_NODE")
				.contains("EXECUTE_MCP_NODE");
		}
	}

	@Test
	public void testTheEventBusOverloadRefusesToRunWithoutACaller() {
		assertTrue(new RunNodeProbeTool(service, options).execute(new JsonObject()).failed());
		assertTrue(new RunNodeGraphTool(service).execute(new JsonObject()).failed());
		assertTrue(new GetJobTool(service, options).execute(new JsonObject()).failed());
		assertTrue(new CancelJobTool(service).execute(new JsonObject()).failed());
	}

	// ── run_node_probe ───────────────────────────────────────────────────

	@Test
	public void testProbeHappyPathCarriesTheOutputIntoTheText() {
		when(service.probe(any(), any())).thenReturn(Future.succeededFuture(new NodeProbeResponse()
			.setState("COMPLETED")
			.setNodeKind("vlm")
			.setAssetUuid(ASSET_UUID)
			.setDurationMs(4120L)
			.setText("beach.jpg\n  vlm: COMPLETED (4120ms)\n    text [text/plain]: A beach at sunset.\n")));

		JsonObject result = new RunNodeProbeTool(service, options).execute(probeArgs("vlm"), ctx).result();

		String text = textOf(result);
		assertThat(text).contains("vlm").contains("COMPLETED").contains("A beach at sunset.");
	}

	@Test
	public void testARejectedProbeIsAReadableResultNotAFailure() {
		when(service.probe(any(), any())).thenReturn(Future.succeededFuture(new NodeProbeResponse()
			.setState(NodeRunService.STATE_REJECTED)
			.setNodeKind("nosuchnode")
			.setMessage("There is no node kind 'nosuchnode'. Use list_nodes to see what is available.")));

		Future<JsonObject> future = new RunNodeProbeTool(service, options).execute(probeArgs("nosuchnode"), ctx);

		assertTrue(future.succeeded(), "a rejection must reach the model as a result it can act on");
		assertThat(textOf(future.result())).contains("nosuchnode").contains("Could not run");
	}

	@Test
	public void testAMissingKindIsRejectedBeforeTheServiceIsTouched() {
		Future<JsonObject> future = new RunNodeProbeTool(service, options)
			.execute(new JsonObject().put("assetUuid", ASSET_UUID.toString()), ctx);

		assertTrue(future.succeeded());
		assertThat(textOf(future.result())).contains("kind");
		verify(service, never()).probe(any(), any());
	}

	@Test
	public void testANonUuidAssetIsRejectedBeforeTheServiceIsTouched() {
		Future<JsonObject> future = new RunNodeProbeTool(service, options)
			.execute(new JsonObject().put("kind", "vlm").put("assetUuid", "not-a-uuid"), ctx);

		assertTrue(future.succeeded());
		assertThat(textOf(future.result())).contains("uuid");
		verify(service, never()).probe(any(), any());
	}

	@Test
	public void testProbeTextIsCapped() {
		options.getNodeExec().setResultMaxChars(120);
		when(service.probe(any(), any())).thenReturn(Future.succeededFuture(new NodeProbeResponse()
			.setState("COMPLETED")
			.setNodeKind("vlm")
			.setText("x".repeat(10_000))));

		String text = textOf(new RunNodeProbeTool(service, options).execute(probeArgs("vlm"), ctx).result());

		assertThat(text.length()).isLessThanOrEqualTo(120);
		// Truncation announced rather than silent: a shortened result that does not say so reads to
		// the model as the whole answer.
		assertThat(text).contains("truncated");
	}

	@Test
	public void testTheProbeBudgetStaysUnderTheToolTimeout() {
		// This single assertion is the whole "a slow node yields a clean tool result, not a transport
		// timeout" contract. A later bump of either default that broke the ordering would otherwise
		// only show up as an unreadable failure in production.
		assertThat(NodeExecOptions.DEFAULT_PROBE_TIMEOUT_MS)
			.as("the probe must finish before the agent loop gives up on the tool call")
			.isLessThan(AiOptions.DEFAULT_TOOL_TIMEOUT_MS);
	}

	// ── run_node_graph ───────────────────────────────────────────────────

	@Test
	public void testGraphRunReturnsAHandleAndAJobCard() {
		when(service.startRun(any(), any())).thenReturn(new NodeRunResponse()
			.setUuid(RUN_UUID)
			.setStatus("RUNNING")
			.setAccepted(12)
			.setRejected(1)
			.setEtaMs(48_000L));

		JsonObject result = new RunNodeGraphTool(service).execute(graphArgs(), ctx).result();

		String text = textOf(result);
		assertThat(text).contains(RUN_UUID.toString()).contains("12 asset").contains("get_job");
		// The counters are repeated in the text because the model never sees the visuals payload.
		assertThat(text).contains("1 asset(s) could not be included");

		JsonObject card = result.getJsonArray("visuals").getJsonObject(0);
		assertEquals("job-card", card.getString("type"));
		assertEquals(RUN_UUID.toString(), card.getString("uuid"));
		assertEquals(12, card.getJsonObject("payload").getInteger("total"));
	}

	@Test
	public void testAQuotaRefusalIsAResultNotAFailure() {
		when(service.startRun(any(), any())).thenThrow(new LoomRestException(429, LoomRestErrorCode.BAD_REQUEST,
			"You already have 3 ad-hoc run(s) in flight, which is the limit."));

		Future<JsonObject> future = new RunNodeGraphTool(service).execute(graphArgs(), ctx);

		assertTrue(future.succeeded(), "a quota refusal is something the model can work around");
		assertThat(textOf(future.result())).contains("limit");
	}

	@Test
	public void testAMissingDefinitionIsRejectedBeforeTheServiceIsTouched() {
		Future<JsonObject> future = new RunNodeGraphTool(service)
			.execute(new JsonObject().put("assetUuids", new JsonArray().add(ASSET_UUID.toString())), ctx);

		assertTrue(future.succeeded());
		assertThat(textOf(future.result())).contains("definition");
		verify(service, never()).startRun(any(), any());
	}

	@Test
	public void testAnEmptyAssetListIsRejectedBeforeTheServiceIsTouched() {
		Future<JsonObject> future = new RunNodeGraphTool(service).execute(new JsonObject()
			.put("definition", new JsonObject())
			.put("assetUuids", new JsonArray()), ctx);

		assertTrue(future.succeeded());
		assertThat(textOf(future.result())).contains("assetUuid");
		verify(service, never()).startRun(any(), any());
	}

	// ── get_job / cancel_job ─────────────────────────────────────────────

	@Test
	public void testGetJobRendersCountersAndPerItemRows() {
		when(service.status(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
			.thenReturn(new NodeRunStatusResponse()
				.setUuid(RUN_UUID)
				.setStatus("RUNNING")
				.setMediaCount(2)
				.setSuccessCount(1)
				.setResults(List.of(new NodeRunItemResult()
					.setMediaPath("/data/beach.jpg")
					.setNodeId("vlm")
					.setState("COMPLETED")
					.setOutputs(new JsonObject().put("text", new JsonObject()
						.put("elements", new JsonArray().add(new JsonObject().put("value", "A beach at sunset."))))))));

		JsonObject result = new GetJobTool(service, options).execute(jobArgs(), ctx).result();

		String text = textOf(result);
		assertThat(text).contains("RUNNING").contains("1 succeeded").contains("beach.jpg").contains("A beach at sunset.");
		assertEquals(50, result.getJsonArray("visuals").getJsonObject(0).getJsonObject("payload").getInteger("percent"));
	}

	@Test
	public void testAForeignJobIsNotFoundRatherThanForbidden() {
		when(service.status(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
			.thenThrow(new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Node run not found."));

		Future<JsonObject> future = new GetJobTool(service, options).execute(jobArgs(), ctx);

		assertTrue(future.succeeded());
		// "not found" rather than "forbidden": the distinction would let a caller enumerate other
		// people's jobs by uuid.
		assertThat(textOf(future.result())).contains("no node run").doesNotContain("forbidden");
	}

	@Test
	public void testCancelReportsWhatActuallyStops() {
		JsonObject result = new CancelJobTool(service).execute(jobArgs(), ctx).result();

		verify(service).cancel(USER_UUID, RUN_UUID);
		assertThat(textOf(result)).contains("Cancelled").contains("already running on a worker will finish");
	}

	@Test
	public void testCancellingAFinishedJobIsAResultNotAFailure() {
		org.mockito.Mockito.doThrow(new LoomRestException(409, LoomRestErrorCode.CONFLICT, "Node run is already SUCCESS."))
			.when(service).cancel(any(), any());

		Future<JsonObject> future = new CancelJobTool(service).execute(jobArgs(), ctx);

		assertTrue(future.succeeded());
		assertThat(textOf(future.result())).contains("already SUCCESS");
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	private JsonObject probeArgs(String kind) {
		return new JsonObject().put("kind", kind).put("assetUuid", ASSET_UUID.toString());
	}

	private JsonObject graphArgs() {
		return new JsonObject()
			.put("definition", new JsonObject().put("version", 1))
			.put("assetUuids", new JsonArray().add(ASSET_UUID.toString()));
	}

	private JsonObject jobArgs() {
		return new JsonObject().put("jobId", RUN_UUID.toString());
	}

}
