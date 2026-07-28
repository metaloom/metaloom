package io.metaloom.cortex.node.sink.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.vertx.core.json.JsonArray;

public class ArtifactSelectorTest {

	@TempDir
	Path metaPath;

	private ArtifactSelector selector;
	private Path thumb;
	private Path depth;

	@BeforeEach
	public void setup() throws IOException {
		selector = new ArtifactSelector(metaPath);
		thumb = write("thumbnail_bin/ab/sheet.thumb");
		depth = write("depthmap_bin/cd/map.png");
	}

	private Path write(String relative) throws IOException {
		Path file = metaPath.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "bytes");
		return file.toAbsolutePath().normalize();
	}

	private LoomMedia media(Path path) {
		LoomMedia media = mock(LoomMedia.class);
		when(media.path()).thenReturn(path);
		when(media.reference()).thenReturn(path.toString());
		return media;
	}

	// --- auto-discovery -------------------------------------------------------------------

	@Test
	public void testAutoDiscoveryPicksUpPathOutputs() {
		Map<String, Map<String, Object>> upstream = Map.of(
			"thumbnail", Map.of("thumbnail_path", thumb.toString(), "thumbnail_flag", "DONE"),
			"depthmap", Map.of("depthmap_path", depth.toString()));

		List<SinkArtifact> selected = selector.select(upstream, null, List.of(), true, false);

		assertThat(selected).extracting(SinkArtifact::file).containsExactlyInAnyOrder(thumb, depth);
	}

	@Test
	public void testAutoDiscoveryIgnoresNonPathOutputs() {
		// depthmap_meta is JSON and thumbnail_flag is a status - neither is a file.
		Map<String, Map<String, Object>> upstream = Map.of(
			"depthmap", Map.of("depthmap_path", depth.toString(),
				"depthmap_meta", "{\"model\":\"x\"}", "depthmap_flag", "DONE"));

		List<SinkArtifact> selected = selector.select(upstream, null, List.of(), true, false);

		assertThat(selected).extracting(SinkArtifact::sourceKey).containsExactly("depthmap_path");
	}

	@Test
	public void testAutoDiscoveryCanBeDisabled() {
		Map<String, Map<String, Object>> upstream = Map.of("thumbnail", Map.of("thumbnail_path", thumb.toString()));

		assertThat(selector.select(upstream, null, List.of(), false, false)).isEmpty();
	}

	// --- explicit selection ---------------------------------------------------------------

	@Test
	public void testExplicitListSelectsExactlyWhatItNames() {
		Map<String, Map<String, Object>> upstream = Map.of(
			"thumbnail", Map.of("thumbnail_path", thumb.toString()),
			"depthmap", Map.of("depthmap_path", depth.toString()));

		List<SinkArtifact> selected = selector.select(upstream, null, List.of("depthmap:depthmap_path"), true, false);

		// Auto-discovery is off whenever an explicit list is given - otherwise excluding a
		// discovered artifact would be impossible.
		assertThat(selected).extracting(SinkArtifact::file).containsExactly(depth);
	}

	@Test
	public void testExplicitListPreservesOrder() {
		Map<String, Map<String, Object>> upstream = Map.of(
			"thumbnail", Map.of("thumbnail_path", thumb.toString()),
			"depthmap", Map.of("depthmap_path", depth.toString()));

		List<SinkArtifact> selected = selector.select(upstream, null,
			List.of("depthmap:depthmap_path", "thumbnail:thumbnail_path"), true, false);

		assertThat(selected).extracting(SinkArtifact::file).containsExactly(depth, thumb);
	}

	@Test
	public void testAbsentAndMalformedEntriesAreSkippedNotFatal() {
		Map<String, Map<String, Object>> upstream = Map.of("thumbnail", Map.of("thumbnail_path", thumb.toString()));

		List<SinkArtifact> selected = selector.select(upstream, null,
			List.of("missing:nope", "malformed", "thumbnail:thumbnail_path"), true, false);

		assertThat(selected).extracting(SinkArtifact::file).containsExactly(thumb);
	}

	// --- multi-valued outputs -------------------------------------------------------------

	@Test
	public void testListOutputsBecomeIndexedArtifacts() throws IOException {
		Path a = write("script_bin/n/a.png");
		Path b = write("script_bin/n/b.png");
		Map<String, Map<String, Object>> upstream = Map.of("script",
			Map.of("frames", List.of(a.toString(), b.toString())));

		List<SinkArtifact> selected = selector.select(upstream, null, List.of("script:frames"), false, false);

		assertThat(selected).extracting(SinkArtifact::index).containsExactly(0, 1);
		assertThat(selected).allMatch(SinkArtifact::multiValued);
	}

	@Test
	public void testJsonArrayOutputsAreAcceptedToo() throws IOException {
		// ScriptNode re-emits a cached list through new JsonObject(cached), so the same output
		// arrives as a JsonArray rather than a List.
		Path a = write("script_bin/n/c.png");
		Map<String, Map<String, Object>> upstream = Map.of("script",
			Map.of("frames", new JsonArray().add(a.toString())));

		List<SinkArtifact> selected = selector.select(upstream, null, List.of("script:frames"), false, false);

		assertThat(selected).extracting(SinkArtifact::file).containsExactly(a);
		assertThat(selected.get(0).multiValued()).isTrue();
	}

	@Test
	public void testNonPathValuesAreIgnored() {
		Map<String, Map<String, Object>> upstream = Map.of("quality", Map.of("blurriness", 42.0));

		assertThat(selector.select(upstream, null, List.of("quality:blurriness"), false, false)).isEmpty();
	}

	// --- resolution rules -----------------------------------------------------------------

	@Test
	public void testRelativePathsResolveAgainstTheMetaPath() {
		Map<String, Map<String, Object>> upstream = Map.of("thumbnail",
			Map.of("thumbnail_path", "thumbnail_bin/ab/sheet.thumb"));

		List<SinkArtifact> selected = selector.select(upstream, null, List.of(), true, false);

		assertThat(selected).extracting(SinkArtifact::file).containsExactly(thumb);
	}

	@Test
	public void testDuplicatePathsAreCollapsed() {
		Map<String, Map<String, Object>> upstream = Map.of(
			"a", Map.of("x_path", thumb.toString()),
			"b", Map.of("y_path", thumb.toString()));

		assertThat(selector.select(upstream, null, List.of(), true, false)).hasSize(1);
	}

	@Test
	public void testMissingFilesAreSelectedButFlaggedAbsent() {
		// Recorded rather than filtered: "the producer said it wrote this and it is not here" is
		// the affinity failure the node must report, not a silent skip.
		Map<String, Map<String, Object>> upstream = Map.of("thumbnail",
			Map.of("thumbnail_path", metaPath.resolve("gone.thumb").toString()));

		List<SinkArtifact> selected = selector.select(upstream, null, List.of(), true, false);

		assertThat(selected).hasSize(1);
		assertThat(selected.get(0).present()).isFalse();
	}

	@Test
	public void testBlankValuesAreIgnored() {
		Map<String, Map<String, Object>> upstream = Map.of("thumbnail", Map.of("thumbnail_path", "  "));

		assertThat(selector.select(upstream, null, List.of(), true, false)).isEmpty();
	}

	// --- includeSource --------------------------------------------------------------------

	@Test
	public void testIncludeSourceAddsTheMediaItem() throws IOException {
		Path source = write("library/clip.mp4");
		Map<String, Map<String, Object>> upstream = Map.of("thumbnail", Map.of("thumbnail_path", thumb.toString()));

		List<SinkArtifact> selected = selector.select(upstream, media(source), List.of(), true, true);

		assertThat(selected).extracting(SinkArtifact::file).containsExactlyInAnyOrder(thumb, source);
		assertThat(selected).filteredOn(a -> a.file().equals(source))
			.allMatch(a -> SinkArtifact.SOURCE_MEDIA.equals(a.sourceNode()));
	}

	@Test
	public void testIncludeSourceIsOffByDefault() throws IOException {
		Path source = write("library/clip2.mp4");

		assertThat(selector.select(Map.of(), media(source), List.of(), true, false)).isEmpty();
	}

	@Test
	public void testSourceMediaIsNotDuplicatedWhenAlsoAnUpstreamPath() throws IOException {
		Path source = write("library/clip3.mp4");
		Map<String, Map<String, Object>> upstream = Map.of("x", Map.of("x_path", source.toString()));

		assertThat(selector.select(upstream, media(source), List.of(), true, true)).hasSize(1);
	}

	// --- artifact helpers -----------------------------------------------------------------

	@Test
	public void testArtifactDerivesNameParts() {
		SinkArtifact artifact = new SinkArtifact("thumbnail", "thumbnail_path", 0, false, thumb, true);

		assertThat(artifact.fileName()).isEqualTo("sheet.thumb");
		assertThat(artifact.baseName()).isEqualTo("sheet");
		assertThat(artifact.extension()).isEqualTo(".thumb");
		assertThat(artifact.describe()).isEqualTo("thumbnail:thumbnail_path");
	}

	@Test
	public void testMultiValuedArtifactDescribesItsIndex() {
		SinkArtifact artifact = new SinkArtifact("script", "frames", 2, true, thumb, true);

		assertThat(artifact.describe()).isEqualTo("script:frames[2]");
	}
}
