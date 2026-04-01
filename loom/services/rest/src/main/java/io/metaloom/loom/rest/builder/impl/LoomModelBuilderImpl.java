package io.metaloom.loom.rest.builder.impl;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class LoomModelBuilderImpl implements LoomModelBuilder {

	private final DaoCollection daos;
	private final LoomModelValidator validator;

	@Inject
	public LoomModelBuilderImpl(DaoCollection daos, LoomModelValidator validator) {
		this.daos = daos;
		this.validator = validator;
	}

	@Override
	public DaoCollection daos() {
		return daos;
	}

	@Override
	public LoomModelValidator validator() {
		return validator;
	}

}
