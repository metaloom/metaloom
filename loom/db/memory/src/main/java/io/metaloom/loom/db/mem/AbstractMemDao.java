package io.metaloom.loom.db.mem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.Element;
import io.metaloom.loom.db.page.Page;

public abstract class AbstractMemDao<T extends Element<T>> implements CRUDDao<T> {

	protected Map<UUID, T> storage = new HashMap<>();

	@Override
	public T load(UUID id) {
		return storage.get(id);
	}

	@Override
	public void delete(UUID id) {
		storage.remove(id);
	}

	@Override
	public void store(T element) {
		UUID id = element.getUuid();
		storage.put(id, element);
	}

	@Override
	public T update(T element) {
		UUID id = element.getUuid();
		storage.put(id, element);
		return element;
	}

	@Override
	public void clear() {
		storage.clear();
	}

	@Override
	public long count() {
		return storage.size();
	}

	@Override
	public Stream<T> findAll() {
		return storage.values().stream();
	}

	@Override
	public Page<T> loadPage(UUID from, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection) {
		List<T> list = new ArrayList<>();
		int n = 0;
		boolean start = from == null;
		for (T element : storage.values()) {
			n++;
			if (n >= pageSize) {
				break;
			}
			if (start) {
				list.add(element);
			} else {
				if (from.equals(element.getUuid())) {
					start = true;
					list.add(element);
				}
			}
		}
		return new Page<>(pageSize, list);
	}

}
