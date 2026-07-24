package io.metaloom.loom.rest.model.segmentcomp;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

/**
 * Batch replace request for the time-ranged segment component ({@code asset_segment_comp}): scenes, silence, shots, chapters.
 *
 * <p>
 * The whole set for {@code (asset, node_kind, segment_type)} is replaced atomically — surplus rows from a shorter re-run are deleted. Segment
 * {@code seq} values are assigned from list position when not supplied.
 * </p>
 */
public class SegmentCompCreateRequest extends AbstractMetaModel<SegmentCompCreateRequest> implements RestRequestModel {

	private String nodeKind;
	private String segmentType;
	private String producerVersion;
	private List<SegmentEntry> segments = new ArrayList<>();

	public String getNodeKind() {
		return nodeKind;
	}

	public SegmentCompCreateRequest setNodeKind(String nodeKind) {
		this.nodeKind = nodeKind;
		return this;
	}

	public String getSegmentType() {
		return segmentType;
	}

	public SegmentCompCreateRequest setSegmentType(String segmentType) {
		this.segmentType = segmentType;
		return this;
	}

	public String getProducerVersion() {
		return producerVersion;
	}

	public SegmentCompCreateRequest setProducerVersion(String producerVersion) {
		this.producerVersion = producerVersion;
		return this;
	}

	public List<SegmentEntry> getSegments() {
		return segments;
	}

	public SegmentCompCreateRequest setSegments(List<SegmentEntry> segments) {
		this.segments = segments;
		return this;
	}

	@Override
	public SegmentCompCreateRequest self() {
		return this;
	}

}
