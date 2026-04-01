package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.annotation.AnnotationCreateRequest;
import io.metaloom.loom.rest.model.annotation.AnnotationListResponse;
import io.metaloom.loom.rest.model.annotation.AnnotationResponse;
import io.metaloom.loom.rest.model.annotation.AnnotationUpdateRequest;

public interface AnnotationMethods {

	LoomClientRequest<AnnotationResponse> loadAnnotation(UUID annotationUuid);

	LoomClientRequest<AnnotationResponse> createAnnotation(AnnotationCreateRequest request);

	LoomClientRequest<AnnotationResponse> updateAnnotation(UUID annotationUuid, AnnotationUpdateRequest request);

	LoomClientRequest<AnnotationListResponse> listAnnotations();

	LoomClientRequest<NoResponse> deleteAnnotation(UUID annotationUuid);
}
