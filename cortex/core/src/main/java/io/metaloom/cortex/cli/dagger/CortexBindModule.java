package io.metaloom.cortex.cli.dagger;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import io.metaloom.cortex.Cortex;
import io.metaloom.cortex.common.metrics.CortexMetrics;
import io.metaloom.cortex.impl.CortexImpl;
import io.metaloom.cortex.impl.loom.LoomBulkSyncWriterImpl;
import io.metaloom.cortex.impl.monitoring.MicrometerCortexMetrics;
import io.metaloom.cortex.pipeline.api.event.PipelineEventBus;
import io.metaloom.cortex.pipeline.api.sync.LoomBulkSyncCollector;
import io.metaloom.cortex.pipeline.common.event.DefaultPipelineEventBus;
import io.metaloom.cortex.pipeline.common.sync.DefaultLoomBulkSyncCollector;
import io.metaloom.cortex.pipeline.common.sync.DefaultLoomBulkSyncCollector.BulkSyncWriter;
import io.metaloom.cortex.processor.MediaProcessor;
import io.metaloom.cortex.processor.impl.DefaultMediaProcessorImpl;
import io.metaloom.cortex.scanner.FilesystemProcessor;
import io.metaloom.cortex.scanner.impl.FilesystemProcessorImpl;
import io.metaloom.fs.linux.LinuxFilesystemScanner;
import io.metaloom.fs.linux.impl.LinuxFilesystemScannerImpl;
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
import io.vertx.micrometer.MicrometerMetricsFactory;
import io.vertx.micrometer.MicrometerMetricsOptions;

@Module
public abstract class CortexBindModule {

	@Binds
	@Singleton
	abstract Cortex bindCortex(CortexImpl e);

	@Binds
	@Singleton
	abstract MediaProcessor bindMediaProcessor(DefaultMediaProcessorImpl e);

	@Binds
	@Singleton
	abstract FilesystemProcessor bindFilesystemProcessor(FilesystemProcessorImpl e);

	@Binds
	@Singleton
	abstract BulkSyncWriter bindBulkSyncWriter(LoomBulkSyncWriterImpl e);

	@Binds
	@Singleton
	abstract CortexMetrics bindCortexMetrics(MicrometerCortexMetrics e);

	/**
	 * Provide the process-wide Prometheus meter registry. Standard JVM/process binders are attached
	 * here so memory, GC, thread and CPU families are exported without any Vert.x involvement. The
	 * registry is created before {@link #provideVertx(PrometheusMeterRegistry)} so it can be handed to
	 * the Vert.x metrics options.
	 */
	@Provides
	@Singleton
	public static PrometheusMeterRegistry provideMeterRegistry() {
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
	public static Vertx provideVertx(PrometheusMeterRegistry registry) {
		// Feed Vert.x built-in metrics (HTTP, event-bus, pools) into the shared registry. JVM metrics
		// are disabled here because the binders above already registered them.
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
	public static PipelineEventBus providePipelineEventBus() {
		return new DefaultPipelineEventBus();
	}

	@Provides
	@Singleton
	public static LoomBulkSyncCollector provideBulkSyncCollector(BulkSyncWriter writer) {
		return new DefaultLoomBulkSyncCollector(writer);
	}


	@Provides
	public static LinuxFilesystemScanner bindFilesystemScanner() {
		return new LinuxFilesystemScannerImpl();
	}

}
