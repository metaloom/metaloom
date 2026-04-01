package io.metaloom.loom.rest.model.annotation;

import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface AnnotationExamples extends ExampleValues {

	default Example annotationResponseExample() {
		return new ExampleImpl(annotationResponse(), "The annotation response", HttpResponseStatus.OK);
	}

	default Example annotationUpdateRequestExample() {
		return new ExampleImpl(annotationUpdateRequest(), "The annotation update request", HttpResponseStatus.OK);
	}

	default Example annotationCreateRequestExample() {
		return new ExampleImpl(annotationCreateRequest(), "The annotation create request", HttpResponseStatus.CREATED);
	}

	default Example annotationListResponseExample() {
		return new ExampleImpl(annotationListResponse(), "The annotation list response", HttpResponseStatus.OK);
	}

	default AnnotationResponse annotationResponse() {
		AnnotationResponse model = new AnnotationResponse();
		model.setUuid(uuidA());
		model.setAssetUuid(uuidA());
		model.setDescription("The annotation description");
		model.setTitle("The annotation title");
		model.setMeta(meta());
		model.setType(AnnotationType.FEEDBACK);
		model.setArea(12, 42, 120, 240, 480, 200);
		setCreatorEditor(model);
		return model;
	}

	default AnnotationUpdateRequest annotationUpdateRequest() {
		AnnotationUpdateRequest model = new AnnotationUpdateRequest();
		model.setAssetUuid(uuidA());
		model.setDescription("The annotation description");
		model.setTitle("The annotation title");
		model.setMeta(meta());
		model.setType(AnnotationType.FEEDBACK);
		model.setArea(12, 42, 120, 240, 480, 200);
		return model;
	}

	default AnnotationCreateRequest annotationCreateRequest() {
		AnnotationCreateRequest model = new AnnotationCreateRequest();
		model.setAssetUuid(uuidA());
		model.setDescription("The annotation description");
		model.setTitle("The annotation title");
		model.setMeta(meta());
		model.setType(AnnotationType.FEEDBACK);
		model.setArea(12, 42, 120, 240, 480, 200);
		return model;
	}

	default AnnotationListResponse annotationListResponse() {
		AnnotationListResponse model = new AnnotationListResponse();
		model.setMetainfo(pagingInfo());
		model.add(annotationResponse());
		model.add(annotationResponse());
		return model;
	}

}
