package io.metaloom.loom.db.page;

import java.util.Iterator;
import java.util.List;

import io.metaloom.loom.db.Element;

public class Page<T extends Element<T>> implements Iterable<T> {

	/**
	 * Value of {@link #totalCount()} when the total was not computed by the producing DAO.
	 */
	public static final long TOTAL_COUNT_UNKNOWN = -1;

	private List<T> list;

	private long perPage;

	private long totalCount;

	/**
	 * Create a page whose total element count is not known.
	 *
	 * @deprecated Use {@link #Page(long, long, List)} so consumers can report a meaningful total. Kept for DAOs which cannot cheaply count.
	 */
	@Deprecated
	public Page(long perPage, List<T> list) {
		this(perPage, TOTAL_COUNT_UNKNOWN, list);
	}

	/**
	 * Create a page.
	 *
	 * @param perPage
	 *            Requested page size
	 * @param totalCount
	 *            Total number of elements matching the query across all pages, or {@link #TOTAL_COUNT_UNKNOWN}
	 * @param list
	 *            Elements of this page
	 */
	public Page(long perPage, long totalCount, List<T> list) {
		this.perPage = perPage;
		this.totalCount = totalCount;
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

	/**
	 * Return the total number of elements matching the query across all pages.
	 *
	 * <p>
	 * This is <b>not</b> {@link #size()}, which returns the number of elements in this page only.
	 * </p>
	 *
	 * @return Total element count, or {@link #TOTAL_COUNT_UNKNOWN} when the producing DAO did not compute it
	 */
	public long totalCount() {
		return totalCount;
	}

}
