package io.metaloom.cortex.node.source.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.pipeline.api.node.MediaSourceNode;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.fs.FileIndexStore;
import io.metaloom.fs.FileInfo;
import io.metaloom.fs.FileState;
import io.metaloom.fs.ScanResult;
import io.metaloom.fs.linux.LinuxFileIndex;
import io.metaloom.fs.linux.LinuxFilesystemScanner;
import io.metaloom.fs.linux.impl.AvroFileIndexStore;
import io.metaloom.fs.linux.impl.LinuxFilesystemScannerImpl;
import io.reactivex.rxjava3.core.Flowable;

/**
 * Source node that reads media files from the filesystem as pipeline input.
 *
 * <p>The selection is either a set of path globs or a single root directory. Globs
 * take precedence when both are configured.</p>
 *
 * <p>In <b>root mode</b> the node performs a <em>differential</em> scan using the
 * {@code differential-filesystem-scanner}: a per-root index is persisted locally
 * (Apache Avro) and reloaded on each run, so only files whose {@link FileState} is
 * contained in the configured emit-set (new / modified / moved / present / deleted)
 * flow downstream. This lets re-runs skip unchanged media. In <b>glob mode</b> the
 * node keeps enumerating every match on each run (differential scanning is
 * root-only, because the scanner diffs a single directory tree).</p>
 *
 * <p>Corresponds to the {@code filesystem-source} node descriptor in
 * {@code cortex-source-api}.</p>
 */
public class FilesystemSourceNode extends AbstractPipelineNode implements MediaSourceNode {

	private static final Logger log = LoggerFactory.getLogger(FilesystemSourceNode.class);

	public static final String DEFAULT_ID = "filesystem-source";

	/**
	 * The one output this source declares: a reference to the file that is flowing through the DAG.
	 *
	 * <p>
	 * {@code media/*} rather than a concrete subtype because the mime is not known until the file is
	 * opened — the wildcard-into-subtype arm of the assignability lattice is what lets this wire into
	 * an image- or video-specific consumer, with the real check deferred to the runtime boundary.
	 * </p>
	 */
	public static final OutputPort<String> OUT_MEDIA = OutputPort.one("media", ContentTypeRegistry.MEDIA_ANY, String.class);

	private final LoomMediaLoader mediaLoader;
	private final Path root;
	private final List<String> pathGlobs;
	private final Set<FileState> emitStates;
	private final Path indexBaseDir;

	private final FileIndexStore indexStore = new AvroFileIndexStore();

	/**
	 * Per-run map of emitted absolute path -> diff state, exposed by {@link #process}. Populated by {@link #stream()} on each subscription.
	 */
	private final Map<String, FileState> lastStates = new ConcurrentHashMap<>();

	/**
	 * @param id           node id within the pipeline
	 * @param mediaLoader  loader used to wrap discovered paths as {@link LoomMedia}
	 * @param root         root directory to scan differentially; may be {@code null} if globs are given
	 * @param pathGlobs    globs to expand; takes precedence over {@code root}
	 * @param emitStates   file states emitted downstream in root mode; {@code null}/empty falls back to new+modified+moved
	 * @param indexBaseDir local base directory for the persisted per-root index; required when a root is used
	 */
	public FilesystemSourceNode(String id, LoomMediaLoader mediaLoader, Path root, List<String> pathGlobs,
		Set<FileState> emitStates, Path indexBaseDir) {
		super(id, "Filesystem Source", NodeMode.SEQUENTIAL, true, 1);
		if (mediaLoader == null) {
			throw new IllegalArgumentException("A media loader must be provided");
		}
		boolean hasGlobs = pathGlobs != null && !pathGlobs.isEmpty();
		if (root == null && !hasGlobs) {
			throw new IllegalArgumentException(
				"Node '" + id + "': either a root path or at least one path glob must be configured");
		}
		if (root != null && indexBaseDir == null) {
			throw new IllegalArgumentException(
				"Node '" + id + "': an index base directory is required for differential (root) scanning");
		}
		this.mediaLoader = mediaLoader;
		this.root = root;
		this.pathGlobs = hasGlobs ? List.copyOf(pathGlobs) : List.of();
		this.emitStates = (emitStates == null || emitStates.isEmpty())
			? EnumSet.of(FileState.NEW, FileState.MODIFIED, FileState.MOVED)
			: EnumSet.copyOf(emitStates);
		this.indexBaseDir = indexBaseDir;
		setSource(true);
	}

	/**
	 * Enumerate the configured selection.
	 *
	 * <p>The returned flowable is cold — the filesystem is scanned on subscription,
	 * so a node instance registered once in a pipeline re-scans on every run.</p>
	 *
	 * <p>The glob path is walked lazily, so the first media item is dispatched before the
	 * walk has finished and a cancelled or backpressured run stops the walk instead of
	 * paying for the rest of it. {@code Flowable.fromStream} closes the walk on both
	 * completion and cancellation. The count is therefore only known at the end, and is
	 * logged there rather than up front.</p>
	 *
	 * <p>The differential (root) path stays eager: a differential scan compares the whole
	 * tree against the persisted index and writes the updated index back, so the set of
	 * changed files does not exist until the scan has finished.</p>
	 */
	@Override
	public Flowable<LoomMedia> stream() {
		return Flowable.defer(() -> {
			lastStates.clear();
			if (!pathGlobs.isEmpty()) {
				log.info("Node '{}' scanning globs {} (differential scanning is root-only)", id(), pathGlobs);
				AtomicLong emitted = new AtomicLong();
				return Flowable.fromStream(FilesystemMediaScanner.stream(pathGlobs))
					.doOnNext(p -> emitted.incrementAndGet())
					.doOnComplete(() -> log.info("Node '{}' resolved {} media file(s) from globs {}",
						id(), emitted.get(), pathGlobs));
			}
			List<Path> paths = differentialScan();
			log.info("Node '{}' resolved {} changed media file(s) from root {} (emitStates={})",
				id(), paths.size(), root, emitStates);
			return Flowable.fromIterable(paths);
		}).map(mediaLoader::load);
	}

	/**
	 * Run a differential scan of the root against the persisted index, persist the updated index and return the paths whose state is in the emit-set.
	 */
	private List<Path> differentialScan() throws IOException {
		if (root == null || !Files.exists(root)) {
			log.debug("Node '{}' root {} does not exist — nothing to emit", id(), root);
			return List.of();
		}

		Path indexFile = indexFileFor(root);
		LinuxFileIndex index = indexStore.load(indexFile);
		LinuxFilesystemScanner scanner = new LinuxFilesystemScannerImpl(index);
		ScanResult result = scanner.scan(root);
		indexStore.store(indexFile, scanner.getIndex());

		List<Path> emitted = new ArrayList<>();
		collect(result.added(), FileState.NEW, emitted);
		collect(result.modified(), FileState.MODIFIED, emitted);
		collect(result.moved(), FileState.MOVED, emitted);
		collect(result.present(), FileState.PRESENT, emitted);
		collect(result.deleted(), FileState.DELETED, emitted);
		return emitted;
	}

	private void collect(Set<FileInfo> infos, FileState state, List<Path> emitted) {
		if (!emitStates.contains(state)) {
			return;
		}
		for (FileInfo info : infos) {
			Path path = info.path().toAbsolutePath().normalize();
			emitted.add(path);
			lastStates.put(path.toString(), state);
		}
	}

	/**
	 * Resolve the dedicated, folder-specific index file for a root. The file name is derived from a hash of the absolute root path, so each start path obtains
	 * its own index.
	 */
	private Path indexFileFor(Path root) {
		String canonical = root.toAbsolutePath().normalize().toString();
		return indexBaseDir.resolve(sha256Hex(canonical) + ".avro");
	}

	private static String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is a required algorithm on every JRE.
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	/**
	 * Emits a reference to the media item currently flowing through the DAG. The actual enumeration happens in {@link #stream()}; this only puts the
	 * reference on the declared output port so downstream nodes can be wired to it.
	 *
	 * <p>
	 * The diff state is deliberately not an output: it is scan bookkeeping, not pipeline data, and the descriptor declares exactly one port. It stays
	 * available through {@link #lastState(String)} for reporting.
	 * </p>
	 */
	@Override
	public NodeResult process(LoomMedia media, NodeInputs inputs) {
		return NodeResult.success(id(), 0,
			Map.of(OUT_MEDIA.id(), new PortOutput(OUT_MEDIA, List.of(media.absolutePath()))));
	}

	/**
	 * The diff state the last scan recorded for the given path, or {@link FileState#UNKNOWN}.
	 */
	public FileState lastState(String absolutePath) {
		FileState state = lastStates.get(absolutePath);
		return state != null ? state : FileState.UNKNOWN;
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
	 * The file states that are emitted downstream in root mode.
	 */
	public Set<FileState> emitStates() {
		return emitStates;
	}

	/**
	 * Build a node from a pipeline JSON node definition, falling back to the configured defaults when the definition omits a selection.
	 *
	 * @param id           node id
	 * @param mediaLoader  media loader
	 * @param path         {@code path} from the definition, may be {@code null}
	 * @param globs        {@code pathGlobs} from the definition, may be empty
	 * @param emitStates   {@code emitStates} from the definition, may be empty
	 * @param defaults     configured defaults, may be {@code null}
	 * @param indexBaseDir local base directory for the persisted per-root index
	 * @return a configured node
	 */
	public static FilesystemSourceNode create(String id, LoomMediaLoader mediaLoader, String path,
		List<String> globs, List<String> emitStates, FilesystemSourceNodeOptions defaults, Path indexBaseDir) {

		List<String> effectiveGlobs = globs != null && !globs.isEmpty()
			? globs
			: (defaults != null ? defaults.getPathGlobs() : List.of());

		String effectivePath = path != null && !path.isBlank()
			? path
			: (defaults != null ? defaults.getPath() : null);

		List<String> effectiveStates = emitStates != null && !emitStates.isEmpty()
			? emitStates
			: (defaults != null ? defaults.getEmitStates() : FilesystemSourceNodeOptions.DEFAULT_EMIT_STATES);

		Path root = effectivePath != null && !effectivePath.isBlank()
			? Paths.get(effectivePath).toAbsolutePath().normalize()
			: null;

		return new FilesystemSourceNode(id, mediaLoader, root, effectiveGlobs, parseStates(effectiveStates), indexBaseDir);
	}

	/**
	 * Parse file state names into an {@link EnumSet}, ignoring blank/unknown entries (options validation rejects unknown states earlier).
	 */
	static Set<FileState> parseStates(List<String> names) {
		Set<FileState> states = EnumSet.noneOf(FileState.class);
		if (names == null) {
			return states;
		}
		for (String name : names) {
			if (name == null || name.isBlank()) {
				continue;
			}
			try {
				states.add(FileState.valueOf(name.trim().toUpperCase()));
			} catch (IllegalArgumentException e) {
				log.warn("Ignoring unknown file state '{}' in emitStates", name);
			}
		}
		return states;
	}
}
