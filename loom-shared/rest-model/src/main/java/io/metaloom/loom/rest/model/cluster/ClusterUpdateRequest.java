package io.metaloom.loom.rest.model.cluster;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class ClusterUpdateRequest extends AbstractMetaModel<ClusterUpdateRequest> implements RestRequestModel {

	private String name;

	private String type;

	public String getName() {
		return name;
	}

	public ClusterUpdateRequest setName(String name) {
		this.name = name;
		return this;
	}

	public String getType() {
		return type;
	}

	public ClusterUpdateRequest setType(String type) {
		this.type = type;
		return this;
	}

	@Override
	public ClusterUpdateRequest self() {
		return this;
	}

}
