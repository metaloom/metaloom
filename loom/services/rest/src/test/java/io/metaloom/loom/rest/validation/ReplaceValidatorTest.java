package io.metaloom.loom.rest.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.asset.AssetUpdateRequest;
import io.metaloom.loom.rest.model.group.GroupUpdateRequest;
import io.metaloom.loom.rest.model.user.UserUpdateRequest;
import io.vertx.core.json.JsonObject;

public class ReplaceValidatorTest {

	@Test
	public void testUserFields() {
		assertThat(ReplaceValidator.replaceableFields(UserUpdateRequest.class))
			.containsExactlyInAnyOrder("username", "firstname", "lastname", "email", "meta");
	}

	@Test
	public void testGroupFields() {
		assertThat(ReplaceValidator.replaceableFields(GroupUpdateRequest.class))
			.containsExactlyInAnyOrder("name", "meta");
	}

	@Test
	public void testAssetFields() {
		Set<String> fields = ReplaceValidator.replaceableFields(AssetUpdateRequest.class);
		// The field has no accessors and can thus never be sent by a client
		assertThat(fields).doesNotContain("dominantColor");
		// Kind specific fields opt out via @ReplaceOptional
		assertThat(fields).doesNotContain("image", "video", "audio", "document", "geo", "timeline", "s3", "consistency", "fingerprint");
		assertThat(fields).containsExactlyInAnyOrder("filename", "meta", "tags", "file", "hashes", "media");
	}

	@Test
	public void testCompleteBody() {
		JsonObject body = new JsonObject()
			.put("username", "joe")
			.put("firstname", "Joe")
			.put("lastname", "Doe")
			.put("email", "joe@doe.tld")
			.put("meta", new JsonObject());
		ReplaceValidator.assertComplete(body, UserUpdateRequest.class);
	}

	@Test
	public void testPresentButNullIsPresent() {
		JsonObject body = new JsonObject()
			.put("username", "joe")
			.put("firstname", "Joe")
			.put("lastname", "Doe")
			.putNull("email")
			.putNull("meta");
		ReplaceValidator.assertComplete(body, UserUpdateRequest.class);
	}

	@Test
	public void testMissingFieldIsRejected() {
		JsonObject body = new JsonObject().put("username", "joe");
		assertThatThrownBy(() -> ReplaceValidator.assertComplete(body, UserUpdateRequest.class))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining("firstname")
			.hasMessageContaining("lastname")
			.hasMessageContaining("email")
			.hasMessageContaining("meta");
	}

	@Test
	public void testNullBodyIsRejected() {
		assertThatThrownBy(() -> ReplaceValidator.assertComplete(null, UserUpdateRequest.class))
			.isInstanceOf(ValidationException.class);
	}

	@Test
	public void testReplaceOptionalIsExcluded() {
		assertThat(ReplaceValidator.replaceableFields(DummyRequest.class))
			.containsExactly("required");
	}

	public static class DummyRequest implements RestRequestModel {

		private String required;

		@ReplaceOptional
		private String optional;

		public String getRequired() {
			return required;
		}

		public DummyRequest setRequired(String required) {
			this.required = required;
			return this;
		}

		public String getOptional() {
			return optional;
		}

		public DummyRequest setOptional(String optional) {
			this.optional = optional;
			return this;
		}
	}

}
