package io.metaloom.loom.rest.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import io.metaloom.loom.rest.model.pipeline.PipelineRunRequest;

/**
 * Merges a {@link PipelineRunRequest}'s media selection into the options the source node
 * will run with.
 *
 * <p>Kept free of DB and transport concerns - the asset-UUID-to-path lookup arrives as a
 * function - so the precedence rules can be unit-tested directly. They are easy to get
 * subtly wrong and almost impossible to observe from outside a running dispatch.</p>
 *
 * <p><strong>Precedence, most specific first:</strong> {@code mediaUuids} &gt;
 * {@code pathGlobs} &gt; {@code path}.</p>
 *
 * <p>{@code path} and {@code pathGlobs} are not interchangeable and the distinction is the
 * reason they are separate fields rather than one: {@code pathGlobs} forces the source node
 * into a full filesystem re-walk, while a bare {@code path} lets it run the differential
 * scan against its persisted per-root index. Applying {@code path} on top of a glob request
 * would therefore silently change what the run does, so a glob request wins outright.</p>
 */
final class SourceOptionsResolver {

	private SourceOptionsResolver() {
	}

	/**
	 * Resolve the effective source node options for a run.
	 *
	 * @param baseOptions        the options declared on the source node in the pipeline definition
	 * @param request            the run request, may be null
	 * @param binaryPathResolver resolves an asset UUID to its stored binary path, returning
	 *                           null when the asset has no stored binary
	 * @return a new mutable map; the input map is never modified
	 */
	static Map<String, Object> resolve(Map<String, Object> baseOptions, PipelineRunRequest request,
		Function<UUID, String> binaryPathResolver) {

		Map<String, Object> options = new LinkedHashMap<>(baseOptions == null ? Map.of() : baseOptions);
		if (request == null) {
			return options;
		}

		boolean hasGlobs = request.getPathGlobs() != null && !request.getPathGlobs().isEmpty();
		if (hasGlobs) {
			options.put("pathGlobs", request.getPathGlobs());
		} else if (request.getPath() != null && !request.getPath().isBlank()) {
			options.put("path", request.getPath());
		}

		List<UUID> mediaUuids = request.getMediaUuids();
		if (mediaUuids == null || mediaUuids.isEmpty()) {
			return options;
		}

		List<String> paths = new ArrayList<>();
		List<UUID> resolvedUuids = new ArrayList<>();
		for (UUID assetUuid : mediaUuids) {
			String path = binaryPathResolver.apply(assetUuid);
			if (path != null) {
				paths.add(path);
				resolvedUuids.add(assetUuid);
			}
		}

		if (paths.size() == 1) {
			// A single asset is the most specific selection there is, so it replaces
			// whatever path/glob selection was set above rather than adding to it.
			options.put("path", paths.get(0));
			options.put("assetUuid", resolvedUuids.get(0).toString());
			options.remove("pathGlobs");
		} else if (paths.size() > 1) {
			options.put("pathGlobs", paths);
			options.remove("path");
		}
		return options;
	}
}
