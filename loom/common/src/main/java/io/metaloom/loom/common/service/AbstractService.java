package io.metaloom.loom.common.service;

import io.metaloom.loom.api.options.LoomOptions;
import io.vertx.core.Vertx;

public abstract class AbstractService {

	private Vertx vertx;
	private LoomOptions options;

	public AbstractService(Vertx vertx, LoomOptions options) {
		this.vertx = vertx;
		this.options = options;
	}

	public Vertx vertx() {
		return vertx;
	}

	public LoomOptions options() {
		return options;
	}

}
