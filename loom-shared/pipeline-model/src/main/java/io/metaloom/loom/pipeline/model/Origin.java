package io.metaloom.loom.pipeline.model;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Where a data element came from, and its place in the sequence it belongs to.
 *
 * <p>
 * This is what makes the implicit gather possible. When one asset fans out into N elements and two
 * branches each process them, a downstream node has to be able to put the branches back together —
 * and it can only do that if every element still says which source asset it descends from. The
 * {@code itemId} is exactly the run item's id, because <strong>the run item is the origin</strong>:
 * fan-out happens <em>inside</em> one item rather than by spawning child items, so no lineage
 * bookkeeping is needed to answer "same asset?".
 * </p>
 *
 * <p>
 * {@code seq} is the element's index within its producing port, and {@code total} the size of that
 * sequence. Together they let a downstream {@code ONE} input zip against a sibling branch by index,
 * and let a gather present its elements in a stable order.
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Origin {

	private final String itemId;
	private final int seq;
	private final Integer total;

	@JsonCreator
	public Origin(@JsonProperty("itemId") String itemId, @JsonProperty("seq") int seq,
		@JsonProperty("total") Integer total) {
		this.itemId = Objects.requireNonNull(itemId, "An origin item id must be set");
		this.seq = seq;
		this.total = total;
	}

	/**
	 * The origin of a single-element payload.
	 */
	public static Origin single(String itemId) {
		return new Origin(itemId, 0, 1);
	}

	/**
	 * The origin of element {@code seq} of a sequence of {@code total}.
	 */
	public static Origin of(String itemId, int seq, int total) {
		return new Origin(itemId, seq, total);
	}

	/**
	 * @return the run item this element descends from — the source asset's identity
	 */
	public String getItemId() {
		return itemId;
	}

	/**
	 * @return this element's index within its producing port
	 */
	public int getSeq() {
		return seq;
	}

	/**
	 * @return how many elements the producing port emitted, or null when not yet known
	 */
	public Integer getTotal() {
		return total;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Origin other)) {
			return false;
		}
		return seq == other.seq && itemId.equals(other.itemId) && Objects.equals(total, other.total);
	}

	@Override
	public int hashCode() {
		return Objects.hash(itemId, seq, total);
	}

	@Override
	public String toString() {
		return itemId + "#" + seq + (total != null ? "/" + total : "");
	}
}
