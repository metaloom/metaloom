package io.metaloom.loom.rest.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.rest.model.pipeline.PipelineRunRequest;

/**
 * The selector precedence in {@link SourceOptionsResolver} decides what a run actually
 * scans. Getting it wrong does not fail loudly - it produces a green run over the wrong
 * file set - so every branch is pinned here.
 */
public class SourceOptionsResolverTest {

	private static final UUID ASSET_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID ASSET_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

	/** Resolves A and B to paths; anything else has no stored binary. */
	private static String resolvePath(UUID uuid) {
		if (ASSET_A.equals(uuid)) {
			return "/media/a.mp4";
		}
		if (ASSET_B.equals(uuid)) {
			return "/media/b.mp4";
		}
		return null;
	}

	private static Map<String, Object> resolve(Map<String, Object> base, PipelineRunRequest request) {
		return SourceOptionsResolver.resolve(base, request, SourceOptionsResolverTest::resolvePath);
	}

	@Test
	@DisplayName("an empty request leaves the definition's options untouched")
	public void testEmptyRequestKeepsDefinitionOptions() {
		Map<String, Object> base = Map.of("path", "/media/library", "emitStates", List.of("NEW"));

		Map<String, Object> options = resolve(base, new PipelineRunRequest());

		assertThat(options).containsEntry("path", "/media/library").containsEntry("emitStates", List.of("NEW"));
	}

	@Test
	@DisplayName("a null request is tolerated and returns the definition's options")
	public void testNullRequest() {
		Map<String, Object> options = resolve(Map.of("path", "/media/library"), null);

		assertThat(options).containsEntry("path", "/media/library");
	}

	@Test
	@DisplayName("the returned map is a copy - the definition's options are never mutated")
	public void testBaseOptionsNotMutated() {
		Map<String, Object> base = new java.util.LinkedHashMap<>(Map.of("path", "/media/library"));

		resolve(base, new PipelineRunRequest().setPath("/media/other"));

		assertThat(base).containsEntry("path", "/media/library");
	}

	@Test
	@DisplayName("path overrides the definition's path and enables the differential scan")
	public void testPathOverride() {
		Map<String, Object> options = resolve(Map.of("path", "/media/library"),
			new PipelineRunRequest().setPath("/media/incoming"));

		assertThat(options).containsEntry("path", "/media/incoming").doesNotContainKey("pathGlobs");
	}

	@Test
	@DisplayName("a blank path is ignored rather than blanking the definition's path")
	public void testBlankPathIgnored() {
		Map<String, Object> options = resolve(Map.of("path", "/media/library"),
			new PipelineRunRequest().setPath("   "));

		assertThat(options).containsEntry("path", "/media/library");
	}

	@Test
	@DisplayName("pathGlobs beats path - a glob request is never downgraded to a single root")
	public void testGlobsBeatPath() {
		Map<String, Object> options = resolve(Map.of(),
			new PipelineRunRequest().setPath("/media/incoming").setPathGlobs(List.of("/media/**/*.mp4")));

		assertThat(options).containsEntry("pathGlobs", List.of("/media/**/*.mp4"));
		// Critically, path must NOT be set: the source node prefers globs, so leaving both
		// set would work, but a later change of that preference would silently switch the
		// run from a full re-walk to a differential scan.
		assertThat(options).doesNotContainKey("path");
	}

	@Test
	@DisplayName("an empty pathGlobs list falls through to path")
	public void testEmptyGlobsFallThroughToPath() {
		Map<String, Object> options = resolve(Map.of(),
			new PipelineRunRequest().setPath("/media/incoming").setPathGlobs(List.of()));

		assertThat(options).containsEntry("path", "/media/incoming").doesNotContainKey("pathGlobs");
	}

	@Test
	@DisplayName("a single asset wins outright and clears any inherited glob selection")
	public void testSingleAssetClearsGlobs() {
		// The definition itself selects the whole library by glob. Running the pipeline for
		// one asset must scan that asset, not re-scan the library.
		Map<String, Object> base = Map.of("pathGlobs", List.of("/media/**"));

		Map<String, Object> options = resolve(base, new PipelineRunRequest().setMediaUuids(List.of(ASSET_A)));

		assertThat(options).containsEntry("path", "/media/a.mp4")
			.containsEntry("assetUuid", ASSET_A.toString())
			.doesNotContainKey("pathGlobs");
	}

	@Test
	@DisplayName("a single asset also beats an explicit pathGlobs on the request")
	public void testSingleAssetBeatsRequestGlobs() {
		Map<String, Object> options = resolve(Map.of(),
			new PipelineRunRequest().setPathGlobs(List.of("/media/**")).setMediaUuids(List.of(ASSET_A)));

		assertThat(options).containsEntry("path", "/media/a.mp4").doesNotContainKey("pathGlobs");
	}

	@Test
	@DisplayName("multiple assets become pathGlobs and clear any inherited single path")
	public void testMultipleAssetsClearPath() {
		Map<String, Object> base = Map.of("path", "/media/library");

		Map<String, Object> options = resolve(base, new PipelineRunRequest().setMediaUuids(List.of(ASSET_A, ASSET_B)));

		assertThat(options).containsEntry("pathGlobs", List.of("/media/a.mp4", "/media/b.mp4"))
			.doesNotContainKey("path");
	}

	@Test
	@DisplayName("assets with no stored binary are skipped, not turned into null paths")
	public void testUnresolvableAssetsSkipped() {
		UUID unknown = UUID.randomUUID();

		Map<String, Object> options = resolve(Map.of(),
			new PipelineRunRequest().setMediaUuids(List.of(ASSET_A, unknown)));

		// One of the two resolved, so this collapses to the single-asset form - and the
		// assetUuid must be the one that actually resolved, not the first one requested.
		assertThat(options).containsEntry("path", "/media/a.mp4").containsEntry("assetUuid", ASSET_A.toString());
	}

	@Test
	@DisplayName("when no asset resolves, the path/glob selection is left in place")
	public void testNoAssetResolves() {
		Map<String, Object> options = resolve(Map.of(),
			new PipelineRunRequest().setPath("/media/incoming").setMediaUuids(List.of(UUID.randomUUID())));

		assertThat(options).containsEntry("path", "/media/incoming").doesNotContainKey("assetUuid");
	}
}
