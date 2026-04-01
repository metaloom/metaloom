package io.metaloom.loom.rest.builder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.group.GroupListResponse;

public class GroupModelBuilderTest extends AbstractModelBuilderTest {

	@Test
	public void testResponseModel() throws IOException {
		Group group = mockGroup();
		assertWithModel(builder().toResponse(group), "group.response");
	}

	@Test
	public void testListResponseModel() throws IOException {
		Group group = mockGroup();
		Page<Group> page = mockPage(group, group);
		GroupListResponse list = builder().toGroupList(page);
		assertWithModel(list, "group.list_response");
	}

	private Group mockGroup() {
		Group group = mock(Group.class);
		when(group.getName()).thenReturn("group_name");
		mockMeta(group);
		mockCreatorEditor(group);
		return group;
	}

}
