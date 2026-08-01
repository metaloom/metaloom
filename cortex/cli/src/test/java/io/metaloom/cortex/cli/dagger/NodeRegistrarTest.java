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

	/** A worker configured to reach an S3-compatible endpoint. */
	private CortexComponent s3Component() {
		CortexOptions options = new CortexOptions();
		options.setMetaPath(Paths.get("target/test-meta"));
		options.getS3().setEndpoint("http://minio:9000").setAccessKey("key").setSecretKey("secret");
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
			"quality", "scene-detection", "captioning", "sha512-dedup",
			"depthmap", "scene-layout", "dominant-color", "watermark",
			// The two fingerprint-dedup kinds gained real runtimes and map bindings; NODES.md §8
			// records them as intentionally bound, so they belong here rather than in the
			// not-advertised list below (where this assertion had gone stale).
			"fingerprint-dedup", "fingerprint-dedup-apply");

		// Stubs / unwired nodes must NOT be advertised, or Loom would dispatch work
		// the worker cannot actually run.
		assertThat(kinds).doesNotContain("facedescription");
	}

	@Test
	public void testS3SourceIsNotAdvertisedWithoutS3Configuration() {
		CortexComponent component = component();
		component.nodeRegistrar().registerAll();

		// Same reasoning as the stub nodes above: advertising a kind this worker cannot serve
		// would let Loom dispatch a source task that can only fail, which surfaces as a dead run
		// rather than as a missing capability.
		assertThat(component.nodeFactory().registeredTypes()).doesNotContain("s3-source");
	}

	@Test
	public void testS3SourceIsAdvertisedOnceS3IsConfigured() {
		CortexComponent component = s3Component();
		component.nodeRegistrar().registerAll();

		assertThat(component.nodeFactory().registeredTypes()).contains("s3-source");
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
