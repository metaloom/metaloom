package io.metaloom.cortex.impl.boot;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.impl.loom.LoomControlChannel;
import io.metaloom.cortex.impl.monitoring.MonitoringService;

@Singleton
public class CortexBootstrapInitializer {

	public static final Logger log = LoggerFactory.getLogger(CortexBootstrapInitializer.class);

	private final MonitoringService monitoringService;
	private final LoomControlChannel loomControlChannel;

	@Inject
	public CortexBootstrapInitializer(MonitoringService monitoringService, LoomControlChannel loomControlChannel) {
		this.monitoringService = monitoringService;
		this.loomControlChannel = loomControlChannel;
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
