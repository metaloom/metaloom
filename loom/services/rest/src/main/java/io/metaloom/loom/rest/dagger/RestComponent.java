package io.metaloom.loom.rest.dagger;

import dagger.BindsInstance;
import dagger.Subcomponent;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.vertx.ext.web.RoutingContext;

@Subcomponent
public interface RestComponent {

	@Subcomponent.Builder
	interface Builder {

		@BindsInstance
		Builder context(RoutingContext rc);

		RestComponent build();
	}

	LoomRoutingContext  requestHandler();
}
