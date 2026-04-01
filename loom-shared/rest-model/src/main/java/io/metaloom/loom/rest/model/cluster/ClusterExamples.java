package io.metaloom.loom.rest.model.cluster;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface ClusterExamples extends ExampleValues {

	default Example clusterUpdateExample() {
		return new ExampleImpl(clusterUpdateRequest(), "The cluster update request", HttpResponseStatus.OK);
	}

	default Example clusterCreateExample() {
		return new ExampleImpl(clusterCreateRequest(), "The cluster create request", HttpResponseStatus.CREATED);
	}

	default Example clusterResponseExample() {
		return new ExampleImpl(clusterResponse(), "The cluster response", HttpResponseStatus.OK);
	}

	default Example clusterListResponseExample() {
		return new ExampleImpl(clusterListResponse(), "The cluster list response", HttpResponseStatus.OK);
	}

	default ClusterResponse clusterResponse() {
		ClusterResponse model = new ClusterResponse();
		model.setUuid(uuidC());
		model.setName("The cluster name");
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default ClusterListResponse clusterListResponse() {
		ClusterListResponse model = new ClusterListResponse();
		model.setMetainfo(pagingInfo());
		model.add(clusterResponse());
		model.add(clusterResponse());
		return model;
	}

	default ClusterUpdateRequest clusterUpdateRequest() {
		ClusterUpdateRequest model = new ClusterUpdateRequest();
		model.setName("The cluster name");
		model.setMeta(meta());
		return model;
	}

	default ClusterCreateRequest clusterCreateRequest() {
		ClusterCreateRequest model = new ClusterCreateRequest();
		model.setName("The cluster name");
		model.setMeta(meta());
		return model;
	}

}
