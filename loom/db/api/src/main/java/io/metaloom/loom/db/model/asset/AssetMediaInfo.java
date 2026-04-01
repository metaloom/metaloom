package io.metaloom.loom.db.model.asset;

public interface AssetMediaInfo {

	Long getMediaDuration();

	Asset setMediaDuration(Long mediaDuration);

	/**
	 * Return the media width.
	 * 
	 * @return
	 */
	Integer getMediaWidth();

	/**
	 * Set the media width in pixel.
	 * 
	 * @param width
	 * @return
	 */
	Asset setMediaWidth(Integer width);

	/**
	 * Return the media height.
	 * 
	 * @return
	 */
	Integer getMediaHeight();

	/**
	 * Set the media height in pixel.
	 * 
	 * @param height
	 * @return
	 */
	Asset setMediaHeight(Integer height);
}
