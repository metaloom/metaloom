package io.metaloom.loom.core.db;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutionException;

import io.metaloom.test.container.provider.client.TestDatabaseProvider;
import io.metaloom.test.container.provider.common.config.ProviderConfig;

public class TestDBPoolManager {

	public static void main(String[] args) throws URISyntaxException, IOException, InterruptedException, ExecutionException {
		
		ProviderConfig config = new ProviderConfig();
		config.setProviderHost("saturn");
		config.setProviderPort(7543);
		config.getPostgresql().setPassword("sa");
		config.getPostgresql().setUsername("sa");
		config.getPostgresql().setDatabaseName("test");
		config.getPostgresql().setHost("saturn");
		config.getPostgresql().setPort(15432);
		TestDatabaseProvider.localConfig(config);
		
		TestDatabaseProvider.client().deletePool("dummy").get();
		
	}
}
