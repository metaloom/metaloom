package io.metaloom.loom.rest.model.common;

public abstract class AbstractNamedReference<T extends AbstractNamedReference<T>> extends AbstractResponse<T> {

	private String name;

	public String getName() {
		return name;
	}

	public T setName(String name) {
		this.name = name;
		return self();
	}
}
