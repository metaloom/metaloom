package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.rest.model.annotation.AnnotationCreateRequest;
import io.metaloom.loom.rest.model.annotation.AnnotationListResponse;
import io.metaloom.loom.rest.model.annotation.AnnotationResponse;
import io.metaloom.loom.rest.model.annotation.AnnotationUpdateRequest;

public class AnnotationEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		AnnotationResponse annotation = client.loadAnnotation(ANNOTATION_UUID).sync().body();
		assertThat(annotation).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		AnnotationCreateRequest request = new AnnotationCreateRequest();
		request.setTitle("dummy title");
		request.setType(AnnotationType.FEEDBACK);
		request.setAssetUuid(ASSET_UUID);
		AnnotationResponse annotation = client.createAnnotation(request).sync().body();
		assertThat(annotation).isValid();

		AnnotationResponse annotation2 = client.loadAnnotation(annotation.getUuid()).sync().body();
		assertThat(annotation2).matches(annotation2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteAnnotation(ANNOTATION_UUID).sync().body();
		expect(404, "Not Found", client.loadAnnotation(ANNOTATION_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		AnnotationUpdateRequest update = new AnnotationUpdateRequest();
		update.setTitle("updated-title");
		AnnotationResponse response = client.updateAnnotation(ANNOTATION_UUID, update).sync().body();
		assertThat(response).isValid();
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			AnnotationCreateRequest request = new AnnotationCreateRequest();
			request.setTitle("dummy title " + i);
			request.setType(AnnotationType.FEEDBACK);
			request.setAssetUuid(ASSET_UUID);
			client.createAnnotation(request).sync().body();
		}
		AnnotationListResponse list = client.listAnnotations().sync().body();
		// 100 created here plus the one seeded by the fixture. totalCount reports every match across
		// all pages, not the size of this page - the two are only equal when the result set fits in one
		// page, which is exactly what made the old conflated assertion look correct.
		assertThat(list).isValid().hasSize(25).hasPerPage(25).hasTotalCount(101);
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		AnnotationCreateRequest request = new AnnotationCreateRequest();
		request.setTitle("perm-check");
		request.setType(AnnotationType.FEEDBACK);
		request.setAssetUuid(ASSET_UUID);
		return client.createAnnotation(request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadAnnotation(ANNOTATION_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listAnnotations();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteAnnotation(ANNOTATION_UUID);
	}

}
