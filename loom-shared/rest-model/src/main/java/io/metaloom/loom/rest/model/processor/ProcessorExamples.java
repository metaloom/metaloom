package io.metaloom.loom.rest.model.processor;

import java.time.Instant;
import java.util.EnumSet;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface ProcessorExamples extends ExampleValues {

	default Example processorResponseExample() {
		return new ExampleImpl(processorResponse(), "The processor response", HttpResponseStatus.OK);
	}

	default Example processorListResponseExample() {
		return new ExampleImpl(processorListResponse(), "The processor list response", HttpResponseStatus.OK);
	}

	default ProcessorResponse processorResponse() {
		ProcessorResponse model = new ProcessorResponse();
		model.setUuid(uuidC());
		model.setName("cortex-node-1");
		model.setHost("10.0.1.10:9090");
		model.setPriority(10);
		model.setState(ProcessorState.ONLINE);
		model.setCapabilities(EnumSet.of(ProcessorCapability.CPU, ProcessorCapability.IO));
		model.setLastSeen(Instant.parse("2026-04-05T10:30:00Z"));
		model.setSystemStatus(new SystemStatusInfo()
			.setCpuLoad(45.2)
			.setGpuLoad(78.0)
			.setIoLoad(31.0)
			.setMemoryUsed(4_294_967_296L)
			.setMemoryTotal(17_179_869_184L)
			.setDiskUsed(107_374_182_400L)
			.setDiskTotal(536_870_912_000L));
		return model;
	}

	default ProcessorListResponse processorListResponse() {
		ProcessorListResponse model = new ProcessorListResponse();
		model.setMetainfo(pagingInfo());
		model.add(processorResponse());
		return model;
	}
}
