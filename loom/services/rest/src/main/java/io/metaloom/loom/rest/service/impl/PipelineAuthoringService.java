package io.metaloom.loom.rest.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.pipeline.graph.AffinityValidator;
import io.metaloom.loom.pipeline.graph.AffinityWarning;
import io.metaloom.loom.pipeline.graph.GraphValidationException;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineValidationError;
import io.metaloom.loom.rest.model.processor.ProcessorCapability;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.PipelineValidationService;
import io.metaloom.loom.rest.validation.ValidationException;
import io.vertx.core.json.JsonObject;

/**
 * The single write path for pipeline definitions.
 *
 * <p>
 * Authoring used to live inside {@link PipelineEndpointService#create(io.metaloom.loom.rest.LoomRoutingContext)}, welded to a
 * {@code LoomRoutingContext}: the request body, the caller uuid and the response all came from it, so the only way to author a pipeline was over HTTP.
 * The MCP tools are a second door onto exactly the same operation, and a second hand-rolled copy of the seven-step create sequence is how
 * {@code latest_version_uuid} ends up maintained differently in two places. Everything here is therefore routing-free: it takes a caller uuid and a
 * request model and returns rows.
 * </p>
 *
 * <p>
 * The ordering is load-bearing and is the one the REST path has always used: validate the model, validate the definition, stamp the format version,
 * <em>then</em> write. Nothing is stored until the definition is known to be sound, which is what lets a rejected definition leave no row behind.
 * </p>
 */
@Singleton
public class PipelineAuthoringService {

	private final PipelineDao pipelineDao;

	private final PipelineVersionDao pipelineVersionDao;

	private final PipelineValidationService pipelineValidationService;

	private final LoomModelValidator validator;

	private final NodeDescriptorRegistry nodeDescriptorRegistry;

	private final ProcessorRegistry processorRegistry;

	@Inject
	public PipelineAuthoringService(PipelineDao pipelineDao, PipelineVersionDao pipelineVersionDao,
		PipelineValidationService pipelineValidationService, LoomModelValidator validator,
		NodeDescriptorRegistry nodeDescriptorRegistry, ProcessorRegistry processorRegistry) {
		this.pipelineDao = pipelineDao;
		this.pipelineVersionDao = pipelineVersionDao;
		this.pipelineValidationService = pipelineValidationService;
		this.validator = validator;
		this.nodeDescriptorRegistry = nodeDescriptorRegistry;
		this.processorRegistry = processorRegistry;
	}

	/**
	 * A pipeline together with the version that carries its name, description and definition.
	 *
	 * <p>
	 * The two travel together because since {@code V2.30} the {@code pipeline} row holds nothing a caller wants to read — name, description and
	 * definition all live on the version.
	 * </p>
	 */
	public record PipelineWithVersion(Pipeline pipeline, PipelineVersion version) {
	}

	/**
	 * The outcome of a dry run over a definition.
	 *
	 * <p>
	 * {@code warnings} are never fatal, and the distinction matters: a node kind no worker currently offers is a real thing to tell the author, but
	 * refusing the save would make editing a pipeline depend on which machines happen to be up — see {@link AffinityWarning}.
	 * </p>
	 *
	 * @param valid
	 *            whether the definition would be accepted by {@link #create} / {@link #update}
	 * @param errors
	 *            every problem found, not just the first; empty when valid
	 * @param warnings
	 *            things the author probably did not intend; may be non-empty for a valid definition
	 */
	public record ValidationReport(boolean valid, List<PipelineValidationError> errors, List<String> warnings) {

		public static ValidationReport invalid(List<PipelineValidationError> errors) {
			return new ValidationReport(false, List.copyOf(errors), List.of());
		}

		public static ValidationReport valid(List<String> warnings) {
			return new ValidationReport(true, List.of(), List.copyOf(warnings));
		}

		/**
		 * The first problem, for callers that can only show one.
		 *
		 * <p>
		 * The MCP tool renders text into a model's context window, where a list of six messages is
		 * worse than one plus an invitation to validate again; the REST route hands back
		 * {@link #errors} whole. Null when valid.
		 * </p>
		 */
		public String error() {
			return errors.isEmpty() ? null : errors.get(0).getMessage();
		}
	}

	/**
	 * Create a pipeline and its version 1.
	 *
	 * @param userUuid
	 *            the creator
	 * @param request
	 *            name + definition are required
	 * @return the stored pipeline and its first version
	 * @throws ValidationException
	 *             when the request or the definition is rejected; nothing has been stored in that case
	 */
	public PipelineWithVersion create(UUID userUuid, PipelineCreateRequest request) {
		validator.validate(request);
		pipelineValidationService.validateDefinition(request.getDefinition());
		// Stamp the format version so what is stored names the format it is in. Done on the
		// way in rather than on the way out: a definition read back has to be interpretable
		// by itself, without knowing which Loom happened to serve it.
		PipelineGraphParser.stampVersion(request.getDefinition());

		Pipeline pipeline = pipelineDao.createPipeline(userUuid, request.getName());
		pipeline.setMeta(request.getMeta());
		pipelineDao.store(pipeline);

		PipelineVersion version = pipelineVersionDao.createVersion(
			userUuid,
			pipeline.getUuid(),
			1,
			request.getName(),
			request.getDescription(),
			request.getDefinition(),
			request.isEnabled() != null ? request.isEnabled() : true,
			request.getPriority() != null ? request.getPriority() : 0,
			request.isDryRun() != null ? request.isDryRun() : false,
			request.getMeta());
		pipelineVersionDao.store(version);

		pipeline.setLatestVersionUuid(version.getUuid());
		pipelineDao.update(pipeline);

		return new PipelineWithVersion(pipeline, version);
	}

	/**
	 * Append a new version to an existing pipeline.
	 *
	 * <p>
	 * An existing version is <b>never</b> mutated: this creates {@code latest.versionNumber + 1}, copying every unset field forward, and repoints
	 * {@code latest_version_uuid}. That is what makes the version history a history rather than a changelog of the current state.
	 * </p>
	 *
	 * @param userUuid
	 *            the editor
	 * @param pipelineUuid
	 *            the pipeline to append to
	 * @param request
	 *            every field is optional; unset fields are inherited from the latest version
	 * @return the pipeline and its new version, or {@code null} when no such pipeline exists
	 * @throws ValidationException
	 *             when the request or a supplied definition is rejected; nothing has been stored in that case
	 */
	public PipelineWithVersion update(UUID userUuid, UUID pipelineUuid, PipelineUpdateRequest request) {
		validator.validate(request);
		if (request.getDefinition() != null) {
			pipelineValidationService.validateDefinition(request.getDefinition());
			PipelineGraphParser.stampVersion(request.getDefinition());
		}

		Pipeline pipeline = pipelineDao.loadWithLatestVersion(pipelineUuid);
		if (pipeline == null) {
			return null;
		}

		PipelineVersion latest = pipelineVersionDao.loadLatestByPipeline(pipeline.getUuid());
		// Since V2.30 every pipeline is backfilled with a version, so `latest` is null only for a
		// row that predates its own invariant. Fall back to the create-path defaults rather than
		// dereferencing it: an update that cannot inherit is still an update.
		int nextVersion = latest != null ? latest.getVersionNumber() + 1 : 1;

		PipelineVersion version = pipelineVersionDao.createVersion(
			userUuid,
			pipeline.getUuid(),
			nextVersion,
			request.getName() != null ? request.getName() : latest == null ? null : latest.getName(),
			request.getDescription() != null ? request.getDescription() : latest == null ? null : latest.getDescription(),
			request.getDefinition() != null ? request.getDefinition() : latest == null ? null : latest.getDefinition(),
			request.isEnabled() != null ? request.isEnabled() : latest == null || latest.isEnabled(),
			request.getPriority() != null ? request.getPriority() : latest == null ? 0 : latest.getPriority(),
			request.isDryRun() != null ? request.isDryRun() : latest != null && latest.isDryRun(),
			request.getMeta() != null ? request.getMeta() : latest == null ? null : latest.getMeta());
		pipelineVersionDao.store(version);

		pipeline.setLatestVersionUuid(version.getUuid());
		if (request.getMeta() != null) {
			pipeline.setMeta(request.getMeta());
		}
		pipeline.setEditorUuid(userUuid);
		pipeline.setEdited(Instant.now());
		pipelineDao.update(pipeline);

		return new PipelineWithVersion(pipeline, version);
	}

	/**
	 * Check a definition without storing anything.
	 *
	 * <p>
	 * This is the dry run behind {@code POST /api/v1/pipelines/validate} and the {@code validate_pipeline} MCP tool, and it exists because whoever is
	 * authoring a graph — an agent or a person in the editor — needs to find out what is wrong with a draft without leaving a pipeline behind for
	 * every attempt. It runs exactly the checks {@link #create} runs — same {@link PipelineValidationService}, therefore the same
	 * {@code PortGraphAnalyzer} — and then adds the two questions save-time validation deliberately does not fail on.
	 * </p>
	 *
	 * <p>
	 * The one difference from {@link #create} is that it collects: {@code create} stops at the first problem because it is deciding whether to write a
	 * row, whereas here the caller is fixing a draft and wants the whole list.
	 * </p>
	 *
	 * @param definition
	 *            the definition to check
	 * @return a report; never null, never throws for an invalid definition
	 */
	public ValidationReport validate(JsonObject definition) {
		List<PipelineValidationError> errors = pipelineValidationService.collectErrors(definition);
		if (!errors.isEmpty()) {
			return ValidationReport.invalid(errors);
		}

		// The parse below repeats work collectErrors already did, but it is the only way to get the
		// graph itself: collectErrors throws away the PipelineGraph it builds. The alternative —
		// having it return one — would change a validator into a factory on the REST path too, for
		// the benefit of this one caller.
		PipelineGraph graph;
		try {
			graph = new PipelineGraphParser(nodeDescriptorRegistry).parse("definition", definition, true, false, 0);
		} catch (GraphValidationException e) {
			// Unreachable in practice: collectErrors ran the same parse and would have reported it.
			return ValidationReport.invalid(List.of(new PipelineValidationError(PipelineValidationService.PORTS,
				PipelineValidationService.stripGraphName(e.getMessage()), null, null)));
		}

		List<String> warnings = new ArrayList<>();

		// The same check dispatchRun makes, where it is a 503. Here it is a warning: a kind whose
		// worker is offline right now is a fact about the fleet, not about the definition, and the
		// definition will still be there when the worker comes back.
		Set<String> unsupported = PipelineEndpointService.unsupportedNodeKinds(graph, processorRegistry);
		if (!unsupported.isEmpty()) {
			warnings.add("No online worker currently accepts these node kinds: " + String.join(", ", unsupported)
				+ ". The pipeline can be saved, but a run started now would be refused.");
		}

		// The affinity fleet check asks whether ONE worker can run a whole segment, which is a real
		// and different question — until nothing is online at all, when "no worker takes sha512" and
		// "no worker takes sha512 and thumbnail together" are the same news twice. Skip it then and
		// let the check above own the message; the structural GROUP_SPLIT warnings still come through.
		Predicate<List<String>> fleetCheck = unsupported.isEmpty()
			? kinds -> processorRegistry.selectProcessorForKinds(ProcessorCapability.CPU, kinds) != null
			: kinds -> true;
		for (AffinityWarning warning : new AffinityValidator().validate(graph, fleetCheck)) {
			warnings.add(warning.getMessage());
		}

		return ValidationReport.valid(warnings);
	}

}
