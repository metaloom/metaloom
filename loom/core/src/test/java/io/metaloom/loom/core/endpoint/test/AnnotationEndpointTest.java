package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.annotation.AnnotationCreateRequest;
import io.metaloom.loom.rest.model.annotation.AnnotationListResponse;
import io.metaloom.loom.rest.model.annotation.AnnotationResponse;
import io.metaloom.loom.rest.model.annotation.AnnotationUpdateRequest;

public class AnnotationEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		AnnotationResponse annotation = client.loadAnnotation(ANNOTATION_UUID).sync();
		assertThat(annotation).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		AnnotationCreateRequest request = new AnnotationCreateRequest();
		request.setTitle("dummy title");
		request.setAssetUuid(ASSET_UUID);
		AnnotationResponse annotation = client.createAnnotation(request).sync();
		assertThat(annotation).isValid();

		AnnotationResponse annotation2 = client.loadAnnotation(annotation.getUuid()).sync();
		assertThat(annotation2).matches(annotation2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteAnnotation(ANNOTATION_UUID).sync();
		expect(404, "Not Found", client.loadAnnotation(ANNOTATION_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		AnnotationUpdateRequest update = new AnnotationUpdateRequest();
		update.setTitle("updated-title");
		AnnotationResponse response = client.updateAnnotation(ANNOTATION_UUID, update).sync();
		assertThat(response).isValid();
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			AnnotationCreateRequest request = new AnnotationCreateRequest();
			request.setTitle("dummy title " + i);
			client.createAnnotation(request).sync();
		}
		AnnotationListResponse list = client.listAnnotations().sync();
		assertThat(list).isValid().hasSize(25).hasPerPage(25);
	}

}
