package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.AttachmentEndpointService;

public class AttachmentEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(AttachmentEndpoint.class);

	private final AttachmentEndpointService service;
	private final ModelExamples examples;

	@Inject
	public AttachmentEndpoint(AttachmentEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "attachment";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/attachments";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Create
		addUploadRoute(basePath(),
			"Create a new attachment from an uploaded file. Expects a multipart request with one file part named 'file'. Optional form fields: "
				+ "'assetUuid' (the asset this attachment describes - its storage pool then also receives these bytes), 'embeddingUuid', "
				+ "'type' (CONTACT_SHEET, POSTER_FRAME, WAVEFORM, PROXY, EXTRACTED_AUDIO, ...) and 'poolUuid'.",
			examples.attachmentResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		// Update
		addRoute(basePath() + "/:uuid", POST,
			"Update a attachment",
			examples.attachmentUpdateRequestExample(),
			examples.attachmentResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Delete
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a attachment",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		// List
		addListRoute(basePath(), GET,
			"Load a paged list of attachments",
			examples.attachmentListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load a attachment",
			null,
			examples.attachmentResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});

		// Download the raw bytes
		addDownloadRoute(basePath() + "/:uuid/data",
			"Download the raw bytes of an attachment.",
			lrc -> {
				service.download(lrc, lrc.pathParamUUID("uuid"));
			});

	}

}
