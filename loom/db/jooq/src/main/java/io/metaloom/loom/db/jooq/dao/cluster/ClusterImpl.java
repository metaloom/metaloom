package io.metaloom.loom.db.jooq.dao.cluster;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.cluster.Cluster;

public class ClusterImpl extends AbstractEditableElement<Cluster> implements Cluster {

	private String name;

	private String type;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Cluster setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getType() {
		return type;
	}

	@Override
	public Cluster setType(String type) {
		this.type = type;
		return this;
	}

}
