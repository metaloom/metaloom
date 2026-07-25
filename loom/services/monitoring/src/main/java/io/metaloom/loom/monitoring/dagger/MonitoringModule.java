package io.metaloom.loom.monitoring.dagger;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import io.metaloom.loom.common.metrics.LoomMetrics;
import io.metaloom.loom.monitoring.MicrometerLoomMetrics;

/**
 * Dagger wiring for the Loom monitoring service. Binds the {@link LoomMetrics} catalog to its
 * Micrometer implementation. {@code MonitoringService} and {@code MicrometerLoomMetrics} are
 * {@code @Inject}-constructed, and the shared {@code PrometheusMeterRegistry} is provided by
 * {@code VertxModule}, so no further provider is needed here.
 */
@Module
public abstract class MonitoringModule {

	@Binds
	@Singleton
	abstract LoomMetrics bindLoomMetrics(MicrometerLoomMetrics impl);
}
