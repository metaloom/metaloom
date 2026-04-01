package io.metaloom.loom.db.page;

import java.util.Iterator;
import java.util.List;

import io.metaloom.loom.db.Element;

public class Page<T extends Element<T>> implements Iterable<T> {

	private List<T> list;

	private long perPage;

	public Page(long perPage, List<T> list) {
		this.perPage = perPage;
		this.list = list;
	}

	@Override
	public Iterator<T> iterator() {
		return list.iterator();
	}

	public long size() {
		return list.size();
	}

	public boolean isEmpty() {
		return list.isEmpty();
	}

	public T last() {
		if (isEmpty()) {
			return null;
		} else {
			return list.get(list.size() - 1);
		}
	}

	public T first() {
		if (isEmpty()) {
			return null;
		} else {
			return list.get(0);
		}
	}

	public long perPage() {
		return perPage;
	}

}
