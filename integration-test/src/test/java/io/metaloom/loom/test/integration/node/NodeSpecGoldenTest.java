package io.metaloom.loom.test.integration.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.metaloom.cortex.api.node.spec.NodeSpec;
import io.metaloom.cortex.api.node.spec.NodeSpecHarvester;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorProvider;
import io.metaloom.loom.nodes.spec.NodeDescriptors;

/**
 * The harvested contract must equal the hand-written one, field for field.
 *
 * <p>
 * This is the acceptance test for moving a node's spec out of {@code loom-shared/node-model} and onto
 * the node itself. The retired {@code XDescriptorProvider} is the <strong>golden fixture</strong>: it
 * is already customer-facing, already reviewed, and already what the editor draws. If
 * {@code harvest(SentimentNode.class)} does not reproduce it exactly, either the annotations are
 * incomplete or the harvester is wrong — and nothing about that judgement is left to the eye.
 * </p>
 *
 * <p>
 * It lives here for the same reason {@link NodePortConformanceTest} does: descriptors are in
 * {@code loom-shared}, node classes are in {@code cortex/}, neither module can see the other, and this
 * is the only one that sees both.
 * </p>
 *
 * <p>
 * As each node is annotated its provider goes away, so this test shrinks as the sweep progresses and
 * {@link NodePortConformanceTest} — which exists <em>only</em> because a node's ports were declared
 * twice — is deleted when the last one does.
 * </p>
 */
public class NodeSpecGoldenTest {

	/**
	 * Node class → the descriptor node id it is the golden fixture for.
	 *
	 * <p>
	 * Listed explicitly rather than scanned so that annotating a node is a deliberate line here, and a
	 * node can never be silently unchecked. A class not yet annotated is skipped, which is what lets
	 * the sweep land one module at a time.
	 * </p>
	 */
	private static final Map<String, String> GOLDEN = new LinkedHashMap<>();

	static {
		GOLDEN.put("io.metaloom.cortex.node.sentiment.SentimentNode", "sentiment");
		GOLDEN.put("io.metaloom.cortex.node.tika.TikaNode", "tika");
		GOLDEN.put("io.metaloom.cortex.node.ocr.OCRNode", "ocr");
		GOLDEN.put("io.metaloom.cortex.node.thumbnail.ThumbnailNode", "thumbnail");
		GOLDEN.put("io.metaloom.cortex.node.metadata.MetadataNode", "metadata");
		GOLDEN.put("io.metaloom.cortex.node.quality.QualityNode", "quality");
		GOLDEN.put("io.metaloom.cortex.node.color.DominantColorNode", "dominant-color");
		GOLDEN.put("io.metaloom.cortex.node.consistency.ConsistencyNode", "consistency");
		GOLDEN.put("io.metaloom.cortex.node.whisper.WhisperNode", "whisper");
		GOLDEN.put("io.metaloom.cortex.node.captioning.CaptioningNode", "captioning");
		GOLDEN.put("io.metaloom.cortex.node.scene.SceneDetectionNode", "scene-detection");
		GOLDEN.put("io.metaloom.cortex.node.scenelayout.SceneLayoutNode", "scene-layout");

		GOLDEN.put("io.metaloom.cortex.node.tts.TtsNode", "tts");
		GOLDEN.put("io.metaloom.cortex.node.depthmap.DepthmapNode", "depthmap");
		GOLDEN.put("io.metaloom.cortex.node.translate.TranslateNode", "translate");

		GOLDEN.put("io.metaloom.cortex.node.hash.MD5Node", "md5");
		GOLDEN.put("io.metaloom.cortex.node.hash.SHA256Node", "sha256");
		GOLDEN.put("io.metaloom.cortex.node.hash.SHA512Node", "sha512");
		GOLDEN.put("io.metaloom.cortex.node.hash.ChunkHashNode", "chunk-hash");
		GOLDEN.put("io.metaloom.cortex.node.source.fs.FilesystemSourceNode", "filesystem-source");
		GOLDEN.put("io.metaloom.cortex.node.source.s3.S3SourceNode", "s3-source");
		// CloudSourceNode also serves onedrive-source, which @NodeSpec cannot declare from the same
		// class - only the id it announces is compared here.
		GOLDEN.put("io.metaloom.cortex.node.source.cloud.GDriveSourceNode", "gdrive-source");
		GOLDEN.put("io.metaloom.cortex.node.source.cloud.OneDriveSourceNode", "onedrive-source");
		// HashDedupNode is also bound under the "sha512-dedup" alias, which @NodeSpec cannot declare from
		// the same class - only the id its descriptor describes is compared here.
		GOLDEN.put("io.metaloom.cortex.node.dedup.HashDedupNode", "hash-dedup");
		GOLDEN.put("io.metaloom.cortex.node.dedup.FingerprintDedupApplyNode", "fingerprint-dedup-apply");
		GOLDEN.put("io.metaloom.cortex.node.dedup.FingerprintDedupNode", "fingerprint-dedup");

		GOLDEN.put("io.metaloom.cortex.node.facedetect.FacedetectNode", "facedetect");
		GOLDEN.put("io.metaloom.cortex.node.facedescription.FacedescriptionNode", "facedescription");
		GOLDEN.put("io.metaloom.cortex.node.fp.FingerprintNode", "fingerprint");
		GOLDEN.put("io.metaloom.cortex.node.watermark.WatermarkNode", "watermark");
		GOLDEN.put("io.metaloom.cortex.node.sink.s3.S3SinkNode", "s3-sink");
		GOLDEN.put("io.metaloom.cortex.node.imagegen.ImageGenNode", "imagegen");
		GOLDEN.put("io.metaloom.cortex.node.videogen.VideoGenNode", "videogen");

		// Dynamic-port nodes: their real ports come from a NodePortResolver, so the static lists
		// compared here are short or empty.
		GOLDEN.put("io.metaloom.cortex.node.script.ScriptNode", "script");
		GOLDEN.put("io.metaloom.cortex.node.filter.FilterNode", "filter");
		GOLDEN.put("io.metaloom.cortex.node.llm.LLMNode", "llm");
		GOLDEN.put("io.metaloom.cortex.node.vlm.VlmNode", "vlm");

		// (resolved) VlmNode was previously not listed: its hand-written descriptor cannot be
		// reproduced, and should not be. It advertises four parameters - model, responseFormat,
		// maxImageDim, maxTokens - that are fields of VlmNodePrompt, not of VlmNodeOptions. A vlm
		// node's config binds into VlmNodeOptions, so anything an author types into those four form
		// fields is silently discarded. (responseFormat is also typed ENUM with no values list, and
		// its advertised default OLMOCR disagrees with the real default TEXT.) Meanwhile apiKey and
		// prompts are real fields the contract never mentions.
		//
		// Reproducing that faithfully would mean inventing options fields to match a broken form.
		// Fixing it is a product decision for whoever owns vlm; add the line with that change.
	}

	private static Map<String, NodeDescriptor> descriptors() {
		Map<String, NodeDescriptor> byNodeId = new LinkedHashMap<>();
		ServiceLoader.load(NodeDescriptorProvider.class)
			.forEach(provider -> provider.getDescriptors().forEach(d -> byNodeId.put(d.getNodeId(), d)));
		return byNodeId;
	}

	@Test
	public void testHarvestReproducesTheHandWrittenDescriptor() throws Exception {
		Map<String, NodeDescriptor> descriptors = descriptors();
		ObjectMapper pretty = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
		List<String> problems = new ArrayList<>();
		int compared = 0;

		for (Map.Entry<String, String> entry : GOLDEN.entrySet()) {
			Class<?> nodeClass;
			try {
				nodeClass = Class.forName(entry.getKey());
			} catch (ClassNotFoundException e) {
				// Module not on this test's class path. Skip rather than fail - the point is to catch
				// drift, not to police the dependency list.
				continue;
			}
			if (!NodeSpecHarvester.isAnnotated(nodeClass)) {
				// Not yet swept. Nothing to compare.
				continue;
			}
			NodeDescriptor golden = descriptors.get(entry.getValue());
			if (golden == null) {
				// The provider is already deleted, so there is no fixture left to compare against. That
				// is the end state, not a failure.
				continue;
			}

			NodeDescriptor harvested = NodeSpecHarvester.harvest(nodeClass);
			compared++;

			// Version is announcement metadata, not contract, and no hand-written provider ever had one.
			NodeDescriptor comparable = NodeDescriptors.copy(harvested).setVersion(null);

			if (!NodeDescriptors.sameBody(golden, comparable)) {
				problems.add(nodeClass.getSimpleName() + " (" + entry.getValue() + "):\n"
					+ "  --- hand-written ---\n" + pretty.writeValueAsString(golden) + "\n"
					+ "  --- harvested ---\n" + pretty.writeValueAsString(comparable));
			}
		}

		assertEquals(List.of(), problems,
			"The harvested contract differs from the hand-written one:\n" + String.join("\n", problems));
		assertTrue(compared > 0,
			"No annotated node was compared against its golden fixture - this test would pass without checking anything");
	}

	@Test
	public void testAnnotatedNodesDeclareTheNodeIdTheyAreMappedTo() {
		for (Map.Entry<String, String> entry : GOLDEN.entrySet()) {
			Class<?> nodeClass;
			try {
				nodeClass = Class.forName(entry.getKey());
			} catch (ClassNotFoundException e) {
				continue;
			}
			NodeSpec spec = nodeClass.getAnnotation(NodeSpec.class);
			if (spec == null) {
				continue;
			}
			assertEquals(entry.getValue(), spec.nodeId(),
				nodeClass.getSimpleName() + " announces a node id this test does not expect");
		}
	}
}
