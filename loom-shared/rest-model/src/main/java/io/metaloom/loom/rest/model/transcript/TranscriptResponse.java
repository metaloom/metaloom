package io.metaloom.loom.rest.model.transcript;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;
import io.vertx.core.json.JsonObject;

public class TranscriptResponse extends AbstractCreatorEditorRestResponse<TranscriptResponse>
	implements TranscriptModel<TranscriptResponse> {

	private String source;
	private String lang;
	private String transcriptText;
	private Integer duration;
	private String model;
	private JsonObject transcriptJson;
	private String assetUuid;

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public TranscriptResponse setSource(String source) {
		this.source = source;
		return this;
	}

	@Override
	public String getLang() {
		return lang;
	}

	@Override
	public TranscriptResponse setLang(String lang) {
		this.lang = lang;
		return this;
	}

	@Override
	public String getTranscriptText() {
		return transcriptText;
	}

	@Override
	public TranscriptResponse setTranscriptText(String transcriptText) {
		this.transcriptText = transcriptText;
		return this;
	}

	@Override
	public Integer getDuration() {
		return duration;
	}

	@Override
	public TranscriptResponse setDuration(Integer duration) {
		this.duration = duration;
		return this;
	}

	@Override
	public String getModel() {
		return model;
	}

	@Override
	public TranscriptResponse setModel(String model) {
		this.model = model;
		return this;
	}

	@Override
	public JsonObject getTranscriptJson() {
		return transcriptJson;
	}

	@Override
	public TranscriptResponse setTranscriptJson(JsonObject transcriptJson) {
		this.transcriptJson = transcriptJson;
		return this;
	}

	public String getAssetUuid() {
		return assetUuid;
	}

	public TranscriptResponse setAssetUuid(String assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public TranscriptResponse self() {
		return this;
	}

}
