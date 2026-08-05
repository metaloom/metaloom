package io.metaloom.loom.nodes.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Guards the {@link java.util.ServiceLoader} wiring of {@link NodeDescriptorProvider}.
 *
 <p>There are now two providers rather than 29. A node declares its contract once, on itself, with
 * {@code @NodeSpec}/{@code @PortDoc}/{@code @ParamDoc}; the harvest is committed to
 * {@code /node-descriptors.json} at build time and served by
 * {@link GeneratedNodeDescriptorProvider}. {@link OrphanNodeDescriptorProvider} carries the single
 * contract that has no node class behind it.</p>
 *
 * <p>What has not changed is the failure mode this guards. A dropped service-file line, or a
 * regenerated resource that lost a node, means a kind quietly disappears from pipeline validation and
 * the UI palette while everything still compiles and every other test still passes. These tests exist
 * so that it fails loudly instead.</p>
 */
public class NodeDescriptorServiceLoaderTest {

	/**
	 * Every provider listed in the service file must be loadable.
	 */
	@Test
	void testAllProvidersAreDiscovered() {
		List<NodeDescriptorProvider> providers = loadProviders();

		assertEquals(2, providers.size(),
			"Expected 2 descriptor providers via ServiceLoader but found " + providers.size()
				+ ". If a provider was intentionally added or removed, update this count and "
				+ "META-INF/services/io.metaloom.loom.nodes.spec.NodeDescriptorProvider together. "
				+ "Discovered: " + providerNames(providers));
	}

	/**
	 * The registry built from the discovered providers must expose the full kind set.
	 */
	@Test
	void testRegistryIsFullyPopulated() {
		NodeDescriptorRegistry registry = buildRegistry();

		assertEquals(41, registry.size(),
			"Expected 41 advertised node kinds but found " + registry.size()
				+ ". Discovered kinds: " + kinds(registry));
	}

	/**
	 * Spot-check kinds across the node modules, so a harvest that quietly stopped seeing one is caught
	 * rather than only a wholesale failure.
	 */
	@Test
	void testKindsFromEachFormerModuleArePresent() {
		NodeDescriptorRegistry registry = buildRegistry();

		// One kind per former *-api module, so any single dropped entry is detected.
		String[] expected = {
			"sha512",             // former cortex-hash-api
			"filesystem-source",  // former cortex-source-api
			"s3-source",
			"gdrive-source",   // cloud drives; one kind per provider so a worker
			"onedrive-source", // advertises only the cloud it holds credentials for
			"s3-sink",
			"filter",             // former cortex-filter-api; the eight filter-* kinds collapsed into this one
			"thumbnail",          // former cortex-thumbnail-api
			"facedetect",         // former cortex-facedetect-api
			"objectdetect",       // YOLO object detection; the first producer of detection/object
			"fingerprint",        // former cortex-fingerprint-api
			"whisper",            // former cortex-whisper-api
			"ocr",                // former cortex-ocr-api
			"llm",                // former cortex-llm-api
			"vlm",                // vision-language model node
			"tika",               // former cortex-tika-api
			"metadata",           // EXIF / IPTC / XMP / container metadata ingest
			"quality",            // former cortex-quality-api
			"consistency",        // former cortex-consistency-api
			"scene-detection",    // former cortex-scene-detection-api
			"captioning",         // former cortex-captioning-api
			"hash-dedup",         // former cortex-dedup-api
			"sentiment",          // sentiment analysis node
			"depthmap",           // monocular depth estimation
			"scene-layout",       // depth + boxes -> spatial relations
			"dominant-color",     // CIELAB colour clustering + bilingual naming
			"tts",                // text to speech via the /v1/tts sidecar
			"translate",          // upstream text into a target language via a language model
			"imagegen",           // text-to-image / image-to-image sidecar
			"watermark",          // composite a configured overlay onto image or video
			"image-manipulation", // autorotate, crop, reframe and resize an image in one pass
			"tag"                 // rule-driven tagging; the terminal that makes a computed value searchable
		};

		for (String kind : expected) {
			assertTrue(registry.contains(kind),
				"Node kind '" + kind + "' is missing from the registry. Its provider is "
					+ "probably absent from META-INF/services/"
					+ "io.metaloom.loom.nodes.spec.NodeDescriptorProvider. "
					+ "Known kinds: " + kinds(registry));
		}
	}

	/**
	 * Two providers advertising the same kind would let one silently shadow the other.
	 */
	@Test
	void testNoDuplicateKindsAcrossProviders() {
		Set<String> seen = new HashSet<>();
		List<String> duplicates = new ArrayList<>();

		for (NodeDescriptorProvider provider : loadProviders()) {
			for (NodeDescriptor descriptor : provider.getDescriptors()) {
				if (!seen.add(descriptor.getNodeId())) {
					duplicates.add(descriptor.getNodeId() + " (from " + provider.getClass().getSimpleName() + ")");
				}
			}
		}

		assertTrue(duplicates.isEmpty(), "Duplicate node kinds advertised by more than one provider: " + duplicates);
	}

	/**
	 * A descriptor without a kind cannot be referenced from a pipeline definition.
	 */
	@Test
	void testEveryDescriptorHasAKind() {
		for (NodeDescriptorProvider provider : loadProviders()) {
			for (NodeDescriptor descriptor : provider.getDescriptors()) {
				String kind = descriptor.getNodeId();
				assertFalse(kind == null || kind.isBlank(),
					"Descriptor from " + provider.getClass().getSimpleName() + " has a null/blank kind");
			}
		}
	}

	private static List<NodeDescriptorProvider> loadProviders() {
		List<NodeDescriptorProvider> providers = new ArrayList<>();
		java.util.ServiceLoader.load(NodeDescriptorProvider.class).forEach(providers::add);
		return providers;
	}

	private static NodeDescriptorRegistry buildRegistry() {
		NodeDescriptorRegistry registry = new NodeDescriptorRegistry();
		loadProviders().forEach(provider -> provider.getDescriptors().forEach(registry::register));
		return registry;
	}

	private static List<String> providerNames(List<NodeDescriptorProvider> providers) {
		return providers.stream().map(p -> p.getClass().getSimpleName()).sorted().toList();
	}

	private static List<String> kinds(NodeDescriptorRegistry registry) {
		return registry.getAll().stream().map(NodeDescriptor::getKind).sorted().toList();
	}
}
