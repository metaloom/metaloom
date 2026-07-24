package io.metaloom.loom.rest.model.segmentcomp;

import java.util.List;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface SegmentCompExamples extends ExampleValues {

	default Example segmentCompCreateRequestExample() {
		return new ExampleImpl(segmentCompCreateRequest(), "The segment component batch replace request", HttpResponseStatus.CREATED);
	}

	default Example segmentCompResponseExample() {
		return new ExampleImpl(segmentCompResponse(), "The segment component response", HttpResponseStatus.OK);
	}

	default Example segmentCompListResponseExample() {
		return new ExampleImpl(segmentCompListResponse(), "The segment component list response", HttpResponseStatus.OK);
	}

	default SegmentCompResponse segmentCompResponse() {
		SegmentCompResponse model = new SegmentCompResponse();
		model.setUuid(uuidC());
		model.setAssetUuid(uuidA().toString());
		model.setNodeKind("scene-detection");
		model.setSegmentType("SCENE");
		model.setProducerVersion("v1");
		model.setSeq(0);
		model.setTimeFrom(0L);
		model.setTimeTo(4200L);
		model.setScore(0.87f);
		setCreatorEditor(model);
		return model;
	}

	default SegmentCompCreateRequest segmentCompCreateRequest() {
		SegmentCompCreateRequest model = new SegmentCompCreateRequest();
		model.setNodeKind("scene-detection");
		model.setSegmentType("SCENE");
		model.setProducerVersion("v1");
		model.setSegments(List.of(
			new SegmentEntry().setSeq(0).setTimeFrom(0L).setTimeTo(4200L).setScore(0.87f),
			new SegmentEntry().setSeq(1).setTimeFrom(4200L).setTimeTo(9100L).setScore(0.63f)));
		return model;
	}

	default SegmentCompListResponse segmentCompListResponse() {
		SegmentCompListResponse model = new SegmentCompListResponse();
		model.setMetainfo(pagingInfo());
		model.add(segmentCompResponse());
		return model;
	}

}
