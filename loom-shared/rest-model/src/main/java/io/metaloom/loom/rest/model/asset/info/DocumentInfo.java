package io.metaloom.loom.rest.model.asset.info;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

public class DocumentInfo implements RestModel {

	private String source;

	private Long wordCount;

	private String plainText;

	@JsonPropertyDescription("The total page count of the document, when the producer reports one.")
	private Integer pageCount;

	@JsonPropertyDescription("The detected or declared language of the extracted text.")
	private String textLang;

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

	public Integer getPageCount() {
		return pageCount;
	}

	public DocumentInfo setPageCount(Integer pageCount) {
		this.pageCount = pageCount;
		return this;
	}

	public String getTextLang() {
		return textLang;
	}

	public DocumentInfo setTextLang(String textLang) {
		this.textLang = textLang;
		return this;
	}

}
