package io.metaloom.loom.nodes.spec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CortexNodeDescriptorsTest {

	private NodeDescriptorRegistry registry;

	@BeforeEach
	void setUp() {
		registry = new NodeDescriptorRegistry();
		CortexNodeDescriptors.registerAll(registry);
	}

	@Test
	void testAllNodesRegistered() {
		// 29 nodes: 2 source + 4 hash + 11 analysis + 1 transform + 3 output + 8 filter
		assertEquals(29, registry.size(), "Expected 29 descriptors but got " + registry.size());
	}

	@Test
	void testAllExpectedKindsPresent() {
		List<String> expectedKinds = List.of(
			// Sources
			"filesystem-source", "loom-fetch",
			// Hash
			"md5", "sha256", "sha512", "chunk-hash",
			// Analysis
			"fingerprint", "facedetect", "facedescription", "quality",
			"consistency", "scene-detection", "tika", "whisper", "ocr", "llm", "captioning",
			// Transform
			"thumbnail",
			// Output
			"loom", "hash-dedup", "fingerprint-dedup",
			// Filter
			"filter-mimetype", "filter-date", "filter-size", "filter-duplicate",
			"filter-blacklist", "filter-quality", "filter-threshold", "filter-asset-attribute"
		);

		for (String kind : expectedKinds) {
			assertTrue(registry.contains(kind), "Missing descriptor for kind: " + kind);
		}
	}

	@Test
	void testEveryDescriptorHasRequiredFields() {
		for (NodeDescriptor desc : registry.getAll()) {
			assertNotNull(desc.getKind(), "kind must not be null");
			assertNotNull(desc.getName(), "name must not be null for " + desc.getKind());
			assertNotNull(desc.getCategory(), "category must not be null for " + desc.getKind());
			assertNotNull(desc.getInputs(), "inputs must not be null for " + desc.getKind());
			assertNotNull(desc.getOutputs(), "outputs must not be null for " + desc.getKind());
			assertNotNull(desc.getParameters(), "parameters must not be null for " + desc.getKind());
		}
	}

	@Test
	void testSourceNodesHaveNoInputs() {
		Set<String> sourceKinds = Set.of("filesystem-source", "loom-fetch");
		for (String kind : sourceKinds) {
			NodeDescriptor desc = registry.get(kind);
			assertTrue(desc.getInputs().isEmpty(),
				kind + " is a source node and should have no inputs");
			assertEquals(NodeCategory.SOURCE, desc.getCategory());
		}
	}

	@Test
	void testFilterNodesHaveCorrectCategory() {
		registry.getAll().stream()
			.filter(d -> d.getKind().startsWith("filter-"))
			.forEach(d -> assertEquals(NodeCategory.FILTER, d.getCategory(),
				d.getKind() + " should be FILTER category"));
	}

	@Test
	void testFacedetectHasExpectedParameters() {
		NodeDescriptor desc = registry.get("facedetect");
		assertNotNull(desc);
		assertFalse(desc.getParameters().isEmpty(), "facedetect should have parameters");

		Set<String> paramKeys = desc.getParameters().stream()
			.map(NodeParameter::getKey)
			.collect(Collectors.toSet());

		assertTrue(paramKeys.contains("videoChopRate"), "Missing videoChopRate parameter");
	}

	@Test
	void testThumbnailIsTransformCategory() {
		NodeDescriptor desc = registry.get("thumbnail");
		assertNotNull(desc);
		assertEquals(NodeCategory.TRANSFORM, desc.getCategory());
		assertFalse(desc.getParameters().isEmpty(), "thumbnail should have parameters");
	}

	@Test
	void testWhisperHasModelPathParameter() {
		NodeDescriptor desc = registry.get("whisper");
		assertNotNull(desc);
		boolean hasModelPath = desc.getParameters().stream()
			.anyMatch(p -> "modelPath".equals(p.getKey()));
		assertTrue(hasModelPath, "whisper should have modelPath parameter");
	}

	@Test
	void testHashNodesProduceHashOutput() {
		for (String kind : List.of("md5", "sha256", "sha512", "chunk-hash")) {
			NodeDescriptor desc = registry.get(kind);
			assertNotNull(desc, "Missing descriptor: " + kind);
			boolean hasHashOutput = desc.getOutputs().stream()
				.anyMatch(o -> ContentTypes.DATA_HASH.equals(o.getContentType()));
			assertTrue(hasHashOutput, kind + " should produce data/hash output");
		}
	}

	@Test
	void testUniqueKinds() {
		// Verify getAll() count matches size() — no collisions
		assertEquals(registry.size(), registry.getAll().size());
	}
}
