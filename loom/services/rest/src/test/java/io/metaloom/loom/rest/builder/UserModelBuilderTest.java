package io.metaloom.loom.rest.builder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.user.UserListResponse;

public class UserModelBuilderTest extends AbstractModelBuilderTest {

	@Test
	@Override
	public void testResponseModel() throws IOException {
		User user = mockUser();
		assertWithModel(builder().toResponse(user), "user.response");
	}

	@Test
	@Override
	public void testListResponseModel() throws IOException {
		User user = mockUser();
		Page<User> page = mockPage(user, user);
		UserListResponse list = builder().toUserList(page);
		assertWithModel(list, "user.list_response");
	}

	private User mockUser() {
		User user = mock(User.class);
		when(user.getUsername()).thenReturn("joeDoe");
		return user;
	}

}
