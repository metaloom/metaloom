package io.metaloom.cortex.node.sink.s3;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.vertx.core.json.JsonArray;

/**
 * Turns a node's upstream outputs into the ordered list of files to upload.
 *
 * <p>Three sources, in precedence order:</p>
 *
 * <ol>
 * <li><b>An explicit {@code artifacts} list</b> of {@code nodeId:outputKey} pairs. Unlike
 * {@code SentimentNodeOptions.textSources}, which takes the first that yields text, <em>all</em>
 * entries are uploaded. When this list is non-empty it is authoritative and auto-discovery is off -
 * merging the two would make it impossible to <em>exclude</em> a discovered artifact.</li>
 * <li><b>Auto-discovery</b> of every upstream output whose key ends in {@code _path}. That
 * convention is honoured by {@code thumbnail_path}, {@code depthmap_path}, {@code imagegen_path}
 * and {@code tts_path}, and correctly excludes {@code depthmap_meta} (JSON, not a path) and every
 * {@code *_flag}. ⚠️ {@code ScriptNode} image outputs are author-named and do <em>not</em> follow
 * it, so a script's images need an explicit entry.</li>
 * <li><b>The media item itself</b>, when {@code includeSource} is set - which is what makes
 * {@code filesystem-source → s3-sink} an archiver.</li>
 * </ol>
 */
public final class ArtifactSelector {

	private static final Logger log = LoggerFactory.getLogger(ArtifactSelector.class);

	private static final String PATH_SUFFIX = "_path";

	private final Path metaPath;

	public ArtifactSelector(Path metaPath) {
		this.metaPath = metaPath;
	}

	/**
	 * @param upstream      upstream node id -&gt; output key -&gt; value
	 * @param media         the media item flowing through the graph
	 * @param artifacts     explicit {@code nodeId:outputKey} selections; may be empty
	 * @param autoDiscover  whether to fall back to {@code *_path} discovery
	 * @param includeSource whether to also upload the media item
	 * @return the artifacts to upload, deduplicated, in a stable order
	 */
	public List<SinkArtifact> select(Map<String, Map<String, Object>> upstream, LoomMedia media,
		List<String> artifacts, boolean autoDiscover, boolean includeSource) {

		List<SinkArtifact> selected = new ArrayList<>();
		Set<Path> seen = new LinkedHashSet<>();

		if (artifacts != null && !artifacts.isEmpty()) {
			for (String entry : artifacts) {
				int colon = entry == null ? -1 : entry.indexOf(':');
				if (colon <= 0 || colon == entry.length() - 1) {
					// Options validation rejects this shape earlier; a surviving one is ignored
					// rather than allowed to abort a run.
					log.warn("Ignoring malformed artifacts entry '{}', expected 'nodeId:outputKey'", entry);
					continue;
				}
				String nodeId = entry.substring(0, colon).trim();
				String key = entry.substring(colon + 1).trim();
				Object value = upstream.getOrDefault(nodeId, Map.of()).get(key);
				if (value == null) {
					log.debug("Configured artifact {}:{} produced no output on this item", nodeId, key);
					continue;
				}
				collect(nodeId, key, value, selected, seen);
			}
		} else if (autoDiscover) {
			upstream.forEach((nodeId, outputs) -> outputs.forEach((key, value) -> {
				if (key != null && key.endsWith(PATH_SUFFIX)) {
					collect(nodeId, key, value, selected, seen);
				}
			}));
		}

		if (includeSource && media != null) {
			addSourceMedia(media, selected, seen);
		}
		return selected;
	}

	/** Accepts a single path, a {@code List}, or the {@code JsonArray} a cached re-emit produces. */
	private void collect(String nodeId, String key, Object value, List<SinkArtifact> out, Set<Path> seen) {
		if (value instanceof String single) {
			add(nodeId, key, 0, false, single, out, seen);
			return;
		}
		if (value instanceof JsonArray array) {
			// ScriptNode emits IMAGE_LIST as a List<String>, but on a LocalResultCache hit it
			// re-emits through new JsonObject(cached), so the same output arrives as a JsonArray.
			collectList(nodeId, key, array.getList(), out, seen);
			return;
		}
		if (value instanceof List<?> list) {
			collectList(nodeId, key, list, out, seen);
			return;
		}
		log.debug("Upstream {}:{} is a {}, not a path or list of paths - ignored",
			nodeId, key, value.getClass().getSimpleName());
	}

	private void collectList(String nodeId, String key, List<?> values, List<SinkArtifact> out, Set<Path> seen) {
		for (int i = 0; i < values.size(); i++) {
			Object element = values.get(i);
			if (element instanceof String path) {
				add(nodeId, key, i, true, path, out, seen);
			} else if (element != null) {
				log.debug("Upstream {}:{}[{}] is not a path - ignored", nodeId, key, i);
			}
		}
	}

	private void add(String nodeId, String key, int index, boolean multiValued, String raw,
		List<SinkArtifact> out, Set<Path> seen) {
		if (raw == null || raw.isBlank()) {
			return;
		}
		Path file = resolve(raw);
		if (file == null) {
			log.warn("Upstream {}:{} value '{}' is not a usable path - ignored", nodeId, key, raw);
			return;
		}
		if (!seen.add(file)) {
			// Two outputs pointing at one file is waste, never intent.
			log.debug("Artifact {} is already selected - dropping the duplicate from {}:{}", file, nodeId, key);
			return;
		}
		// Presence is recorded rather than filtered: "the producer said it wrote this and it is
		// not here" is an affinity failure the node must report, not a silent skip.
		out.add(new SinkArtifact(nodeId, key, index, multiValued, file, Files.isRegularFile(file)));
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
