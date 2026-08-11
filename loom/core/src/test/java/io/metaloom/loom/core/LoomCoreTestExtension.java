package io.metaloom.loom.core;

import java.io.File;
import java.time.Duration;
import java.util.function.Consumer;

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

	private Consumer<LoomOptions> optionsCustomizer = o -> {
	};

	/**
	 * Register a customizer that is applied to the {@link LoomOptions} right before the Dagger
	 * component is built in {@link #beforeEach(ExtensionContext)}. Use this to toggle options that
	 * are read at injection time - e.g. MCP authentication ({@code o.getAuth().setMcpAuthEnabled(true)}).
	 *
	 * @param customizer options customizer, applied once per boot
	 * @return this extension for fluent chaining
	 */
	public LoomCoreTestExtension withOptions(Consumer<LoomOptions> customizer) {
		this.optionsCustomizer = customizer;
		return this;
	}

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
		File baseFolder = new File("target", "test-config");
		File keystoreFile = new File(baseFolder, "keystore.jceks");
		// Reuse a usable keystore instead of deleting it before every test method.
		//
		// This file is shared by every test in the module and the password is a constant, so a fresh
		// one per method bought nothing and cost a race: KeyStoreHelper.gen() creates the file and
		// then fills it, so a method that deleted and regenerated it while another was reading left
		// a zero-byte file behind. That surfaces as "Tag number over 30 is not supported" out of the
		// JWT provider, or as a token signed with the previous keystore being rejected by the next -
		// a 401 on a request that had just logged in successfully. Both were intermittent, both hit
		// whichever class happened to be running, and both grew with the number of test methods.
		//
		// A file that is present but empty or truncated is the wreckage of exactly that race, so it
		// is still removed; only a plausible one is kept.
		if (keystoreFile.exists() && keystoreFile.length() == 0) {
			keystoreFile.delete();
		}
		// Apply test-specific option overrides before the component is built. Auth flags such as
		// mcpAuthEnabled are read at injection time (MCPAuthenticationHandler constructor), so the
		// customizer must run before DaggerLoomCoreComponent is built below.
		optionsCustomizer.accept(options);

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
