package io.metaloom.loom.rest.model;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.rest.model.example.Examples;

/**
 * Injectable aggregator dependency for REST model examples.
 */
@Singleton
public class ModelExamples implements Examples {

	@Inject
	public ModelExamples() {
	}

}
