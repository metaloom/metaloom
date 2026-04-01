package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class ClusterModelAssert extends AbstractModelAssert<ClusterModelAssert, ClusterResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public ClusterModelAssert(ClusterResponse actual) {
		super(actual, ClusterModelAssert.class);
	}

	public ClusterModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public ClusterModelAssert matches(ClusterResponse response) {
		assertEquals(response.getName(), actual.getName(), "The name did not match");
		return this;
	}

}
