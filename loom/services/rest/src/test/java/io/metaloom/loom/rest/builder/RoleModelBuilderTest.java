package io.metaloom.loom.rest.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.role.RoleDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.builder.impl.LoomModelBuilderImpl;
import io.metaloom.loom.rest.model.role.RoleListResponse;
import io.metaloom.loom.rest.model.role.RolePermission;
import io.metaloom.loom.rest.model.role.RoleResponse;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class RoleModelBuilderTest extends AbstractModelBuilderTest {

	@Test
	@Override
	void testResponseModel() throws IOException {
		Role role = mockRole();
		assertWithModel(builder().toResponse(role), "role.response");
	}

	@Test
	@Override
	void testListResponseModel() throws IOException {
		Role role = mockRole();
		Page<Role> page = mockPage(role, role);
		RoleListResponse list = builder().toRoleList(page);
		assertWithModel(list, "role.list_response");
	}

	/**
	 * The response reports the role's grants, loaded from {@code role_permission}. The field used to be left unset by the builder, so the ACL matrix
	 * showed every role as empty no matter what it granted.
	 */
	@Test
	void testResponseCarriesPermissions() {
		Role role = mockRole();
		when(role.getUuid()).thenReturn(ROLE_UUID);

		DaoCollection daos = mock(DaoCollection.class, Mockito.RETURNS_MOCKS);
		RoleDao roleDao = mock(RoleDao.class);
		when(roleDao.loadPermissions(ROLE_UUID)).thenReturn(EnumSet.of(Permission.READ_ASSET, Permission.CREATE_ASSET));
		when(daos.roleDao()).thenReturn(roleDao);
		LoomModelBuilder builder = new LoomModelBuilderImpl(daos, new LoomModelValidatorImpl());

		RoleResponse response = builder.toResponse(role);

		// Sorted by name so the response does not reorder when the enum is reordered.
		assertEquals(List.of(RolePermission.CREATE_ASSET, RolePermission.READ_ASSET), response.getPermissions());
	}

	/**
	 * A role that grants nothing reports an empty list, never {@code null} - a client can then tell "grants nothing" from "not reported".
	 */
	@Test
	void testResponseReportsEmptyPermissionsAsAList() {
		Role role = mockRole();
		when(role.getUuid()).thenReturn(ROLE_UUID);

		DaoCollection daos = mock(DaoCollection.class, Mockito.RETURNS_MOCKS);
		RoleDao roleDao = mock(RoleDao.class);
		when(roleDao.loadPermissions(ROLE_UUID)).thenReturn(EnumSet.noneOf(Permission.class));
		when(daos.roleDao()).thenReturn(roleDao);
		LoomModelBuilder builder = new LoomModelBuilderImpl(daos, new LoomModelValidatorImpl());

		RoleResponse response = builder.toResponse(role);

		assertNotNull(response.getPermissions());
		assertTrue(response.getPermissions().isEmpty());
	}

	private Role mockRole() {
		Role role = mock(Role.class);
		when(role.getName()).thenReturn("the_role_name");
		// The response carries a creator/editor block; without a creator uuid the builder
		// leaves it empty (machine-written rows).
		mockCreatorEditorRefs(role);
		return role;
	}

}
