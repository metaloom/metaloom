package io.metaloom.loom.rest.model.segmentcomp;

/**
 * One time-ranged segment within a {@link SegmentCompCreateRequest} batch. The {@code seq} is assigned from list position on the server when absent.
 */
public class SegmentEntry {

	private int seq;
	private long timeFrom;
	private long timeTo;
	private String title;
	private Float score;

	public int getSeq() {
		return seq;
	}

	public SegmentEntry setSeq(int seq) {
		this.seq = seq;
		return this;
	}

	public long getTimeFrom() {
		return timeFrom;
	}

	public SegmentEntry setTimeFrom(long timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	public long getTimeTo() {
		return timeTo;
	}

	public SegmentEntry setTimeTo(long timeTo) {
		this.timeTo = timeTo;
		return this;
	}

	public String getTitle() {
		return title;
	}

	public SegmentEntry setTitle(String title) {
		this.title = title;
		return this;
	}

	public Float getScore() {
		return score;
	}

	public SegmentEntry setScore(Float score) {
		this.score = score;
		return this;
	}

}
