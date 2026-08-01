package io.metaloom.cortex.impl.boot;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.impl.loom.LoomControlChannel;
import io.metaloom.cortex.impl.monitoring.MonitoringService;
import io.metaloom.cortex.pipeline.loader.NodeRegistrar;
import io.metaloom.cortex.s3.event.SqsS3EventSource;

@Singleton
public class CortexBootstrapInitializer {

	public static final Logger log = LoggerFactory.getLogger(CortexBootstrapInitializer.class);

	private final MonitoringService monitoringService;
	private final LoomControlChannel loomControlChannel;
	private final NodeRegistrar nodeRegistrar;
	private final SqsS3EventSource sqsEventSource;
	private final CortexOptions options;

	@Inject
	public CortexBootstrapInitializer(MonitoringService monitoringService, LoomControlChannel loomControlChannel,
			NodeRegistrar nodeRegistrar, SqsS3EventSource sqsEventSource, CortexOptions options) {
		this.monitoringService = monitoringService;
		this.loomControlChannel = loomControlChannel;
		this.nodeRegistrar = nodeRegistrar;
		this.sqsEventSource = sqsEventSource;
		this.options = options;
	}

	public void init() {
		init(8093);
	}

	public void init(int port) {
		// Populate the node-kind registry before the control channel starts: the
		// REGISTER message advertises registeredTypes() as this worker's whitelist,
		// so the registry must be filled first or the worker under-reports (or fails
		// to report) what it can run. Registration is lazy per kind (Providers), so
		// this does not construct any node here.
		nodeRegistrar.registerAll();
		// The monitoring server also hosts the S3 webhook route, so it must come up before
		// notifications can be accepted.
		monitoringService.init(port);
		// No-op unless SQS bucket notifications are configured.
		sqsEventSource.start();
		loomControlChannel.start();
	}

	public Integer actualMonitoringPort() {
		return monitoringService.actualMonitoringPort();
	}

	public void deinit() {
		// Drain before stopping: announcing TERMINATING and handing back unfinished work
		// both need the control channel still open, and every task not returned here costs
		// its run a full lease interval before Loom places it again.
		loomControlChannel.drain(options.getDrainTimeoutMs());
		loomControlChannel.stop();
		sqsEventSource.stop();
		monitoringService.deinit();
	}

}
