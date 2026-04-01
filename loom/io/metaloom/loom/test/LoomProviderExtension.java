package io.metaloom.loom.test;

import io.metaloom.test.container.provider.client.ProviderClient;
import io.metaloom.test.container.provider.client.TestDatabaseProvider;
import io.metaloom.test.container.provider.common.config.ProviderConfig;
import io.metaloom.test.provider.junit5.ProviderExtension;

public class LoomProviderExtension extends ProviderExtension {

	private static final String POOL_ID = "loom-dev";

	public LoomProviderExtension(ProviderClient client, String poolId) {
		super(client, poolId);
	}

	public static ProviderClient client() {
		ProviderConfig config = TestEnvHelper.prepareProvider();
		return TestDatabaseProvider.client(config.getProviderHost(), config.getProviderPort());
	}

	/**
	 * Create a new extension which connects to the provider server.
	 * 
	 * @param poolId
	 * @return
	 */
	public static LoomProviderExtension create() {
		ProviderClient client = client();
		return new LoomProviderExtension(client, POOL_ID);
	}

}
