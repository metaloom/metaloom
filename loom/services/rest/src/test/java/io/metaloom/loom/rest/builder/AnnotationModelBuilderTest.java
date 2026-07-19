package io.metaloom.loom.rest.builder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.annotation.Annotation;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.annotation.AnnotationListResponse;

public class AnnotationModelBuilderTest extends AbstractModelBuilderTest {

	@Test
	@Override
	void testResponseModel() throws IOException {
		Annotation annotation = mockAnnotation();
		assertWithModel(builder().toResponse(annotation), "annotation.response");
	}

	@Test
	@Override
	void testListResponseModel() throws IOException {
		Annotation annotation = mockAnnotation();
		Page<Annotation> page = mockPage(annotation, annotation);
		AnnotationListResponse list = builder().toAnnotationList(page);
		assertWithModel(list, "annotation.list_response");
	}

	private Annotation mockAnnotation() {
		Annotation annotation = mock(Annotation.class);
		when(annotation.getUuid()).thenReturn(ANNOTATION_UUID);
		when(annotation.getAssetUuid()).thenReturn(ASSET_UUID);
		when(annotation.getTitle()).thenReturn("the_title");
		when(annotation.getDescription()).thenReturn("the_description");
		mockCreatorEditor(annotation);
		return annotation;
	}
}
