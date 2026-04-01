package io.metaloom.loom.rest.model.assertj;

import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class AssetModelAssert extends AbstractModelAssert<AssetModelAssert, AssetResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public AssetModelAssert(AssetResponse actual) {
		super(actual, AssetModelAssert.class);
	}

	public AssetModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public AssetModelAssert matches(AssetCreateRequest request) {
		assertJson(request.getMeta(), actual.getMeta(), "The meta information mismatch");
		assertJson(request.getHashes(), actual.getHashes(), "The hash information mismatch");
		assertJson(request.getFile(), actual.getFile(), "The file information mismatch");

		// Component fields are stored in separate tables and returned as lists.
		if (request.getAudio() != null) {
			isNotNull();
			org.assertj.core.api.Assertions.assertThat(actual.getAudioComponents())
				.as("Audio components should not be empty").isNotEmpty();
		}
		if (request.getVideo() != null) {
			org.assertj.core.api.Assertions.assertThat(actual.getVideoComponents())
				.as("Video components should not be empty").isNotEmpty();
		}
		if (request.getDocument() != null) {
			org.assertj.core.api.Assertions.assertThat(actual.getDocumentComponents())
				.as("Document components should not be empty").isNotEmpty();
		}
		if (request.getImage() != null) {
			org.assertj.core.api.Assertions.assertThat(actual.getImageComponents())
				.as("Image components should not be empty").isNotEmpty();
		}
		if (request.getGeo() != null) {
			org.assertj.core.api.Assertions.assertThat(actual.getGeoComponents())
				.as("Geo components should not be empty").isNotEmpty();
		}

		return this;
	}

}
