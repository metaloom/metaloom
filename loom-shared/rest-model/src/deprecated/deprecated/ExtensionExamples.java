package io.metaloom.loom.rest.model.example;

import deprecated.model.extension.ExtensionCreateRequest;
import deprecated.model.extension.ExtensionKind;
import deprecated.model.extension.ExtensionListResponse;
import deprecated.model.extension.ExtensionResponse;
import deprecated.model.extension.ExtensionStatus;
import deprecated.model.extension.ExtensionUpdateRequest;

public class ExtensionExamples extends AbstractExamples {

	public static ExtensionResponse extensionResponse() {
		ExtensionResponse model = new ExtensionResponse();
		model.setUuid(uuidC());
		model.setURL("http://localhost:8086/asset");
		model.setKind(ExtensionKind.ASSET_PROCESSOR);
		model.setServiceStatus(ExtensionStatus.NOMINAL);
		setCreatorEditor(model);
		return model;
	}

	public static ExtensionUpdateRequest extensionUpdateRequest() {
		ExtensionUpdateRequest model = new ExtensionUpdateRequest();
		model.setURL("http://localhost:8088/asset");
		model.setKind(ExtensionKind.ASSET_PROCESSOR);
		return model;
	}

	public static ExtensionCreateRequest extensionCreateRequest() {
		ExtensionCreateRequest model = new ExtensionCreateRequest();
		model.setURL("http://localhost:8088/asset");
		model.setKind(ExtensionKind.ASSET_PROCESSOR);
		return model;
	}

	public static ExtensionListResponse extensionListResponse() {
		ExtensionListResponse model = new ExtensionListResponse();
		model.setMetainfo(pagingInfo());
		model.add(extensionResponse());
		model.add(extensionResponse());
		return model;
	}
}
