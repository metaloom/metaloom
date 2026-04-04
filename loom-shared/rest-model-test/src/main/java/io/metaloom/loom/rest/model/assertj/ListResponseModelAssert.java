package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.loom.rest.model.common.AbstractListResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class ListResponseModelAssert extends AbstractAssert<ListResponseModelAssert, AbstractListResponse<?, ?>> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public ListResponseModelAssert(AbstractListResponse<?, ?> actual) {
		super(actual, ListResponseModelAssert.class);
	}

	public ListResponseModelAssert isValid() {
		validator.validate(actual.getMetainfo());
		return this;
	}

	public ListResponseModelAssert hasSize(int size) {
		assertEquals(size, actual.getMetainfo().getTotalCount(), "The expected size did not match up with the total count");
		assertEquals(size, actual.getData().size(), "The expected size did not match up with the results.");
		return this;
	}

	public ListResponseModelAssert hasPerPage(int perPage) {
		assertEquals(perPage, actual.getMetainfo().getPerPage(), "The per page value did not match up with the metainfo value");
		return this;
	}

}
