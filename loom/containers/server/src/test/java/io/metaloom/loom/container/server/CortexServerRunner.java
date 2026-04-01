package io.metaloom.loom.container.server;
import io.metaloom.loom.api.Loom;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.LoomOptionsLookup;
import io.metaloom.loom.common.options.LoomOptionsLoader;

public class CortexServerRunner {

	public static void main(String[] args) throws Exception {
		LoomOptionsLookup optionsLookup = LoomOptionsLoader.createOrLoadOptions();
		LoomOptions options = optionsLookup.options();
		options.getDatabase().setHost("cortex.sky");
		options.getDatabase().setPort(5432);
		options.getDatabase().setDatabaseName("loom");
		options.getDatabase().setUsername("sa").setPassword("sa");
		Loom loom = Loom.create(optionsLookup);
		loom.run();
	}
}
