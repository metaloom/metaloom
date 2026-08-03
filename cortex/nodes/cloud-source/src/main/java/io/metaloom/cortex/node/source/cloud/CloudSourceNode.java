package io.metaloom.cortex.node.source.cloud;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.ParamOverride;
import io.metaloom.cortex.api.node.spec.PortDoc;
import io.metaloom.cortex.cloud.CloudFileRef;
import io.metaloom.cortex.cloud.CloudLoomMedia;
import io.metaloom.cortex.cloud.CloudMediaMaterializer;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.node.MediaSourceNode;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.fs.FileState;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.reactivex.rxjava3.core.Flowable;

/**
 * Source node that reads media files from a cloud drive.
 *
 * <p>One class serves both {@code gdrive-source} and {@code onedrive-source}: the scanning,
 * indexing and emission are provider-agnostic, and the only difference is which
 * {@code CloudFileStore} the scanner and materializer were built with. The kinds stay separate at
 * registration time so a worker advertises only the cloud it actually holds credentials for.</p>
 *
 * <p>A <em>differential</em> source: a per-selection index is persisted locally (Apache Avro) and
 * reloaded on each run, so only files whose {@link FileState} is in the configured emit-set flow
 * downstream and a re-run over an unchanged folder emits nothing. Because cloud file ids are
 * stable, {@code MOVED} is genuinely detected here - see {@link CloudDifferentialScanner}.</p>
 *
 * <p><b>Nothing is downloaded here.</b> The node emits {@link CloudLoomMedia} handles carrying a
 * {@code gdrive://} or {@code onedrive://} reference; the bytes are fetched by whichever worker
 * later runs a node task against them. That is what lets one drive be processed by workers sharing
 * no storage.</p>
 */
/*
 * <p>
 * The scanning is provider-agnostic; only the bound file store differs. The two node <em>contracts</em>
 * are not, though - {@code gdrive-source} and {@code onedrive-source} differ in name, icon,
 * description, the prose on six of their nine shared parameters, and one parameter gdrive has that
 * onedrive deliberately refuses. Each therefore has its own thin subclass to carry its own
 * {@code @NodeSpec}: {@link GDriveSourceNode} and {@link OneDriveSourceNode}. They add no behaviour.
 * </p>
 */
public class CloudSourceNode extends AbstractPipelineNode implements MediaSourceNode {

	private static final Logger log = LoggerFactory.getLogger(CloudSourceNode.class);

	/**
	 * The reference of the current item.
	 *
	 * <p>Named {@code media}, exactly as every other source names its output, so a downstream node
	 * stays wired when the source is swapped. The value is the cloud reference rather than a local
	 * path - the file may not be materialized anywhere yet.</p>
	 *
	 * <p>{@code media/*} because the concrete kind is only known once the file is fetched.</p>
	 */
	@PortDoc(label = "Media", description = "Every file the scan emitted. The concrete kind is only known once the file is fetched")
	public static final OutputPort<String> OUT_MEDIA =
		OutputPort.one("media", ContentTypeRegistry.MEDIA_ANY, String.class);

	private final CloudDifferentialScanner scanner;
	private final CloudMediaMaterializer materializer;
	private final CloudSelection selection;

	/**
	 * Per-run map of emitted reference -&gt; diff state, read back by {@link #lastState}. Populated
	 * by {@link #stream()} on each subscription.
	 *
	 * <p>⚠️ In-JVM only. When this node's own NODE_TASK is dispatched to a different worker than the
	 * one that ran the SOURCE_TASK, the state reads {@code UNKNOWN}. Both other source nodes share
	 * the limitation; it matters slightly more here because {@code MOVED} is a state this source can
	 * actually produce.</p>
	 */
	private final Map<String, FileState> lastStates = new ConcurrentHashMap<>();

	public CloudSourceNode(String id, CloudDifferentialScanner scanner, CloudMediaMaterializer materializer,
		CloudSelection selection) {
		super(id, nameOf(selection), NodeMode.SEQUENTIAL, true, 1);
		if (scanner == null) {
			throw new IllegalArgumentException("Node '" + id + "': a cloud scanner must be provided");
		}
		if (materializer == null) {
			throw new IllegalArgumentException("Node '" + id + "': a cloud materializer must be provided");
		}
		if (selection == null) {
			throw new IllegalArgumentException("Node '" + id + "': a cloud selection must be provided");
		}
		this.scanner = scanner;
		this.materializer = materializer;
		this.selection = selection;
		setSource(true);
	}

	private static String nameOf(CloudSelection selection) {
		return selection == null ? "Cloud Source" : selection.provider().displayName() + " Source";
	}

	/**
	 * Enumerate the configured selection.
	 *
	 * <p>Cold: the drive is scanned on subscription, so a node instance registered once re-scans on
	 * every run and picks up files added since the previous one.</p>
	 */
	@Override
	public Flowable<LoomMedia> stream() {
		return Flowable.defer(() -> {
			lastStates.clear();
			CloudScanResult result = scanner.scan(selection, System.currentTimeMillis());
			lastStates.putAll(result.states());
			log.info("Node '{}' resolved {} changed file(s) from {} {}/{} (mode={}, emitStates={})",
				id(), result.size(), selection.provider().displayName(), selection.driveId(),
				selection.folderId() == null ? "" : selection.folderId(),
				result.mode(), selection.emitStates());
			return Flowable.fromIterable(result.files());
		}).map(this::toMedia);
	}

	/** Wrap a file as a media handle. No bytes are fetched until something asks for them. */
	private LoomMedia toMedia(CloudFileRef ref) {
		return new CloudLoomMedia(ref, materializer);
	}

	/**
	 * Report the reference of the file currently flowing through the DAG. The enumeration itself
	 * happens in {@link #stream()}; this only records provenance for downstream nodes and the UI.
	 *
	 * <p>Emits {@code reference()}, never {@code absolutePath()} - the latter would materialize the
	 * file just to name it.</p>
	 */
	@Override
	public NodeResult process(LoomMedia media, NodeInputs inputs) {
		String reference = media.reference();
		return NodeResult.success(id(), 0,
			Map.of(OUT_MEDIA.id(), new PortOutput(OUT_MEDIA, List.of(reference))));
	}

	/**
	 * The diff state the last scan recorded for the given reference, or {@link FileState#UNKNOWN}.
	 *
	 * <p>Drive, folder and diff state are scan bookkeeping rather than pipeline data - the
	 * descriptor declares one port - so they are read here instead of being emitted.</p>
	 *
	 * @param reference a media reference
	 * @return the recorded state
	 */
	public FileState lastState(String reference) {
		FileState state = lastStates.get(reference);
		return state != null ? state : FileState.UNKNOWN;
	}

	/**
	 * @return the configured selection
	 */
	public CloudSelection selection() {
		return selection;
	}

	/**
	 * Build a node from a pipeline JSON node definition, falling back to the worker's configured
	 * defaults when the definition omits a value.
	 *
	 * @param id             node id
	 * @param scanner        the differential scanner
	 * @param materializer   the lazy materializer
	 * @param resolvedDriveId the drive id the store resolved, already non-blank
	 * @param folderId       {@code folderId} from the definition, may be null
	 * @param recursive      {@code recursive} from the definition, may be null
	 * @param maxDepth       {@code maxDepth} from the definition, may be null
	 * @param suffixes       {@code suffixes} from the definition, may be null
	 * @param mimeTypes      {@code mimeTypes} from the definition, may be null
	 * @param emitStates     {@code emitStates} from the definition, may be empty
	 * @param useDelta       {@code useDelta} from the definition, may be null
	 * @param includeTrashed {@code includeTrashed} from the definition, may be null
	 * @param exportDocs     the effective exportNativeDocs setting
	 * @param defaults       configured defaults, may be null
	 * @return a configured node
	 */
	public static CloudSourceNode create(String id, CloudDifferentialScanner scanner,
		CloudMediaMaterializer materializer, CloudProviderId provider, String resolvedDriveId,
		String folderId, Boolean recursive, Integer maxDepth, String suffixes, String mimeTypes,
		List<String> emitStates, Boolean useDelta, Boolean includeTrashed, boolean exportDocs,
		CloudSourceNodeOptions<?> defaults) {

		String effectiveFolder = firstNonBlank(folderId, defaults == null ? null : defaults.getFolderId());
		String effectiveSuffixes = firstNonBlank(suffixes, defaults == null ? null : defaults.getSuffixes());
		String effectiveMimeTypes = firstNonBlank(mimeTypes, defaults == null ? null : defaults.getMimeTypes());

		boolean effectiveRecursive = recursive != null
			? recursive
			: defaults == null || defaults.isRecursive();
		int effectiveMaxDepth = maxDepth != null
			? maxDepth
			: defaults == null ? 0 : defaults.getMaxDepth();
		boolean effectiveUseDelta = useDelta != null
			? useDelta
			: defaults == null || defaults.isUseDelta();
		boolean effectiveIncludeTrashed = includeTrashed != null
			? includeTrashed
			: defaults != null && defaults.isIncludeTrashed();

		List<String> effectiveStates = emitStates != null && !emitStates.isEmpty()
			? emitStates
			: defaults != null ? defaults.getEmitStates() : CloudSourceNodeOptions.DEFAULT_EMIT_STATES;

		Set<FileState> states = CloudSelection.parseStates(effectiveStates);
		CloudSelection selection = new CloudSelection(provider, resolvedDriveId, effectiveFolder,
			effectiveRecursive, effectiveMaxDepth,
			CloudSelection.parseSuffixes(effectiveSuffixes),
			CloudSelection.parseMimeTypes(effectiveMimeTypes),
			states, effectiveUseDelta, effectiveIncludeTrashed, exportDocs);
		// The subclass carries the contract, not the behaviour: an unknown provider still gets a working
		// node, it simply has no annotated contract to announce.
		return switch (provider) {
			case GDRIVE -> new GDriveSourceNode(id, scanner, materializer, selection);
			case ONEDRIVE -> new OneDriveSourceNode(id, scanner, materializer, selection);
		};
	}

	private static String firstNonBlank(String preferred, String fallback) {
		if (preferred != null && !preferred.isBlank()) {
			return preferred;
		}
		return fallback != null && !fallback.isBlank() ? fallback : null;
	}
}
