package io.metaloom.loom.db.model.perm;


public class ResourcePermission {

	private String permission;

	private String resource;

	public String getResource() {
		return resource;
	}

	public ResourcePermission setResource(String resource) {
		this.resource = resource;
		return this;
	}

	public String getPermission() {
		return permission;
	}

	public ResourcePermission setPermission(String permission) {
		this.permission = permission;
		return this;
	}

	@Override
	public String toString() {
		return "Permission[" + getResource() + "," + getPermission() + "]";
	}

}
