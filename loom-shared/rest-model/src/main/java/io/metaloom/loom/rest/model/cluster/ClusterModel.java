package io.metaloom.loom.rest.model.cluster;

import io.metaloom.loom.rest.model.MetaModel;

public interface ClusterModel<T extends ClusterModel<T>> extends MetaModel<T> {

	String getName();

	T setName(String name);

}
