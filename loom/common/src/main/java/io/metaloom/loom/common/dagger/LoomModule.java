package io.metaloom.loom.common.dagger;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import io.metaloom.loom.api.options.DatabaseOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.LoomOptionsLookup;

@Module
public class LoomModule {

	@Provides
	@Singleton
	public DatabaseOptions databaseOptions(LoomOptions options) {
		return options.getDatabase();
	}

	@Provides
	@Singleton
	public LoomOptions loomOptions(LoomOptionsLookup lookup) {
		return lookup.options();
	}

}
