package io.metaloom.loom.rest.model.role;

import java.util.Arrays;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface RoleExamples extends ExampleValues {

	default Example roleResponseExample() {
		return new ExampleImpl(roleResponse(), "The role response", HttpResponseStatus.OK);
	}

	default Example roleUpdateRequestExample() {
		return new ExampleImpl(roleUpdateRequest(), "The role update request", HttpResponseStatus.OK);
	}

	default Example roleCreateRequestExample() {
		return new ExampleImpl(roleCreateRequest(), "The role create request", HttpResponseStatus.CREATED);
	}

	default Example roleListResponseExample() {
		return new ExampleImpl(roleListResponse(), "The role list response", HttpResponseStatus.OK);
	}
	
	default RoleResponse roleResponse() {
		RoleResponse model = new RoleResponse();
		model.setUuid(uuidA());
		model.setName("GuestPermissions");
		model.setMeta(meta());
		model.setPermissions(Arrays.asList(RolePermission.CREATE_USER));
		setCreatorEditor(model);
		return model;
	}

	default RoleReference roleReference() {
		RoleReference model = new RoleReference();
		model.setName("GuestPermissions");
		model.setUuid(uuidA());
		return model;
	}

	default RoleCreateRequest roleCreateRequest() {
		RoleCreateRequest model = new RoleCreateRequest();
		model.setName("GuestPermissions");
		model.setPermissions(Arrays.asList(RolePermission.CREATE_USER));
		model.setMeta(meta());
		return model;
	}

	default RoleUpdateRequest roleUpdateRequest() {
		RoleUpdateRequest model = new RoleUpdateRequest();
		model.setName("new_role_name");
		model.setPermissions(Arrays.asList(RolePermission.CREATE_USER));
		model.setMeta(meta());
		return model;
	}

	default RoleListResponse roleListResponse() {
		RoleListResponse model = new RoleListResponse();
		model.add(roleResponse());
		model.add(roleResponse());
		model.setMetainfo(pagingInfo());
		return model;
	}
}
