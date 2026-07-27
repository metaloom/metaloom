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

	/**
	 * Assert the number of elements <b>in this page</b>.
	 *
	 * <p>
	 * This deliberately no longer asserts {@code totalCount}. The two were checked together for as long as {@code totalCount} was (incorrectly) populated
	 * with the page size; now that it reports the number of matches across all pages, conflating them would make it impossible to have a page smaller than
	 * the result set. Use {@link #hasTotalCount(long)} for the total.
	 * </p>
	 *
	 * @param size
	 *            expected number of returned elements
	 * @return Fluent API
	 */
	public ListResponseModelAssert hasSize(int size) {
		assertEquals(size, actual.getData().size(), "The expected size did not match up with the results.");
		return this;
	}

	/**
	 * Assert the total number of elements matching the query across all pages.
	 *
	 * @param totalCount
	 *            expected total
	 * @return Fluent API
	 */
	public ListResponseModelAssert hasTotalCount(long totalCount) {
		assertEquals(totalCount, actual.getMetainfo().getTotalCount(), "The expected total count did not match up with the metainfo value");
		return this;
	}

	public ListResponseModelAssert hasPerPage(int perPage) {
		assertEquals(perPage, actual.getMetainfo().getPerPage(), "The per page value did not match up with the metainfo value");
		return this;
	}

}
