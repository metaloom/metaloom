package io.metaloom.loom.db.model.asset;

public interface AssetVideoInfo extends AssetMediaInfo {

	String getVideoEncoding();

	Asset setVideoEncoding(String videoEncoding);

	Integer getVideoBitrate();

	Asset setVideoBitrate(Integer bitrate);

}
