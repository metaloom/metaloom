package io.metaloom.loom.common.dagger;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import io.metaloom.loom.api.options.LoomOptions;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.file.FileSystem;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.micrometer.MicrometerMetricsFactory;
import io.vertx.micrometer.MicrometerMetricsOptions;

@Module
public class VertxModule {

	/**
	 * Process-wide Prometheus meter registry, created before {@link #vertx(PrometheusMeterRegistry)}
	 * so it can back Vert.x's built-in metrics. Standard JVM/process binders are attached here so
	 * memory, GC, thread and CPU families are exported.
	 */
	@Provides
	@Singleton
	public PrometheusMeterRegistry meterRegistry() {
		PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		new ClassLoaderMetrics().bindTo(registry);
		new JvmMemoryMetrics().bindTo(registry);
		new JvmGcMetrics().bindTo(registry);
		new JvmThreadMetrics().bindTo(registry);
		new ProcessorMetrics().bindTo(registry);
		new UptimeMetrics().bindTo(registry);
		return registry;
	}

	@Provides
	@Singleton
	public Vertx vertx(PrometheusMeterRegistry registry) {
		// Feed Vert.x built-in metrics (HTTP server/client, event-bus, pools) into the shared
		// registry. JVM metrics are disabled here because the binders above already registered them.
		VertxOptions options = new VertxOptions().setMetricsOptions(new MicrometerMetricsOptions()
			.setJvmMetricsEnabled(false)
			.setEnabled(true));
		return Vertx.builder()
			.with(options)
			.withMetrics(new MicrometerMetricsFactory(registry))
			.build();
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

	@Provides
	@Singleton
	public HttpServer httpServer(Vertx vertx, LoomOptions options) {
		HttpServerOptions httpOptions = new HttpServerOptions()
			.setHost(options.getServer().getBindAddress())
			.setPort(options.getServer().getRestPort());
		return vertx.createHttpServer(httpOptions);
	}

}
