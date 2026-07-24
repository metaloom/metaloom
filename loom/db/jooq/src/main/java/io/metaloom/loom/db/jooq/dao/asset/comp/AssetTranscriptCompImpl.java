package io.metaloom.loom.db.jooq.dao.asset.comp;

import java.util.UUID;

import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.vertx.core.json.JsonObject;

public class AssetTranscriptCompImpl extends AbstractAssetCompImpl<AssetTranscriptComp> implements AssetTranscriptComp {

	private int streamIndex;
	private String lang = "";
	private UUID audioCompUuid;
	private String transcriptText;
	private Long duration;
	private Integer wordCount;
	private String model;
	private JsonObject transcriptJson;

	@Override
	public int getStreamIndex() {
		return streamIndex;
	}

	@Override
	public AssetTranscriptComp setStreamIndex(int streamIndex) {
		this.streamIndex = streamIndex;
		return this;
	}

	@Override
	public String getLang() {
		return lang;
	}

	@Override
	public AssetTranscriptComp setLang(String lang) {
		this.lang = lang == null ? "" : lang;
		return this;
	}

	@Override
	public UUID getAudioCompUuid() {
		return audioCompUuid;
	}

	@Override
	public AssetTranscriptComp setAudioCompUuid(UUID audioCompUuid) {
		this.audioCompUuid = audioCompUuid;
		return this;
	}

	@Override
	public String getTranscriptText() {
		return transcriptText;
	}

	@Override
	public AssetTranscriptComp setTranscriptText(String text) {
		this.transcriptText = text;
		return this;
	}

	@Override
	public Long getDuration() {
		return duration;
	}

	@Override
	public AssetTranscriptComp setDuration(Long duration) {
		this.duration = duration;
		return this;
	}

	@Override
	public Integer getWordCount() {
		return wordCount;
	}

	@Override
	public AssetTranscriptComp setWordCount(Integer wordCount) {
		this.wordCount = wordCount;
		return this;
	}

	@Override
	public String getModel() {
		return model;
	}

	@Override
	public AssetTranscriptComp setModel(String model) {
		this.model = model;
		return this;
	}

	@Override
	public JsonObject getTranscriptJson() {
		return transcriptJson;
	}

	@Override
	public AssetTranscriptComp setTranscriptJson(JsonObject json) {
		this.transcriptJson = json;
		return this;
	}
}
