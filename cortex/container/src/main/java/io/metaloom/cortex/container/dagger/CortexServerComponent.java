package io.metaloom.cortex.container.dagger;

import javax.annotation.Nullable;
import javax.inject.Named;
import javax.inject.Singleton;

import dagger.BindsInstance;
import dagger.Component;
import io.metaloom.cortex.Cortex;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.cli.dagger.CortexBindModule;
import io.metaloom.cortex.cli.dagger.CortexClientModule;
import io.metaloom.cortex.cli.dagger.CortexMediaModule;
import io.metaloom.cortex.cli.dagger.LoomStorageModule;
import io.metaloom.cortex.cli.dagger.NodeCollectionModule;

@Singleton
@Component(modules = { CortexBindModule.class, CortexMediaModule.class, LoomStorageModule.class, NodeCollectionModule.class,
	CortexClientModule.class })
public interface CortexServerComponent {

	Cortex cortex();

	@Component.Builder
	interface Builder {

		@BindsInstance
		Builder options(@Named("default-options") @Nullable CortexOptions options);

		CortexServerComponent build();

	}

}
