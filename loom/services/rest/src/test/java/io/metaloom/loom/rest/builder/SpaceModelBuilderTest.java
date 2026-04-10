package io.metaloom.loom.rest.builder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.space.Space;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.space.SpaceListResponse;

public class SpaceModelBuilderTest extends AbstractModelBuilderTest {

	@Test
	@Override
	void testResponseModel() throws IOException {
		Space space = mockSpace("the_name");
		assertWithModel(builder().toResponse(space), "space.response");
	}

	@Test
	@Override
	void testListResponseModel() throws IOException {
		Space space1 = mockSpace("the_name_1");
		Space space2 = mockSpace("the_name_2");
		Page<Space> page = mockPage(space1, space2);
		SpaceListResponse list = builder().toSpaceList(page);
		assertWithModel(list, "space.list_response");
	}

	public Space mockSpace(String name) {
		Space space = mock(Space.class);
		when(space.getName()).thenReturn(name);
		when(space.getUuid()).thenReturn(PROJECT_UUID);
		return space;
	}

}
