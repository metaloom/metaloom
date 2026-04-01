package io.metaloom.loom.db.model.asset;

public interface AssetAudioInfo extends AssetMediaInfo {

	String getAudioEncoding();

	Asset setAudioEncoding(String audioEncoding);

	Integer getAudioSamplingRate();

	Asset setAudioSampleRate(Integer audioSampleRate);

	Integer getAudioBPM();

	Asset setAudioBPM(Integer audioBPM);

	Integer getAudioChannels();

	Asset setAudioChannels(Integer channels);

	Integer getAudioBitrate();

	Asset setAudioBitrate(Integer bitrate);

}
