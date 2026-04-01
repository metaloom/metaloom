package io.metaloom.loom.rest.model.asset.info;

import io.metaloom.loom.rest.model.RestModel;

public class DocumentInfo implements RestModel {

	private String source;

	private Long wordCount;

	private String plainText;

	public String getSource() {
		return source;
	}

	public DocumentInfo setSource(String source) {
		this.source = source;
		return this;
	}

	public String getPlainText() {
		return plainText;
	}

	public DocumentInfo setPlainText(String plainText) {
		this.plainText = plainText;
		return this;
	}

	public Long getWordCount() {
		return wordCount;
	}

	public DocumentInfo setWordCount(Long documentWordCount) {
		this.wordCount = documentWordCount;
		return this;
	}

}
