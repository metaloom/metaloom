package io.metaloom.loom.rest.dagger;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;
import io.metaloom.vertx.router.ApiRouter;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

@Module
public class RESTModule {

	@Provides
	@Singleton
	@Named("restRouter")
	public Router restRouter(Vertx vertx) {
		return Router.router(vertx);
	}

	@Provides
	@Singleton
	@Named("restApiRouter")
	public ApiRouter restApiRouter(Vertx vertx) {
		return ApiRouter.create(vertx);
	}

	@Provides
	@Singleton
	public LoomModelValidator validator() {
		return new LoomModelValidatorImpl();
	}

}
