package io.metaloom.loom.rest.builder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.project.Project;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.project.ProjectListResponse;

public class ProjectModelBuilderTest extends AbstractModelBuilderTest {

	@Test
	@Override
	void testResponseModel() throws IOException {
		Project project = mockProject("the_name");
		assertWithModel(builder().toResponse(project), "project.response");
	}

	@Test
	@Override
	void testListResponseModel() throws IOException {
		Project project1 = mockProject("the_name_1");
		Project project2 = mockProject("the_name_2");
		Page<Project> page = mockPage(project1, project2);
		ProjectListResponse list = builder().toProjectList(page);
		assertWithModel(list, "project.list_response");
	}

	public Project mockProject(String name) {
		Project project = mock(Project.class);
		when(project.getName()).thenReturn(name);
		when(project.getUuid()).thenReturn(PROJECT_UUID);
		return project;
	}

}
