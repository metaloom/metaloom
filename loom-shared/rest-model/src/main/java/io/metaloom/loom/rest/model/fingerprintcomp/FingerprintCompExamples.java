package io.metaloom.loom.rest.model.fingerprintcomp;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface FingerprintCompExamples extends ExampleValues {

	default Example fingerprintCompCreateRequestExample() {
		return new ExampleImpl(fingerprintCompCreateRequest(), "The fingerprint component create request", HttpResponseStatus.CREATED);
	}

	default Example fingerprintCompResponseExample() {
		return new ExampleImpl(fingerprintCompResponse(), "The fingerprint component response", HttpResponseStatus.OK);
	}

	default Example fingerprintCompListResponseExample() {
		return new ExampleImpl(fingerprintCompListResponse(), "The fingerprint component list response", HttpResponseStatus.OK);
	}

	default FingerprintCompResponse fingerprintCompResponse() {
		FingerprintCompResponse model = new FingerprintCompResponse();
		model.setUuid(uuidC());
		model.setAssetUuid(uuidA().toString());
		model.setNodeKind("fingerprint");
		model.setAlgorithm("metaloom-multisector-v1");
		model.setSectorIndex(0);
		model.setFingerprint("a1b2c3d4e5f6");
		model.setProducerVersion("v1");
		setCreatorEditor(model);
		return model;
	}

	default FingerprintCompCreateRequest fingerprintCompCreateRequest() {
		FingerprintCompCreateRequest model = new FingerprintCompCreateRequest();
		model.setNodeKind("fingerprint");
		model.setAlgorithm("metaloom-multisector-v1");
		model.setSectorIndex(0);
		model.setFingerprint("a1b2c3d4e5f6");
		model.setProducerVersion("v1");
		return model;
	}

	default FingerprintCompListResponse fingerprintCompListResponse() {
		FingerprintCompListResponse model = new FingerprintCompListResponse();
		model.setMetainfo(pagingInfo());
		model.add(fingerprintCompResponse());
		return model;
	}

}
