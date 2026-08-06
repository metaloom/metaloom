package io.metaloom.loom.rest.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.nodes.spec.Cardinality;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.nodes.spec.PortSpec;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;
import io.metaloom.loom.rest.service.impl.PipelineAuthoringService.PipelineWithVersion;
import io.metaloom.loom.rest.service.impl.PipelineAuthoringService.ValidationReport;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.PipelineValidationService;
import io.metaloom.loom.rest.validation.ValidationException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The one write path for pipeline definitions, shared by the REST endpoint and the MCP authoring tools.
 *
 * <p>
 * Three properties matter here and none of them is about JSON shapes: nothing is stored before the definition is known to be sound, an update appends
 * rather than edits, and {@code latest_version_uuid} is repointed by whoever wrote the new version.
 * </p>
 */
public class PipelineAuthoringServiceTest {

	private static final UUID USER_UUID = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
	private static final UUID PIPELINE_UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID VERSION_UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

	private PipelineDao pipelineDao;
	private PipelineVersionDao versionDao;
	private ProcessorRegistry processors;
	private Pipeline pipeline;
	private PipelineAuthoringService service;

	@BeforeEach
	public void setup() {
		pipelineDao = mock(PipelineDao.class);
		versionDao = mock(PipelineVersionDao.class);
		processors = mock(ProcessorRegistry.class);
		when(processors.selectProcessorForKinds(any(), any())).thenReturn(null);

		NodeDescriptorRegistry registry = new NodeDescriptorRegistry();
		registry.register(new NodeDescriptor()
			.setNodeId("filesystem-source")
			.setName("File Source")
			.setCategory(NodeCategory.SOURCE)
			.setOutputPorts(List.of(PortSpec.one("media", "media/*"))));
		registry.register(new NodeDescriptor()
			.setNodeId("sha512")
			.setName("SHA-512")
			.setCategory(NodeCategory.ANALYSIS)
			.setInputPorts(List.of(PortSpec.one("media", "media/*")))
			.setOutputPorts(List.of(new PortSpec("hash", "hash/sha512", Cardinality.ONE))));

		pipeline = mock(Pipeline.class);
		when(pipeline.getUuid()).thenReturn(PIPELINE_UUID);
		when(pipeline.setMeta(any())).thenReturn(pipeline);
		when(pipelineDao.createPipeline(any(UUID.class), any())).thenReturn(pipeline);
		when(pipelineDao.loadWithLatestVersion(PIPELINE_UUID)).thenReturn(pipeline);

		when(versionDao.createVersion(any(), any(), anyInt(), any(), any(), any(), anyBoolean(), anyInt(), anyBoolean(), any()))
			.thenAnswer(i -> version(i.getArgument(2), i.getArgument(3), i.getArgument(5)));

		service = new PipelineAuthoringService(pipelineDao, versionDao, new PipelineValidationService(registry),
			mock(LoomModelValidator.class), registry, processors);
	}

	private static PipelineVersion version(int number, String name, JsonObject definition) {
		PipelineVersion version = mock(PipelineVersion.class);
		when(version.getUuid()).thenReturn(VERSION_UUID);
		when(version.getVersionNumber()).thenReturn(number);
		when(version.getName()).thenReturn(name);
		when(version.getDefinition()).thenReturn(definition);
		return version;
	}

	private static JsonObject validDefinition() {
		return new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "pn1").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "pn2").put("type", "sha512")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("id", "pe1").put("source", "pn1").put("sourcePort", "media")
					.put("target", "pn2").put("targetPort", "media")));
	}

	private static PipelineCreateRequest createRequest(JsonObject definition) {
		return new PipelineCreateRequest().setName("Checksums").setDefinition(definition);
	}

	@Test
	public void testCreateWritesPipelineThenVersionThenPointer() {
		PipelineWithVersion result = service.create(USER_UUID, createRequest(validDefinition()));

		assertNotNull(result);
		assertEquals(1, result.version().getVersionNumber());
		verify(pipelineDao).store(pipeline);
		verify(versionDao).store(any());
		// The pointer is what makes the version the live one; a create that skips it stores a
		// pipeline nothing can read.
		verify(pipeline).setLatestVersionUuid(VERSION_UUID);
		verify(pipelineDao).update(pipeline);
	}

	/**
	 * The format version is stamped on the way in, so a definition read back names the format it is in without the reader having to know which Loom
	 * served it.
	 */
	@Test
	public void testCreateStampsTheFormatVersion() {
		JsonObject definition = validDefinition();
		assertFalse(definition.containsKey("version"));
		service.create(USER_UUID, createRequest(definition));
		assertEquals(1, definition.getInteger("version"));
	}

	@Test
	public void testCreateStoresNothingWhenTheDefinitionIsRejected() {
		JsonObject broken = validDefinition();
		broken.getJsonArray("edges").getJsonObject(0).put("targetPort", "not_a_port");

		assertThrows(ValidationException.class, () -> service.create(USER_UUID, createRequest(broken)));
		verify(pipelineDao, never()).store(any());
		verify(versionDao, never()).store(any());
		verify(pipelineDao, never()).update(any());
	}

	@Test
	public void testUpdateAppendsAndCopiesUnsetFieldsForward() {
		PipelineVersion latest = version(3, "Checksums", validDefinition());
		when(latest.getDescription()).thenReturn("The old description");
		when(latest.getPriority()).thenReturn(7);
		when(versionDao.loadLatestByPipeline(PIPELINE_UUID)).thenReturn(latest);

		PipelineWithVersion result = service.update(USER_UUID, PIPELINE_UUID,
			new PipelineUpdateRequest().setName("Renamed"));

		assertEquals(4, result.version().getVersionNumber());

		ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Integer> priority = ArgumentCaptor.forClass(Integer.class);
		verify(versionDao).createVersion(any(), any(), anyInt(), any(), description.capture(), any(), anyBoolean(),
			priority.capture(), anyBoolean(), any());
		assertEquals("The old description", description.getValue(), "An unset field is inherited, not blanked");
		assertEquals(7, priority.getValue());
		verify(pipeline).setLatestVersionUuid(VERSION_UUID);
	}

	@Test
	public void testUpdateUnknownPipelineReturnsNull() {
		assertNull(service.update(USER_UUID, UUID.randomUUID(), new PipelineUpdateRequest().setName("x")));
	}

	// --- validate ----------------------------------------------------------

	@Test
	public void testValidateAcceptsAGoodGraph() {
		ValidationReport report = service.validate(validDefinition());
		assertTrue(report.valid());
		assertNull(report.error());
	}

	/**
	 * A kind no online worker offers is a fact about the fleet, not about the definition — the run path turns it into a 503, the save path must not
	 * turn it into a refusal.
	 */
	@Test
	public void testValidateWarnsAboutUnplaceableKinds() {
		ValidationReport report = service.validate(validDefinition());
		assertTrue(report.valid());
		assertEquals(1, report.warnings().size());
		assertTrue(report.warnings().get(0).contains("No online worker currently accepts"));
	}

	@Test
	public void testValidateReportsRatherThanThrows() {
		JsonObject broken = validDefinition();
		broken.getJsonArray("edges").getJsonObject(0).put("targetPort", "not_a_port");

		ValidationReport report = service.validate(broken);
		assertFalse(report.valid());
		assertTrue(report.error().contains("not_a_port"));
		assertTrue(report.warnings().isEmpty());
	}

	@Test
	public void testValidateNullDefinition() {
		assertFalse(service.validate(null).valid());
	}

}
