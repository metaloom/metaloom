package io.metaloom.cortex.cli.dagger;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.loader.NodeFactory;
import io.vertx.core.json.JsonObject;

/**
 * Verifies that the executable node-kind set advertised by the worker is derived
 * from the actual node collection (the {@code @IntoMap @StringKey} multibinding),
 * populated imperatively at bootstrap by the {@link io.metaloom.cortex.pipeline.loader.NodeRegistrar}.
 */
public class NodeRegistrarTest {

	/**
	 * A syntactically real service-account key, generated rather than embedded: the token source
	 * parses the PEM when the Dagger graph is built, so a placeholder string would fail the
	 * worker's boot rather than exercise the capability gate.
	 */
	private static final String SERVICE_ACCOUNT_KEY = generateServiceAccountKey();

	private static String generateServiceAccountKey() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			KeyPair keyPair = generator.generateKeyPair();
			String pem = "-----BEGIN PRIVATE KEY-----\n"
				+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
					.encodeToString(keyPair.getPrivate().getEncoded())
				+ "\n-----END PRIVATE KEY-----\n";
			return new JsonObject()
				.put("type", "service_account")
				.put("client_email", "ingest@example.iam.gserviceaccount.com")
				.put("private_key", pem)
				.encode();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("RSA is not available", e);
		}
	}

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
			"ocr", "tika", "whisper", "tts", "sentiment", "llm", "vlm", "translate",
			"quality", "scene-detection", "captioning", "sha512-dedup",
			"depthmap", "scene-layout", "dominant-color", "watermark",
			// The two fingerprint-dedup kinds gained real runtimes and map bindings; NODES.md §8
			// records them as intentionally bound, so they belong here rather than in the
			// not-advertised list below (where this assertion had gone stale).
			"fingerprint-dedup", "fingerprint-dedup-apply",
			// facedescription had a descriptor and no bindings, so the pipeline editor offered a node the
			// registrar could not resolve. It is bound now, which is the whole point of advertising it.
			"facedescription",
			// The relocation pair. A descriptor makes a kind visible in the palette; only the map binding
			// makes it runnable, and these two are what the dedup nodes now hand their decisions to.
			"move", "assign");
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

	/** A worker holding a Google service-account key. */
	private CortexComponent gdriveComponent() {
		CortexOptions options = new CortexOptions();
		options.setMetaPath(Paths.get("target/test-meta"));
		options.getGdrive().setServiceAccountJson(SERVICE_ACCOUNT_KEY);
		return DaggerCortexComponent.builder().options(options).build();
	}

	/** A worker holding Google installed-app OAuth credentials instead. */
	private CortexComponent gdriveRefreshTokenComponent() {
		CortexOptions options = new CortexOptions();
		options.setMetaPath(Paths.get("target/test-meta"));
		options.getGdrive().setClientId("cid").setClientSecret("secret").setRefreshToken("rt");
		return DaggerCortexComponent.builder().options(options).build();
	}

	/** A worker holding Microsoft app-only credentials. */
	private CortexComponent onedriveComponent() {
		CortexOptions options = new CortexOptions();
		options.setMetaPath(Paths.get("target/test-meta"));
		options.getOnedrive().setTenantId("tenant-1").setClientId("cid").setClientSecret("secret");
		return DaggerCortexComponent.builder().options(options).build();
	}

	@Test
	public void testGdriveSourceIsNotAdvertisedWithoutCredentials() {
		CortexComponent component = component();
		component.nodeRegistrar().registerAll();

		// One kind per provider exists precisely so this gate can be per provider: advertising a
		// cloud this worker cannot reach would let Loom dispatch a source task that can only fail.
		assertThat(component.nodeFactory().registeredTypes()).doesNotContain("gdrive-source");
	}

	@Test
	public void testGdriveSourceIsAdvertisedOnceAServiceAccountIsConfigured() {
		CortexComponent component = gdriveComponent();
		component.nodeRegistrar().registerAll();

		assertThat(component.nodeFactory().registeredTypes()).contains("gdrive-source");
	}

	@Test
	public void testGdriveSourceIsAdvertisedWithARefreshToken() {
		CortexComponent component = gdriveRefreshTokenComponent();
		component.nodeRegistrar().registerAll();

		assertThat(component.nodeFactory().registeredTypes()).contains("gdrive-source");
	}

	@Test
	public void testOneDriveSourceIsNotAdvertisedWithoutCredentials() {
		CortexComponent component = component();
		component.nodeRegistrar().registerAll();

		assertThat(component.nodeFactory().registeredTypes()).doesNotContain("onedrive-source");
	}

	@Test
	public void testOneDriveSourceIsAdvertisedOnceAppOnlyCredentialsAreConfigured() {
		CortexComponent component = onedriveComponent();
		component.nodeRegistrar().registerAll();

		assertThat(component.nodeFactory().registeredTypes()).contains("onedrive-source");
	}

	@Test
	public void testConfiguringOnlyGoogleDoesNotAdvertiseOneDrive() {
		CortexComponent component = gdriveComponent();
		component.nodeRegistrar().registerAll();

		// The case a single generic 'cloud-source' kind could not express.
		Set<String> kinds = component.nodeFactory().registeredTypes();
		assertThat(kinds).contains("gdrive-source");
		assertThat(kinds).doesNotContain("onedrive-source");
	}

	@Test
	public void testS3AndCloudCanBeAdvertisedTogether() {
		CortexOptions options = new CortexOptions();
		options.setMetaPath(Paths.get("target/test-meta"));
		options.getS3().setEndpoint("http://minio:9000").setAccessKey("key").setSecretKey("secret");
		options.getGdrive().setServiceAccountJson(SERVICE_ACCOUNT_KEY);
		CortexComponent component = DaggerCortexComponent.builder().options(options).build();
		component.nodeRegistrar().registerAll();

		assertThat(component.nodeFactory().registeredTypes()).contains("s3-source", "gdrive-source");
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
