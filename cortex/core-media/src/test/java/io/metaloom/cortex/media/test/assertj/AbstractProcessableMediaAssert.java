package io.metaloom.cortex.media.test.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.api.media.LoomMedia;

public abstract class AbstractProcessableMediaAssert<T extends AbstractProcessableMediaAssert<T, M>, M extends LoomMedia>
	extends AbstractAssert<T, M> {

	public AbstractProcessableMediaAssert(M actual, Class<?> clazz) {
		super(actual, clazz);
	}

	protected abstract T self();

	public T hasXAttr(String fullKey) {
		assertTrue(actual.listXAttr().contains(fullKey), "The attr " + fullKey + " was not found in the media file.");
		return self();
	}

	public T printXAttrKeys() {
		actual.listXAttr().forEach(System.out::println);
		return self();
	}

	public T hasXAttr(int count) {
		int actualCount = actual.listXAttr().size();
		if (count != actualCount) {
			String allKeys = String.join(" ,\n", actual.listXAttr());
			assertEquals(count, actualCount, "The count of xattr did not match the expected count. Got:\n" + allKeys);
		}
		return self();
	}

}
