package deprecated.model.extension;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class ExtensionResponse extends AbstractCreatorEditorRestResponse {

	@JsonPropertyDescription("The URL to the extension endpoint service.")
	private String url;

	@JsonPropertyDescription("The kind of extension that the endpoint service supports.")
	private ExtensionKind kind;

	@JsonPropertyDescription("The current status of the endpoint service.")
	private ExtensionStatus serviceStatus;

	public ExtensionResponse() {

	}

	public String getURL() {
		return url;
	}

	public ExtensionResponse setURL(String url) {
		this.url = url;
		return this;
	}

	public ExtensionKind getKind() {
		return kind;
	}

	public ExtensionResponse setKind(ExtensionKind kind) {
		this.kind = kind;
		return this;
	}

	public ExtensionStatus getServiceStatus() {
		return serviceStatus;
	}

	public void setServiceStatus(ExtensionStatus serviceStatus) {
		this.serviceStatus = serviceStatus;
	}

}
