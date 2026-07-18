package io.metaloom.cortex.node.source.fs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.node.MediaSourceNode;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.reactivex.rxjava3.core.Flowable;

/**
 * Source node that reads media files from the filesystem as pipeline input.
 *
 * <p>The selection is either a set of path globs or a single root directory that
 * is walked recursively. Globs take precedence when both are configured. Because
 * the node implements {@link MediaSourceNode}, a pipeline built around it can be
 * executed without the caller discovering media itself:</p>
 *
 * <pre>
 * pipelineExecutor.execute(pipeline, runContext);
 * </pre>
 *
 * <p>Corresponds to the {@code filesystem-source} node descriptor in
 * {@code cortex-source-api}.</p>
 */
public class FilesystemSourceNode extends AbstractPipelineNode implements MediaSourceNode {

	private static final Logger log = LoggerFactory.getLogger(FilesystemSourceNode.class);

	public static final String DEFAULT_ID = "filesystem-source";

	private static final String OUTPUT_PATH = "path";
	private static final String OUTPUT_SOURCE = "source";

	private final LoomMediaLoader mediaLoader;
	private final Path root;
	private final List<String> pathGlobs;

	/**
	 * @param id          node id within the pipeline
	 * @param mediaLoader loader used to wrap discovered paths as {@link LoomMedia}
	 * @param root        root directory to walk; may be {@code null} if globs are given
	 * @param pathGlobs   globs to expand; takes precedence over {@code root}
	 */
	public FilesystemSourceNode(String id, LoomMediaLoader mediaLoader, Path root, List<String> pathGlobs) {
		super(id, "Filesystem Source", NodeMode.SEQUENTIAL, true, 1);
		if (mediaLoader == null) {
			throw new IllegalArgumentException("A media loader must be provided");
		}
		boolean hasGlobs = pathGlobs != null && !pathGlobs.isEmpty();
		if (root == null && !hasGlobs) {
			throw new IllegalArgumentException(
				"Node '" + id + "': either a root path or at least one path glob must be configured");
		}
		this.mediaLoader = mediaLoader;
		this.root = root;
		this.pathGlobs = hasGlobs ? List.copyOf(pathGlobs) : List.of();
		setSource(true);
	}

	/**
	 * Enumerate the configured selection.
	 *
	 * <p>The returned flowable is cold — the filesystem is walked on subscription,
	 * so a node instance registered once in a pipeline re-scans on every run and
	 * picks up files added since the last one.</p>
	 */
	@Override
	public Flowable<LoomMedia> stream() {
		return Flowable.defer(() -> {
			List<Path> paths = scan();
			log.info("Node '{}' resolved {} media file(s) from {}", id(), paths.size(), describeSelection());
			return Flowable.fromIterable(paths);
		}).map(mediaLoader::load);
	}

	private List<Path> scan() throws Exception {
		if (!pathGlobs.isEmpty()) {
			return FilesystemMediaScanner.expand(pathGlobs);
		}
		return FilesystemMediaScanner.walk(root);
	}

	/**
	 * Emits the resolved path for the media item currently flowing through the DAG.
	 * The actual enumeration happens in {@link #stream()}; this only records the
	 * source in the per-media result map for downstream nodes and the UI.
	 */
	@Override
	public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		return NodeResult.success(id(), 0, Map.of(
			OUTPUT_PATH, media.absolutePath(),
			OUTPUT_SOURCE, "filesystem"));
	}

	private String describeSelection() {
		return pathGlobs.isEmpty() ? "root " + root : "globs " + pathGlobs;
	}

	/**
	 * The configured root directory, or {@code null} when globs are used.
	 */
	public Path root() {
		return root;
	}

	/**
	 * The configured path globs, empty when a root directory is used.
	 */
	public List<String> pathGlobs() {
		return pathGlobs;
	}

	/**
	 * Build a node from a pipeline JSON node definition, falling back to the
	 * configured defaults when the definition omits a selection.
	 *
	 * @param id          node id
	 * @param mediaLoader media loader
	 * @param path        {@code path} from the definition, may be {@code null}
	 * @param globs       {@code pathGlobs} from the definition, may be empty
	 * @param defaults    configured defaults, may be {@code null}
	 * @return a configured node
	 */
	public static FilesystemSourceNode create(String id, LoomMediaLoader mediaLoader, String path,
		List<String> globs, FilesystemSourceNodeOptions defaults) {

		List<String> effectiveGlobs = globs != null && !globs.isEmpty()
			? globs
			: (defaults != null ? defaults.getPathGlobs() : List.of());

		String effectivePath = path != null && !path.isBlank()
			? path
			: (defaults != null ? defaults.getPath() : null);

		Path root = effectivePath != null && !effectivePath.isBlank()
			? Paths.get(effectivePath).toAbsolutePath().normalize()
			: null;

		return new FilesystemSourceNode(id, mediaLoader, root, effectiveGlobs);
	}
}
