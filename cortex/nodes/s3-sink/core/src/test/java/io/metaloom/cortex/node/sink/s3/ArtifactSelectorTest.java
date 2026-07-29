package io.metaloom.cortex.node.sink.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;

/**
 * What the sink uploads is now decided by the {@code artifacts} input port, in sequence order.
 *
 * <p>
 * The {@code artifacts} option ({@code nodeId:outputKey} strings) and the {@code autoDiscover} flag
 * (upload anything whose key ends in {@code _path}) are both gone, so the cases that covered them
 * are gone with them - there is nothing left to auto-discover from and no node id to name. An edge
 * into the typed {@code artifact/*} port says which file from which node, and says it in a way a
 * rename cannot break. Everything below is a rule that still exists: ordering, indexing,
 * resolution, dedupe, absence and {@code includeSource}.
 * </p>
 */
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

	// --- what the port carries ------------------------------------------------------------

	@Test
	public void testEveryElementOfThePortIsSelected() {
		List<SinkArtifact> selected = selector.select(List.of(thumb.toString(), depth.toString()), null, false);

		assertThat(selected).extracting(SinkArtifact::file).containsExactly(thumb, depth);
	}

	@Test
	public void testSequenceOrderIsPreserved() {
		// The port's element order is the upload order: it is the order the producer emitted in,
		// and a key template keyed on the index would otherwise name the wrong file.
		List<SinkArtifact> selected = selector.select(List.of(depth.toString(), thumb.toString()), null, false);

		assertThat(selected).extracting(SinkArtifact::file).containsExactly(depth, thumb);
	}

	@Test
	public void testAnEmptyPortSelectsNothing() {
		assertThat(selector.select(List.of(), null, false)).isEmpty();
		assertThat(selector.select(null, null, false)).isEmpty();
	}

	@Test
	public void testProvenanceNamesThePortRatherThanAnUpstreamNode() {
		// The selector cannot know which node filled the port, and no longer needs to: the graph
		// does. Recording the port id keeps the label honest instead of guessing a node name.
		List<SinkArtifact> selected = selector.select(List.of(thumb.toString()), null, false);

		assertThat(selected).extracting(SinkArtifact::sourceNode).containsExactly(SinkArtifact.ARTIFACTS_PORT);
		assertThat(selected).extracting(SinkArtifact::sourceKey).containsExactly(SinkArtifact.ARTIFACTS_PORT);
	}

	// --- multi-element ports --------------------------------------------------------------

	@Test
	public void testSeveralElementsBecomeIndexedArtifacts() throws IOException {
		Path a = write("script_bin/n/a.png");
		Path b = write("script_bin/n/b.png");

		List<SinkArtifact> selected = selector.select(List.of(a.toString(), b.toString()), null, false);

		assertThat(selected).extracting(SinkArtifact::index).containsExactly(0, 1);
		assertThat(selected).allMatch(SinkArtifact::multiValued);
	}

	@Test
	public void testASingleElementIsNotMarkedMultiValued() {
		List<SinkArtifact> selected = selector.select(List.of(thumb.toString()), null, false);

		// So the key template does not append a "[0]" suffix to a lone artifact.
		assertThat(selected).hasSize(1);
		assertThat(selected.get(0).multiValued()).isFalse();
		assertThat(selected.get(0).index()).isZero();
	}

	// --- resolution rules -----------------------------------------------------------------

	@Test
	public void testRelativePathsResolveAgainstTheMetaPath() {
		List<SinkArtifact> selected = selector.select(List.of("thumbnail_bin/ab/sheet.thumb"), null, false);

		assertThat(selected).extracting(SinkArtifact::file).containsExactly(thumb);
	}

	@Test
	public void testDuplicatePathsAreCollapsed() {
		// Two elements pointing at one file is waste, never intent.
		assertThat(selector.select(List.of(thumb.toString(), thumb.toString()), null, false)).hasSize(1);
	}

	@Test
	public void testMissingFilesAreSelectedButFlaggedAbsent() {
		// Recorded rather than filtered: "the producer said it wrote this and it is not here" is
		// the affinity failure the node must report, not a silent skip.
		List<SinkArtifact> selected = selector.select(List.of(metaPath.resolve("gone.thumb").toString()), null, false);

		assertThat(selected).hasSize(1);
		assertThat(selected.get(0).present()).isFalse();
	}

	@Test
	public void testBlankAndNullValuesAreIgnored() {
		assertThat(selector.select(List.of("  "), null, false)).isEmpty();
		assertThat(selector.select(java.util.Arrays.asList((String) null), null, false)).isEmpty();
	}

	// --- includeSource --------------------------------------------------------------------

	@Test
	public void testIncludeSourceAddsTheMediaItem() throws IOException {
		Path source = write("library/clip.mp4");

		List<SinkArtifact> selected = selector.select(List.of(thumb.toString()), media(source), true);

		assertThat(selected).extracting(SinkArtifact::file).containsExactlyInAnyOrder(thumb, source);
		assertThat(selected).filteredOn(a -> a.file().equals(source))
			.allMatch(a -> SinkArtifact.SOURCE_MEDIA.equals(a.sourceNode()));
	}

	@Test
	public void testIncludeSourceIsOffByDefault() throws IOException {
		Path source = write("library/clip2.mp4");

		assertThat(selector.select(List.of(), media(source), false)).isEmpty();
	}

	@Test
	public void testTheSourceMediaComesLast() throws IOException {
		// So a template that numbers artifacts keeps the produced files stable when the archive
		// flag is toggled on.
		Path source = write("library/clip4.mp4");

		List<SinkArtifact> selected = selector.select(List.of(thumb.toString()), media(source), true);

		assertThat(selected).extracting(SinkArtifact::file).containsExactly(thumb, source);
	}

	@Test
	public void testSourceMediaIsNotDuplicatedWhenAlsoOnThePort() throws IOException {
		Path source = write("library/clip3.mp4");

		assertThat(selector.select(List.of(source.toString()), media(source), true)).hasSize(1);
	}

	// --- artifact helpers -----------------------------------------------------------------

	@Test
	public void testArtifactDerivesNameParts() {
		SinkArtifact artifact = new SinkArtifact(SinkArtifact.ARTIFACTS_PORT, SinkArtifact.ARTIFACTS_PORT, 0, false, thumb, true);

		assertThat(artifact.fileName()).isEqualTo("sheet.thumb");
		assertThat(artifact.baseName()).isEqualTo("sheet");
		assertThat(artifact.extension()).isEqualTo(".thumb");
		assertThat(artifact.describe()).isEqualTo("artifacts:artifacts");
	}

	@Test
	public void testMultiValuedArtifactDescribesItsIndex() {
		SinkArtifact artifact = new SinkArtifact(SinkArtifact.ARTIFACTS_PORT, SinkArtifact.ARTIFACTS_PORT, 2, true, thumb, true);

		assertThat(artifact.describe()).isEqualTo("artifacts:artifacts[2]");
	}
}
