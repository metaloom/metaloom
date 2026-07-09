package io.metaloom.cortex.impl.boot;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.impl.loom.LoomControlChannel;
import io.metaloom.cortex.impl.monitoring.MonitoringService;
import io.metaloom.cortex.pipeline.loader.NodeFactory;

@Singleton
public class CortexBootstrapInitializer {

	public static final Logger log = LoggerFactory.getLogger(CortexBootstrapInitializer.class);

	private final MonitoringService monitoringService;
	private final LoomControlChannel loomControlChannel;
	@SuppressWarnings("unused")
	private final NodeFactory nodeFactory;

	@Inject
	public CortexBootstrapInitializer(MonitoringService monitoringService, LoomControlChannel loomControlChannel,
			NodeFactory nodeFactory) {
		this.monitoringService = monitoringService;
		this.loomControlChannel = loomControlChannel;
		// Injecting the NodeFactory forces eager instantiation of the
		// RegistryNodeFactory which, as part of construction, calls
		// LoomPipelineLoader.setNodeFactory(...). Without this reference
		// Dagger would create the factory lazily and pipelines loaded before
		// first use would still resolve to stub nodes.
		this.nodeFactory = nodeFactory;
	}

	public void init() {
		init(8093);
	}

	public void init(int port) {
		monitoringService.init(port);
		loomControlChannel.start();
	}

	public Integer actualMonitoringPort() {
		return monitoringService.actualMonitoringPort();
	}

	public void deinit() {
		loomControlChannel.stop();
		monitoringService.deinit();
	}

}
