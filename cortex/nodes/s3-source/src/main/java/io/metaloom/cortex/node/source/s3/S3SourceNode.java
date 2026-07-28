package io.metaloom.cortex.node.source.s3;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.node.MediaSourceNode;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.cortex.s3.S3LoomMedia;
import io.metaloom.cortex.s3.S3MediaMaterializer;
import io.metaloom.cortex.s3.S3ObjectRef;
import io.metaloom.fs.FileState;
import io.reactivex.rxjava3.core.Flowable;

/**
 * Source node that reads media objects from S3-compatible object storage.
 *
 * <p>A <em>differential</em> source: a per-selection index is persisted locally (Apache Avro) and
 * reloaded on each run, so only objects whose {@link FileState} is in the configured emit-set flow
 * downstream and a re-run over an unchanged bucket emits nothing. When bucket notifications are
 * enabled the run skips listing entirely and processes only the keys the bucket reported - with a
 * periodic full listing to recover anything a lost notification would otherwise have hidden. See
 * {@link S3DifferentialScanner}.</p>
 *
 * <p><b>Nothing is downloaded here.</b> The node emits {@link S3LoomMedia} handles carrying an
 * {@code s3://bucket/key} reference; the bytes are fetched by whichever worker later runs a node
 * task against them. That is what lets a bucket be processed by workers that share no storage -
 * the constraint recorded in {@code MediaRef}'s Javadoc.</p>
 */
public class S3SourceNode extends AbstractPipelineNode implements MediaSourceNode {

	private static final Logger log = LoggerFactory.getLogger(S3SourceNode.class);

	public static final String DEFAULT_ID = "s3-source";

	/**
	 * The reference of the current item.
	 *
	 * <p>Named {@code path} and emitted alongside {@code uri} on purpose: {@code filesystem-source}
	 * and {@code asset-source} both emit {@code path}, so a downstream node that reads it keeps
	 * working when the source is swapped for this one. The value is the {@code s3://} reference
	 * rather than a local path - the object may not be materialized anywhere yet.</p>
	 */
	private static final String OUTPUT_PATH = "path";

	private static final String OUTPUT_URI = "uri";
	private static final String OUTPUT_BUCKET = "bucket";
	private static final String OUTPUT_KEY = "key";
	private static final String OUTPUT_SOURCE = "source";
	private static final String OUTPUT_STATE = "state";

	private final S3DifferentialScanner scanner;
	private final S3MediaMaterializer materializer;
	private final S3Selection selection;

	/**
	 * Per-run map of emitted reference -&gt; diff state, read back by {@link #process}. Populated by
	 * {@link #stream()} on each subscription.
	 *
	 * <p>⚠️ In-JVM only. When this node's own NODE_TASK is dispatched to a different worker than
	 * the one that ran the SOURCE_TASK, the state reads {@code UNKNOWN}. {@code FilesystemSourceNode}
	 * has the same limitation; it is recorded in the node spec rather than worked around here.</p>
	 */
	private final Map<String, FileState> lastStates = new ConcurrentHashMap<>();

	public S3SourceNode(String id, S3DifferentialScanner scanner, S3MediaMaterializer materializer,
		S3Selection selection) {
		super(id, "S3 Source", NodeMode.SEQUENTIAL, true, 1);
		if (scanner == null) {
			throw new IllegalArgumentException("Node '" + id + "': an S3 scanner must be provided");
		}
		if (materializer == null) {
			throw new IllegalArgumentException("Node '" + id + "': an S3 materializer must be provided");
		}
		if (selection == null) {
			throw new IllegalArgumentException("Node '" + id + "': an S3 selection must be provided");
		}
		this.scanner = scanner;
		this.materializer = materializer;
		this.selection = selection;
		setSource(true);
	}

	/**
	 * Enumerate the configured selection.
	 *
	 * <p>Cold: the bucket is scanned on subscription, so a node instance registered once re-scans
	 * on every run and picks up objects added since the previous one.</p>
	 */
	@Override
	public Flowable<LoomMedia> stream() {
		return Flowable.defer(() -> {
			lastStates.clear();
			S3ScanResult result = scanner.scan(selection, System.currentTimeMillis());
			lastStates.putAll(result.states());
			log.info("Node '{}' resolved {} changed object(s) from s3://{}/{} (mode={}, emitStates={})",
				id(), result.size(), selection.bucket(),
				selection.prefix() == null ? "" : selection.prefix(),
				result.mode(), selection.emitStates());
			return Flowable.fromIterable(result.objects());
		}).map(this::toMedia);
	}

	/** Wrap an object as a media handle. No bytes are fetched until something asks for them. */
	private LoomMedia toMedia(S3ObjectRef ref) {
		return new S3LoomMedia(ref, materializer);
	}

	/**
	 * Report the reference and diff state of the object currently flowing through the DAG. The
	 * enumeration itself happens in {@link #stream()}; this only records provenance for downstream
	 * nodes and the UI.
	 */
	@Override
	public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		String reference = media.reference();
		FileState state = lastStates.get(reference);
		String bucket = selection.bucket();
		String key = reference;
		if (media instanceof S3LoomMedia s3Media) {
			bucket = s3Media.ref().bucket();
			key = s3Media.ref().key();
		}
		return NodeResult.success(id(), 0, Map.of(
			OUTPUT_PATH, reference,
			OUTPUT_URI, reference,
			OUTPUT_BUCKET, bucket,
			OUTPUT_KEY, key,
			OUTPUT_SOURCE, "s3",
			OUTPUT_STATE, state != null ? state.name() : FileState.UNKNOWN.name()));
	}

	/**
	 * @return the configured selection
	 */
	public S3Selection selection() {
		return selection;
	}

	/**
	 * Build a node from a pipeline JSON node definition, falling back to the worker's configured
	 * defaults when the definition omits a value.
	 *
	 * @param id           node id
	 * @param scanner      the differential scanner
	 * @param materializer the lazy materializer
	 * @param bucket       {@code bucket} from the definition, may be null
	 * @param prefix       {@code prefix} from the definition, may be null
	 * @param suffixes     {@code suffixes} from the definition, may be null
	 * @param emitStates   {@code emitStates} from the definition, may be empty
	 * @param startAfter   {@code startAfter} from the definition, may be null
	 * @param useEvents    {@code useEvents} from the definition, may be null
	 * @param defaults     configured defaults, may be null
	 * @return a configured node
	 */
	public static S3SourceNode create(String id, S3DifferentialScanner scanner, S3MediaMaterializer materializer,
		String bucket, String prefix, String suffixes, List<String> emitStates,
		Boolean startAfter, Boolean useEvents, S3SourceNodeOptions defaults) {

		String effectiveBucket = firstNonBlank(bucket, defaults == null ? null : defaults.getBucket());
		if (effectiveBucket == null) {
			throw new IllegalArgumentException("Node '" + id + "' (s3-source) requires a 'bucket' option");
		}
		String effectivePrefix = firstNonBlank(prefix, defaults == null ? null : defaults.getPrefix());
		String effectiveSuffixes = firstNonBlank(suffixes, defaults == null ? null : defaults.getSuffixes());

		List<String> effectiveStates = emitStates != null && !emitStates.isEmpty()
			? emitStates
			: (defaults != null ? defaults.getEmitStates() : S3SourceNodeOptions.DEFAULT_EMIT_STATES);

		boolean effectiveStartAfter = startAfter != null
			? startAfter
			: defaults != null && defaults.isStartAfter();
		boolean effectiveUseEvents = useEvents != null
			? useEvents
			: defaults != null && defaults.isUseEvents();

		Set<FileState> states = S3Selection.parseStates(effectiveStates);
		S3Selection selection = new S3Selection(effectiveBucket, effectivePrefix,
			S3Selection.parseSuffixes(effectiveSuffixes), states, effectiveStartAfter, effectiveUseEvents);
		return new S3SourceNode(id, scanner, materializer, selection);
	}

	private static String firstNonBlank(String preferred, String fallback) {
		if (preferred != null && !preferred.isBlank()) {
			return preferred;
		}
		return fallback != null && !fallback.isBlank() ? fallback : null;
	}
}
