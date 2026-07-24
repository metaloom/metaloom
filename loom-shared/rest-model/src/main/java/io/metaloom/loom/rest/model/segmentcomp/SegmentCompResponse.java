package io.metaloom.loom.rest.model.segmentcomp;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class SegmentCompResponse extends AbstractCreatorEditorRestResponse<SegmentCompResponse> {

	private String assetUuid;
	private String nodeKind;
	private String segmentType;
	private String producerVersion;
	private int seq;
	private long timeFrom;
	private long timeTo;
	private String title;
	private Float score;

	public String getAssetUuid() {
		return assetUuid;
	}

	public SegmentCompResponse setAssetUuid(String assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public String getNodeKind() {
		return nodeKind;
	}

	public SegmentCompResponse setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	public String getSegmentType() {
		return segmentType;
	}

	public SegmentCompResponse setSegmentType(String segmentType) {
		this.segmentType = segmentType;
		return this;
	}

	public String getProducerVersion() {
		return producerVersion;
	}

	public SegmentCompResponse setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	public int getSeq() {
		return seq;
	}

	public SegmentCompResponse setSeq(int seq) {
		this.seq = seq;
		return this;
	}

	public long getTimeFrom() {
		return timeFrom;
	}

	public SegmentCompResponse setTimeFrom(long timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	public long getTimeTo() {
		return timeTo;
	}

	public SegmentCompResponse setTimeTo(long timeTo) {
		this.timeTo = timeTo;
		return this;
	}

	public String getTitle() {
		return title;
	}

	public SegmentCompResponse setTitle(String title) {
		this.title = title;
		return this;
	}

	public Float getScore() {
		return score;
	}

	public SegmentCompResponse setScore(Float score) {
		this.score = score;
		return this;
	}

	@Override
	public SegmentCompResponse self() {
		return this;
	}

}
