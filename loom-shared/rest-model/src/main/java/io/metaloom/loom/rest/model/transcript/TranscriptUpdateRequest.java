package io.metaloom.loom.rest.model.transcript;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;
import io.vertx.core.json.JsonObject;

public class TranscriptUpdateRequest extends AbstractMetaModel<TranscriptUpdateRequest>
	implements RestRequestModel, TranscriptModel<TranscriptUpdateRequest> {

	private String source;
	private String lang;
	private String transcriptText;
	private Long duration;
	private String model;
	private JsonObject transcriptJson;

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public TranscriptUpdateRequest setSource(String source) {
		this.source = source;
		return this;
	}

	@Override
	public String getLang() {
		return lang;
	}

	@Override
	public TranscriptUpdateRequest setLang(String lang) {
		this.lang = lang;
		return this;
	}

	@Override
	public String getTranscriptText() {
		return transcriptText;
	}

	@Override
	public TranscriptUpdateRequest setTranscriptText(String transcriptText) {
		this.transcriptText = transcriptText;
		return this;
	}

	@Override
	public Long getDuration() {
		return duration;
	}

	@Override
	public TranscriptUpdateRequest setDuration(Long duration) {
		this.duration = duration;
		return this;
	}

	@Override
	public String getModel() {
		return model;
	}

	@Override
	public TranscriptUpdateRequest setModel(String model) {
		this.model = model;
		return this;
	}

	@Override
	public JsonObject getTranscriptJson() {
		return transcriptJson;
	}

	@Override
	public TranscriptUpdateRequest setTranscriptJson(JsonObject transcriptJson) {
		this.transcriptJson = transcriptJson;
		return this;
	}

	@Override
	public TranscriptUpdateRequest self() {
		return this;
	}

}
