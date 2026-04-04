package io.metaloom.loom.rest.model.json;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

public class DatePojo implements RestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("ISO8601 formatted editing date.")
	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssX")
	private Instant edited;

	public Instant getEdited() {
		return edited;
	}

	public void setEdited(Instant edited) {
		this.edited = edited;
	}
}
