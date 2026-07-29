package io.metaloom.cortex.node.sink.s3;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;

/**
 * Turns what the sink's {@code artifacts} port carries into the ordered list of files to upload.
 *
 * <p>Two sources, in order:</p>
 *
 * <ol>
 * <li><b>Every element of the {@code artifacts} input port</b>, in sequence order. This replaces
 * the {@code artifacts} option (a list of {@code nodeId:outputKey} strings) and the
 * {@code autoDiscover} flag (upload anything whose key ends in {@code _path}). Both existed only
 * because there was no way to say "this file, from that node" in the graph: the option broke when a
 * node was renamed, and discovery-by-suffix silently missed every {@code script} image because
 * those keys are author-named. An edge into a typed {@code artifact/*} port says both precisely.</li>
 * <li><b>The media item itself</b>, when {@code includeSource} is set - which is what makes
 * {@code filesystem-source → s3-sink} an archiver.</li>
 * </ol>
 */
public final class ArtifactSelector {

	private static final Logger log = LoggerFactory.getLogger(ArtifactSelector.class);

	private final Path metaPath;

	public ArtifactSelector(Path metaPath) {
		this.metaPath = metaPath;
	}

	/**
	 * @param artifacts     what the {@code artifacts} port carries, in sequence order
	 * @param media         the media item flowing through the graph
	 * @param includeSource whether to also upload the media item
	 * @return the artifacts to upload, deduplicated, in a stable order
	 */
	public List<SinkArtifact> select(List<String> artifacts, LoomMedia media, boolean includeSource) {
		List<SinkArtifact> selected = new ArrayList<>();
		Set<Path> seen = new LinkedHashSet<>();

		if (artifacts != null) {
			for (int seq = 0; seq < artifacts.size(); seq++) {
				add(seq, artifacts.size() > 1, artifacts.get(seq), selected, seen);
			}
		}

		if (includeSource && media != null) {
			addSourceMedia(media, selected, seen);
		}
		return selected;
	}

	private void add(int index, boolean multiValued, String raw, List<SinkArtifact> out, Set<Path> seen) {
		if (raw == null || raw.isBlank()) {
			return;
		}
		Path file = resolve(raw);
		if (file == null) {
			log.warn("Artifact element {} value '{}' is not a usable path - ignored", index, raw);
			return;
		}
		if (!seen.add(file)) {
			// Two elements pointing at one file is waste, never intent.
			log.debug("Artifact {} is already selected - dropping the duplicate at element {}", file, index);
			return;
		}
		// Presence is recorded rather than filtered: "the producer said it wrote this and it is
		// not here" is an affinity failure the node must report, not a silent skip.
		out.add(new SinkArtifact(SinkArtifact.ARTIFACTS_PORT, SinkArtifact.ARTIFACTS_PORT, index, multiValued,
			file, Files.isRegularFile(file)));
	}

	private void addSourceMedia(LoomMedia media, List<SinkArtifact> out, Set<Path> seen) {
		Path file;
		try {
			file = media.path().toAbsolutePath().normalize();
		} catch (RuntimeException e) {
			log.warn("Could not resolve the media path for the source archive: {}", e.getMessage());
			return;
		}
		if (!seen.add(file)) {
			return;
		}
		out.add(new SinkArtifact(SinkArtifact.SOURCE_MEDIA, SinkArtifact.SOURCE_MEDIA, 0, false,
			file, Files.isRegularFile(file)));
	}

	/** A relative path is resolved against the worker's meta path, where the {@code *_bin} caches live. */
	private Path resolve(String raw) {
		try {
			Path path = Paths.get(raw);
			if (!path.isAbsolute() && metaPath != null) {
				path = metaPath.resolve(path);
			}
			return path.toAbsolutePath().normalize();
		} catch (InvalidPathException e) {
			return null;
		}
	}
}
