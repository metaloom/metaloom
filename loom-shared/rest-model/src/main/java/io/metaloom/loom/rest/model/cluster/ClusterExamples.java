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

	default Example clusterMemberListResponseExample() {
		return new ExampleImpl(clusterMemberListResponse(), "The cluster member list response", HttpResponseStatus.OK);
	}

	default Example clusterConfirmExample() {
		return new ExampleImpl(clusterConfirmRequest(), "The cluster confirmation request", HttpResponseStatus.OK);
	}

	default Example clusterBulkCreateRequestExample() {
		return new ExampleImpl(clusterBulkCreateRequest(), "The cluster bulk create request", HttpResponseStatus.CREATED);
	}

	default Example clusterBulkResponseExample() {
		return new ExampleImpl(clusterBulkResponse(), "The cluster bulk create response", HttpResponseStatus.CREATED);
	}

	default ClusterBulkCreateRequest clusterBulkCreateRequest() {
		ClusterBulkCreateRequest model = new ClusterBulkCreateRequest();
		model.add(new ClusterCreateItem()
			.setType("face")
			.setNodeKind("facedetect")
			.setProducerVersion("1/inspireface-pikachu-r18")
			.setClusterIndex(0)
			.setScore(0.94f)
			.setModel("inspireface-pikachu-r18")
			.setDimensions(512)
			.add(new ClusterMemberCreateItem().setEmbeddingUuid(uuidA().toString()).setConfidence(0.96f).setOrigin("AUTO")));
		return model;
	}

	default ClusterBulkResponse clusterBulkResponse() {
		ClusterBulkResponse model = new ClusterBulkResponse();
		model.add(clusterResponse());
		model.setTotal(1);
		model.setCreated(1);
		model.setFailed(0);
		model.setPruned(0);
		return model;
	}

	default ClusterResponse clusterResponse() {
		ClusterResponse model = new ClusterResponse();
		model.setUuid(uuidC());
		model.setName("The cluster name");
		model.setType("face");
		model.setReviewStatus("PENDING");
		model.setClusterIndex(0);
		model.setScore(0.92f);
		model.setMemberCount(4L);
		model.setNodeKind("facedetect");
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default ClusterMemberListResponse clusterMemberListResponse() {
		ClusterMemberListResponse model = new ClusterMemberListResponse();
		model.add(clusterMember());
		model.add(clusterMember());
		model.setTotal(2);
		return model;
	}

	default ClusterMemberModel clusterMember() {
		return new ClusterMemberModel()
			.setEmbeddingUuid(uuidA().toString())
			.setDetectionUuid(uuidB().toString())
			.setAssetUuid(uuidC().toString())
			.setFrameNumber(0)
			.setBboxX(0.25f)
			.setBboxY(0.15f)
			.setBboxWidth(0.12f)
			.setBboxHeight(0.2f)
			.setConfidence(0.97f)
			.setOrigin("AUTO");
	}

	default ClusterConfirmRequest clusterConfirmRequest() {
		return new ClusterConfirmRequest()
			.setAlias("Anna Meyer")
			.setFirstname("Anna")
			.setLastname("Meyer");
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
