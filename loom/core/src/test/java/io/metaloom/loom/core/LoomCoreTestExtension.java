package io.metaloom.loom.core;

import java.io.File;
import java.time.Duration;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.api.options.AuthenticationOptions;
import io.metaloom.loom.api.options.DatabaseOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.LoomOptionsLookup;
import io.metaloom.loom.api.options.ServerOptions;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.dagger.DaggerLoomCoreComponent;
import io.metaloom.loom.core.dagger.LoomCoreComponent;
import io.metaloom.loom.test.LoomProviderExtension;
import io.metaloom.test.container.provider.model.DatabaseAllocationResponse;

public class LoomCoreTestExtension implements BeforeEachCallback, AfterEachCallback {

	@RegisterExtension
	public static LoomProviderExtension ext = LoomProviderExtension.create();

	private LoomCoreComponent loomInternal;

	public LoomHttpClient httpClient() {
		return LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofHours(2))
			.setPort(loomInternal.boot().getRestService().getServer().actualPort())
			.build();
	}

	// public LoomClient grpcClient() {
	// return LoomHttpClient.builder()
	// .setHostname("localhost")
	// .setPort(loomInternal.boot().getGrpcService().getServer().actualPort())
	// .build();
	// }

	@Override
	public void beforeEach(ExtensionContext context) throws Exception {
		ext.beforeEach(context);
		LoomOptions options = new LoomOptions();

		// Database
		DatabaseOptions dbOptions = new DatabaseOptions();
		DatabaseAllocationResponse db = ext.db();
		dbOptions.setHost(db.getHost());
		dbOptions.setPort(db.getPort());
		dbOptions.setUsername(db.getUsername());
		dbOptions.setPassword(db.getPassword());
		dbOptions.setDatabaseName(db.getDatabaseName());
		options.setDatabase(dbOptions);

		// Server
		ServerOptions serverOptions = options.getServer();
		serverOptions.setBindAddress("localhost");
		serverOptions.setRestPort(0);
		serverOptions.setGrpcPort(0);

		// Auth
		AuthenticationOptions authOptions = options.getAuth();
		authOptions.setKeystorePassword("ABCD");
		// TODO use tempfile to avoid collisions
		File baseFolder = new File("target", "test-config");
		File keystoreFile = new File(baseFolder, "keystore.jceks");
		if (keystoreFile.exists()) {
			keystoreFile.delete();
		}
		LoomOptionsLookup optionsLookup = new LoomOptionsLookup(baseFolder, options);
		loomInternal = DaggerLoomCoreComponent.builder().configuration(optionsLookup).build();
		loomInternal.boot().init(false);

	}

	@Override
	public void afterEach(ExtensionContext context) throws Exception {
		// ext.afterEach(context);
		if (loomInternal != null) {
			loomInternal.boot().deinit();
		}
	}

	public LoomCoreComponent internal() {
		return loomInternal;
	}

}
