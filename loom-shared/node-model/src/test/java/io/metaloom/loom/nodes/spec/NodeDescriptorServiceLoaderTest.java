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
 * <p>The descriptor providers used to live in 17 separate {@code cortex/nodes/*-api}
 * modules, each shipping its own
 * {@code META-INF/services/io.metaloom.loom.nodes.spec.NodeDescriptorProvider} file.
 * Those modules were merged into this one on 2026-07-18, which meant merging 16
 * service files into a single registry file.</p>
 *
 * <p>That merge is exactly the kind of change that fails <em>silently</em>: a dropped
 * line means a node kind quietly disappears from pipeline validation and the UI
 * palette, while everything still compiles and every other test still passes. These
 * tests exist so that a missing entry fails loudly instead.</p>
 */
public class NodeDescriptorServiceLoaderTest {

	/**
	 * Every provider listed in the merged service file must be loadable.
	 */
	@Test
	void testAllProvidersAreDiscovered() {
		List<NodeDescriptorProvider> providers = loadProviders();

		assertEquals(17, providers.size(),
			"Expected 17 descriptor providers via ServiceLoader but found " + providers.size()
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

		assertEquals(30, registry.size(),
			"Expected 30 advertised node kinds but found " + registry.size()
				+ ". Discovered kinds: " + kinds(registry));
	}

	/**
	 * Spot-check kinds from providers that lived in different former modules, so a
	 * single dropped service-file line is caught rather than only a wholesale failure.
	 */
	@Test
	void testKindsFromEachFormerModuleArePresent() {
		NodeDescriptorRegistry registry = buildRegistry();

		// One kind per former *-api module, so any single dropped entry is detected.
		String[] expected = {
			"sha512",             // former cortex-hash-api
			"filesystem-source",  // former cortex-source-api
			"filter-mimetype",    // former cortex-filter-api
			"thumbnail",          // former cortex-thumbnail-api
			"facedetect",         // former cortex-facedetect-api
			"fingerprint",        // former cortex-fingerprint-api
			"whisper",            // former cortex-whisper-api
			"ocr",                // former cortex-ocr-api
			"llm",                // former cortex-llm-api
			"vlm",                // vision-language model node
			"tika",               // former cortex-tika-api
			"quality",            // former cortex-quality-api
			"consistency",        // former cortex-consistency-api
			"scene-detection",    // former cortex-scene-detection-api
			"captioning",         // former cortex-captioning-api
			"loom",               // former cortex-loom-api
			"hash-dedup"          // former cortex-dedup-api
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
				if (!seen.add(descriptor.getKind())) {
					duplicates.add(descriptor.getKind() + " (from " + provider.getClass().getSimpleName() + ")");
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
				String kind = descriptor.getKind();
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
