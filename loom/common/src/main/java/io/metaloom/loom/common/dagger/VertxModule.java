package io.metaloom.loom.common.dagger;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.file.FileSystem;

@Module
public class VertxModule {

	@Provides
	@Singleton
	public Vertx vertx() {
		return Vertx.vertx();
	}

	@Provides
	@Singleton
	public io.vertx.rxjava3.core.Vertx rxVertx(Vertx vertx) {
		return new io.vertx.rxjava3.core.Vertx(vertx);
	}

	@Provides
	@Singleton
	public FileSystem filesystem(Vertx vertx) {
		return vertx.fileSystem();
	}

	@Provides
	@Singleton
	public io.vertx.rxjava3.core.file.FileSystem rxFilesystem(io.vertx.rxjava3.core.Vertx rxVertx) {
		return rxVertx.fileSystem();
	}

	@Provides
	@Singleton
	public EventBus eventBus(Vertx vertx) {
		return vertx.eventBus();
	}

	@Provides
	@Singleton
	public io.vertx.rxjava3.core.eventbus.EventBus rxEventBus(io.vertx.rxjava3.core.Vertx rxVertx) {
		return rxVertx.eventBus();
	}

}
