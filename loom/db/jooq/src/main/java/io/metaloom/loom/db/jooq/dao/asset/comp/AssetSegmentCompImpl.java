package io.metaloom.loom.db.jooq.dao.asset.comp;

import io.metaloom.loom.db.model.asset.AssetSegmentComp;

public class AssetSegmentCompImpl extends AbstractAssetCompImpl<AssetSegmentComp> implements AssetSegmentComp {

	private String segmentType;
	private int seq;
	private long timeFrom;
	private long timeTo;
	private String title;
	private Float score;

	@Override
	public String getSegmentType() {
		return segmentType;
	}

	@Override
	public AssetSegmentComp setSegmentType(String segmentType) {
		this.segmentType = segmentType;
		return this;
	}

	@Override
	public int getSeq() {
		return seq;
	}

	@Override
	public AssetSegmentComp setSeq(int seq) {
		this.seq = seq;
		return this;
	}

	@Override
	public long getTimeFrom() {
		return timeFrom;
	}

	@Override
	public AssetSegmentComp setTimeFrom(long timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	@Override
	public long getTimeTo() {
		return timeTo;
	}

	@Override
	public AssetSegmentComp setTimeTo(long timeTo) {
		this.timeTo = timeTo;
		return this;
	}

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public AssetSegmentComp setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public Float getScore() {
		return score;
	}

	@Override
	public AssetSegmentComp setScore(Float score) {
		this.score = score;
		return this;
	}
}
