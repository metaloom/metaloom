package deprecated.model.extension;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

public class ExtensionUpdateRequest implements RestModel {

	@JsonProperty(required = false)
	@JsonPropertyDescription("The URL to the extension endpoint service")
	private String url;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The kind of extension that the endpoint service supports.")
	private ExtensionKind kind;

	public ExtensionUpdateRequest() {
	}

	public String getURL() {
		return url;
	}

	public void setURL(String url) {
		this.url = url;
	}

	public ExtensionKind getKind() {
		return kind;
	}

	public ExtensionUpdateRequest setKind(ExtensionKind kind) {
		this.kind = kind;
		return this;
	}

}
