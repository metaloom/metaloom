package io.metaloom.loom.db.model.asset;

/**
 * Time-ranged component of an asset: scenes, silence, shot changes, chapters.
 *
 * <p>
 * Identity: <code>(asset_uuid, node_kind, segment_type, seq)</code>. Replace-in-place happens at the <b>set</b> level: a re-run writes seq 0..N-1 and
 * must delete the segments beyond N - see {@link AssetComponentDao#replaceSegmentComps}.
 * </p>
 */
public interface AssetSegmentComp extends AssetComponent<AssetSegmentComp> {

	/**
	 * Return the segment kind: SCENE, SILENCE, SHOT or CHAPTER.
	 */
	String getSegmentType();

	AssetSegmentComp setSegmentType(String segmentType);

	/**
	 * Return the ordinal within (asset, node kind, segment type), starting at 0.
	 */
	int getSeq();

	AssetSegmentComp setSeq(int seq);

	/**
	 * Return the segment start in milliseconds.
	 */
	long getTimeFrom();

	AssetSegmentComp setTimeFrom(long timeFrom);

	/**
	 * Return the segment end in milliseconds.
	 */
	long getTimeTo();

	AssetSegmentComp setTimeTo(long timeTo);

	/**
	 * Return the human readable label, e.g. a chapter title produced by the LLM node.
	 */
	String getTitle();

	AssetSegmentComp setTitle(String title);

	/**
	 * Return the detector score for this boundary, distinct from the extraction confidence.
	 */
	Float getScore();

	AssetSegmentComp setScore(Float score);
}
