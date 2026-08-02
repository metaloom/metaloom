package io.metaloom.cortex.cli.dagger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.GDriveClientOptions;
import io.metaloom.cortex.api.option.OneDriveClientOptions;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.cortex.cloud.CloudSupport;
import io.vertx.core.json.JsonObject;

public class CloudModuleTest {

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
				.put("client_email", "ingest@example.iam.gserviceaccount.com")
				.put("private_key", pem)
				.encode();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("RSA is not available", e);
		}
	}

	private static CortexOptions withMetaPath() {
		return new CortexOptions().setMetaPath(Paths.get("/var/lib/cortex"));
	}

	@Test
	public void testInactiveWithoutCredentials() {
		CortexOptions options = withMetaPath();

		assertThat(CloudModule.buildGdrive(new GDriveClientOptions(), options).isActive()).isFalse();
		assertThat(CloudModule.buildOnedrive(new OneDriveClientOptions(), options).isActive()).isFalse();
	}

	@Test
	public void testActiveWithAServiceAccount() {
		CloudSupport support = CloudModule.buildGdrive(
			new GDriveClientOptions().setServiceAccountJson(SERVICE_ACCOUNT_KEY), withMetaPath());

		assertThat(support.isActive()).isTrue();
		assertThat(support.store().accountId()).isEqualTo("ingest@example.iam.gserviceaccount.com");
	}

	@Test
	public void testActiveWithAppOnlyMicrosoftCredentials() {
		CloudSupport support = CloudModule.buildOnedrive(new OneDriveClientOptions()
			.setTenantId("tenant-1").setClientId("cid").setClientSecret("secret"), withMetaPath());

		assertThat(support.isActive()).isTrue();
		assertThat(support.store().accountId()).isEqualTo("tenant-1/cid");
	}

	/**
	 * A half-filled credential set is a deployment mistake. Staying quietly inactive would turn it
	 * into a missing capability, and Loom would reject runs with an unhelpful "no worker supports
	 * this kind" long after the typo was made.
	 */
	@Test
	public void testHalfConfiguredGoogleCredentialsFailAtBoot() {
		GDriveClientOptions options = new GDriveClientOptions().setClientId("cid");

		assertThatThrownBy(() -> CloudModule.buildGdrive(options, withMetaPath()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("--gdrive-client-secret");
	}

	@Test
	public void testHalfConfiguredMicrosoftCredentialsFailAtBoot() {
		OneDriveClientOptions options = new OneDriveClientOptions().setClientId("cid");

		assertThatThrownBy(() -> CloudModule.buildOnedrive(options, withMetaPath()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("--onedrive-client-secret");
	}

	@Test
	public void testAMalformedServiceAccountKeyFailsAtBoot() {
		GDriveClientOptions options = new GDriveClientOptions()
			.setServiceAccountJson("{\"client_email\":\"a@b\",\"private_key\":\"garbage\"}");

		assertThatThrownBy(() -> CloudModule.buildGdrive(options, withMetaPath()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("credentials could not be loaded");
	}

	@Test
	public void testCachePathDerivesFromTheMetaPath() {
		Path cache = CloudModule.resolveCachePath(CloudProviderId.GDRIVE, new GDriveClientOptions(), withMetaPath());

		assertThat(cache).isEqualTo(Paths.get("/var/lib/cortex/gdrive_bin"));
	}

	@Test
	public void testEachProviderGetsItsOwnDirectories() {
		CortexOptions options = withMetaPath();

		assertThat(CloudModule.resolveIndexPath(CloudProviderId.GDRIVE, new GDriveClientOptions(), options))
			.isEqualTo(Paths.get("/var/lib/cortex/gdrive-index"));
		// Separate, so one provider's index can be wiped without touching the other's.
		assertThat(CloudModule.resolveIndexPath(CloudProviderId.ONEDRIVE, new OneDriveClientOptions(), options))
			.isEqualTo(Paths.get("/var/lib/cortex/onedrive-index"));
	}

	@Test
	public void testAnExplicitCachePathWins() {
		Path cache = CloudModule.resolveCachePath(CloudProviderId.GDRIVE,
			new GDriveClientOptions().setCachePath("/mnt/fast/cache"), withMetaPath());

		assertThat(cache).isEqualTo(Paths.get("/mnt/fast/cache"));
	}

	@Test
	public void testAMissingCachePathAndMetaPathIsAConfigurationError() {
		assertThatThrownBy(() -> CloudModule.resolveCachePath(CloudProviderId.GDRIVE,
			new GDriveClientOptions(), new CortexOptions()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("CORTEX_GDRIVE_CACHE_PATH");
	}

	@Test
	public void testAMissingIndexPathIsNullRatherThanAnError() {
		// The index directory is only needed by the source node, which reports it itself with a
		// message naming the node.
		assertThat(CloudModule.resolveIndexPath(CloudProviderId.GDRIVE, new GDriveClientOptions(),
			new CortexOptions())).isNull();
	}
}
