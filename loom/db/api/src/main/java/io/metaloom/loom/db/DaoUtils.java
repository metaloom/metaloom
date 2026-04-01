package io.metaloom.loom.db;

import java.util.Objects;

public final class DaoUtils {

	public static void requireUuid(Element<?> element, String name) {
		Objects.requireNonNull(element, "A valid element [" + name + "] must be provided.");
		Objects.requireNonNull(element.getUuid(), "The element [" + name + "] that was provided did not have a uuid");
	}

}
