package io.metaloom.loom.rest.dagger;

import dagger.Binds;
import dagger.Module;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.builder.impl.LoomModelBuilderImpl;

@Module
public abstract class RESTBindModule {

	@Binds
	abstract LoomModelBuilder bindModelBuilder(LoomModelBuilderImpl e);

}
