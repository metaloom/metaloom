package io.metaloom.loom.rest.model.asset.info;

import io.metaloom.loom.rest.model.RestModel;
import io.vertx.core.json.JsonObject;

public class TranscriptInfo implements RestModel {

	private String source;
	private String lang;
	private String transcriptText;
	private Long duration;
	private String model;
	private JsonObject transcriptJson;

	public String getSource() {
		return source;
	}

	public TranscriptInfo setSource(String source) {
		this.source = source;
		return this;
	}

	public String getLang() {
		return lang;
	}

	public TranscriptInfo setLang(String lang) {
		this.lang = lang;
		return this;
	}

	public String getTranscriptText() {
		return transcriptText;
	}

	public TranscriptInfo setTranscriptText(String transcriptText) {
		this.transcriptText = transcriptText;
		return this;
	}

	public Long getDuration() {
		return duration;
	}

	public TranscriptInfo setDuration(Long duration) {
		this.duration = duration;
		return this;
	}

	public String getModel() {
		return model;
	}

	public TranscriptInfo setModel(String model) {
		this.model = model;
		return this;
	}

	public JsonObject getTranscriptJson() {
		return transcriptJson;
	}

	public TranscriptInfo setTranscriptJson(JsonObject transcriptJson) {
		this.transcriptJson = transcriptJson;
		return this;
	}
}
