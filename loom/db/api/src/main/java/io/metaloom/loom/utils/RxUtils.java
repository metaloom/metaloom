package io.metaloom.loom.utils;

import io.reactivex.rxjava3.core.Maybe;

public final class RxUtils {

	/**
	 * Convert the nullable element into a maybe.
	 * 
	 * @param <T>
	 * @param value
	 * @return
	 */
	public static <T> Maybe<T> ofNullable(T value) {
		return value == null ? Maybe.empty() : Maybe.just(value);
	}
}
