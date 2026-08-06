package io.metaloom.cortex.api.node.spec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.nodes.spec.NodeDescriptor;

/**
 * Resolves which node classes exist on this worker, and harvests the ones it can actually run.
 *
 * <h2>Why discovery is separate from the Dagger registry</h2>
 *
 * <p>
 * The executable-node registry is a {@code Map<String, Provider<FilesystemNode>>} multibinding, and
 * the {@code Provider} is load-bearing: it keeps a node <em>uninstantiated</em> until a task of its
 * kind arrives, so booting a worker never constructs nodes that pull heavy native dependencies. A
 * {@code Provider} therefore cannot tell us the node's class without calling {@code get()} — which
 * would defeat the very laziness it exists to provide, for all 34 nodes, on every worker start.
 * </p>
 *
 * <p>
 * So discovery runs on class literals instead: built-ins come from a name list resolved with
 * {@code Class.forName(name, false, loader)} — <strong>initialize = false</strong>, so merely
 * discovering a node does not run its static initializer — and third parties contribute through
 * {@link NodeSpecSource}. Reading the port constants does initialize the class, and that happens only
 * for a node the worker is registered to run, which it would load on its first task anyway.
 * </p>
 */
public final class NodeSpecCatalog {

	private static final Logger log = LoggerFactory.getLogger(NodeSpecCatalog.class);

	/**
	 * The nodes that ship with Cortex, by class name.
	 *
	 * <p>
	 * Named rather than referenced so this class stays in {@code cortex/api}, below every node module,
	 * and so a build that omits a node module skips it instead of failing to link. A node missing from
	 * this list is still runnable — it simply cannot be authored, which is the defect this whole
	 * mechanism removes, so keep it in step with the {@code @StringKey} bindings.
	 * </p>
	 */
	private static final List<String> BUILT_IN_NODE_CLASSES = List.of(
		"io.metaloom.cortex.node.source.fs.FilesystemSourceNode",
		"io.metaloom.cortex.node.source.s3.S3SourceNode",
		// Two contracts, two thin subclasses - the base class carries the behaviour and no @NodeSpec.
		"io.metaloom.cortex.node.source.cloud.GDriveSourceNode",
		"io.metaloom.cortex.node.source.cloud.OneDriveSourceNode",
		"io.metaloom.cortex.node.hash.MD5Node",
		"io.metaloom.cortex.node.hash.SHA256Node",
		"io.metaloom.cortex.node.hash.SHA512Node",
		"io.metaloom.cortex.node.hash.ChunkHashNode",
		"io.metaloom.cortex.node.consistency.ConsistencyNode",
		"io.metaloom.cortex.node.fp.FingerprintNode",
		"io.metaloom.cortex.node.thumbnail.ThumbnailNode",
		"io.metaloom.cortex.node.facedetect.FacedetectNode",
		"io.metaloom.cortex.node.facedescription.FacedescriptionNode",
		"io.metaloom.cortex.node.objectdetect.ObjectDetectNode",
		"io.metaloom.cortex.node.ocr.OCRNode",
		"io.metaloom.cortex.node.tika.TikaNode",
		"io.metaloom.cortex.node.metadata.MetadataNode",
		"io.metaloom.cortex.node.quality.QualityNode",
		"io.metaloom.cortex.node.whisper.WhisperNode",
		"io.metaloom.cortex.node.sentiment.SentimentNode",
		"io.metaloom.cortex.node.depthmap.DepthmapNode",
		"io.metaloom.cortex.node.sam2.Sam2Node",
		"io.metaloom.cortex.node.scenelayout.SceneLayoutNode",
		"io.metaloom.cortex.node.color.DominantColorNode",
		"io.metaloom.cortex.node.scene.SceneDetectionNode",
		"io.metaloom.cortex.node.captioning.CaptioningNode",
		"io.metaloom.cortex.node.tts.TtsNode",
		"io.metaloom.cortex.node.imagegen.ImageGenNode",
		"io.metaloom.cortex.node.imagemanip.ImageManipulationNode",
		"io.metaloom.cortex.node.watermark.WatermarkNode",
		"io.metaloom.cortex.node.sink.s3.S3SinkNode",
		"io.metaloom.cortex.node.filter.FilterNode",
		"io.metaloom.cortex.node.translate.TranslateNode",
		"io.metaloom.cortex.node.guard.GuardNode",
		"io.metaloom.cortex.node.tag.TagNode",
		// There is no DedupNode class - the name this list carried until now resolved to nothing, so
		// neither dedup node was ever discoverable. The dedup module binds HashDedupNode (under both
		// "hash-dedup" and the "sha512-dedup" alias), FingerprintDedupNode and FingerprintDedupApplyNode.
		"io.metaloom.cortex.node.dedup.HashDedupNode",
		"io.metaloom.cortex.node.dedup.FingerprintDedupApplyNode",
		"io.metaloom.cortex.node.dedup.FingerprintDedupNode",
		"io.metaloom.cortex.node.script.ScriptNode",
		// The class is LLMNode, not LlmNode - the name this list carried resolved to nothing, so the
		// llm node was never discoverable.
		"io.metaloom.cortex.node.llm.LLMNode",
		"io.metaloom.cortex.node.vlm.VlmNode",
		"io.metaloom.cortex.node.videogen.VideoGenNode");

	private NodeSpecCatalog() {
	}

	/**
	 * Every annotated node class visible to this class loader, keyed by the node id it declares.
	 *
	 * <p>
	 * No class is initialized here: the node ids come from the annotation, which is readable without
	 * running any of the class's code.
	 * </p>
	 */
	public static Map<String, Class<?>> discover(ClassLoader loader) {
		ClassLoader classLoader = loader == null ? NodeSpecCatalog.class.getClassLoader() : loader;
		Map<String, Class<?>> byNodeId = new LinkedHashMap<>();

		for (Class<?> nodeClass : allNodeClasses(classLoader)) {
			NodeSpec spec = nodeClass.getAnnotation(NodeSpec.class);
			if (spec == null) {
				log.debug("Node class {} carries no @NodeSpec and cannot be announced", nodeClass.getName());
				continue;
			}
			Class<?> previous = byNodeId.put(spec.nodeId(), nodeClass);
			if (previous != null && previous != nodeClass) {
				log.warn("Two node classes both declare node id '{}': {} and {}. The later one wins.",
					spec.nodeId(), previous.getName(), nodeClass.getName());
			}
		}
		return byNodeId;
	}

	/**
	 * Harvest the contracts for the nodes this worker can actually run.
	 *
	 * <p>
	 * The intersection with {@code runnableNodeIds} is the point, not a detail: announcing a contract
	 * for a node the worker cannot execute would put it in the palette and then fail at dispatch, and
	 * harvesting a node the worker will never run is what would load native libraries for no reason.
	 * </p>
	 *
	 * @param runnableNodeIds
	 *            what the node factory reports it can execute, already narrowed by the worker's
	 *            whitelist and blacklist
	 */
	public static List<NodeDescriptor> harvestRunnable(Set<String> runnableNodeIds, ClassLoader loader) {
		if (runnableNodeIds == null || runnableNodeIds.isEmpty()) {
			return List.of();
		}
		Map<String, Class<?>> discovered = discover(loader);
		List<NodeDescriptor> descriptors = new ArrayList<>();
		Set<String> missing = new LinkedHashSet<>();

		for (String nodeId : runnableNodeIds) {
			Class<?> nodeClass = discovered.get(nodeId);
			if (nodeClass == null) {
				missing.add(nodeId);
				continue;
			}
			try {
				descriptors.add(NodeSpecHarvester.harvest(nodeClass));
			} catch (RuntimeException | LinkageError e) {
				// One node that cannot be harvested must not cost the worker its other contracts -
				// the same rule Loom applies when rejecting per node rather than per frame.
				log.warn("Could not harvest the contract for node '{}' ({}); it stays runnable but unauthorable",
					nodeId, nodeClass.getName(), e);
			}
		}
		if (!missing.isEmpty()) {
			log.info("These node types are runnable but announce no contract, so they will not appear in the "
				+ "pipeline editor: {}. Annotate the node class with @NodeSpec, or contribute it through a "
				+ "NodeSpecSource.", missing);
		}
		return descriptors;
	}

	private static Collection<Class<?>> allNodeClasses(ClassLoader classLoader) {
		Set<Class<?>> classes = new LinkedHashSet<>();

		for (String className : BUILT_IN_NODE_CLASSES) {
			try {
				// initialize = false: discovery must never run a node's static block. FingerprintNode
				// calls Video4j.init() in one, and loading native libraries to build a JSON payload
				// would be an unpleasant surprise on a worker that runs neither.
				classes.add(Class.forName(className, false, classLoader));
			} catch (ClassNotFoundException | LinkageError e) {
				// The module is not part of this build. Normal for a trimmed worker image.
				log.debug("Node class {} is not on the class path", className);
			}
		}

		try {
			for (NodeSpecSource source : ServiceLoader.load(NodeSpecSource.class, classLoader)) {
				Collection<Class<?>> contributed = source.nodeClasses();
				if (contributed != null) {
					classes.addAll(contributed);
				}
			}
		} catch (ServiceConfigurationError | RuntimeException e) {
			// A broken third-party service file must not stop this worker announcing its own nodes.
			log.warn("A NodeSpecSource failed while contributing node classes", e);
		}
		return classes;
	}
}
