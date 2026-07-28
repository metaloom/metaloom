package io.metaloom.cortex.cli.dagger;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.loader.NodeFactory;

/**
 * Verifies that the executable node-kind set advertised by the worker is derived
 * from the actual node collection (the {@code @IntoMap @StringKey} multibinding),
 * populated imperatively at bootstrap by the {@link io.metaloom.cortex.pipeline.loader.NodeRegistrar}.
 */
public class NodeRegistrarTest {

	private CortexComponent component() {
		CortexOptions options = new CortexOptions();
		options.setMetaPath(Paths.get("target/test-meta"));
		return DaggerCortexComponent.builder().options(options).build();
	}

	@Test
	public void testRegistryEmptyUntilBootstrap() {
		CortexComponent component = component();
		// The registry is provided empty; nothing is registered until the bootstrap
		// initializer calls registerAll(). This is what lets the REGISTER message
		// advertise the fully-populated set.
		assertThat(component.nodeFactory().registeredTypes()).isEmpty();
	}

	@Test
	public void testRegisterAllAdvertisesFullCollection() {
		CortexComponent component = component();
		component.nodeRegistrar().registerAll();

		NodeFactory factory = component.nodeFactory();
		Set<String> kinds = factory.registeredTypes();

		// Source kinds are registered explicitly; every processing kind comes from
		// its own node module's map binding - including the ~10 that the old
		// hand-maintained factory list omitted (whisper, ocr, tika, quality, ...).
		assertThat(kinds).contains(
			"filesystem-source", "asset-source",
			"sha512", "sha256", "md5", "chunk-hash",
			"fingerprint", "consistency", "thumbnail", "facedetect",
			"ocr", "tika", "whisper", "tts", "sentiment", "llm", "vlm",
			"quality", "scene-detection", "captioning", "loom", "sha512-dedup",
			"depthmap", "scene-layout");

		// Stubs / unwired nodes must NOT be advertised, or Loom would dispatch work
		// the worker cannot actually run.
		assertThat(kinds).doesNotContain("fingerprint-dedup", "facedescription");
	}

	@Test
	public void testRegisterAllIsIdempotent() {
		CortexComponent component = component();
		component.nodeRegistrar().registerAll();
		int first = component.nodeFactory().registeredTypes().size();
		component.nodeRegistrar().registerAll();
		assertThat(component.nodeFactory().registeredTypes()).hasSize(first);
	}
}
