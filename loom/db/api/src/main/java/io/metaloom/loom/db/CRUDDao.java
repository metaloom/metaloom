package io.metaloom.loom.db;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.page.Page;

/**
 * @param <T>
 *            Element type for the DTO
 */
public interface CRUDDao<T extends Element<T>> extends Dao {

	default void delete(T element) {
		delete(element.getUuid());
	}

	/**
	 * Delete the element with the given id.
	 * 
	 * @param id
	 */
	void delete(UUID id);

	T update(T element);

	T load(UUID id);

	/**
	 * Store the element.
	 * 
	 * @param element
	 */
	void store(T element);

	/**
	 * Store multiple elements in a single batch operation.
	 * 
	 * @param elements
	 */
	default void storeBatch(List<T> elements) {
		for (T element : elements) {
			store(element);
		}
	}

	Page<T> loadPage(UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);

	Stream<? extends T> findAll();

}
