package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.metaloom.loom.rest.model.tag.TagListResponse;
import io.metaloom.loom.rest.model.tag.TagRatingRequest;
import io.metaloom.loom.rest.model.tag.TagRatingResponse;
import io.metaloom.loom.rest.model.tag.TagResponse;
import io.metaloom.loom.rest.model.tag.TagUpdateRequest;

public class TagEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		TagResponse tag = client.loadTag(TAG_UUID).sync().body();
		assertThat(tag).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		TagCreateRequest request = new TagCreateRequest();
		request.setName("dummy name");
		request.setCollection("test");
		TagResponse tag = client.createTag(request).sync().body();
		assertThat(tag).isValid();

		TagResponse tag2 = client.loadTag(tag.getUuid()).sync().body();
		assertThat(tag2).matches(tag2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteTag(TAG_UUID).sync().body();
		expect(404, "Not Found", client.loadTag(TAG_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		TagUpdateRequest update = new TagUpdateRequest();
		update.setName("updated-name");
		TagResponse response = client.updateTag(TAG_UUID, update).sync().body();
		assertThat(response).isValid();
	}

	@Test
	public void testRating() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// No rating yet
			TagRatingResponse initial = client.loadTagRating(TAG_UUID).sync().body();
			assertNull(initial.getRating(), "The tag should not carry a rating initially");

			// Set a rating
			TagRatingResponse rated = client.rateTag(TAG_UUID, new TagRatingRequest().setRating(8)).sync().body();
			assertEquals(Integer.valueOf(8), rated.getRating());

			// Read it back
			TagRatingResponse read = client.loadTagRating(TAG_UUID).sync().body();
			assertEquals(Integer.valueOf(8), read.getRating());

			// Update in place
			client.rateTag(TAG_UUID, new TagRatingRequest().setRating(3)).sync().body();
			assertEquals(Integer.valueOf(3), client.loadTagRating(TAG_UUID).sync().body().getRating());

			// Remove it
			client.deleteTagRating(TAG_UUID).sync().body();
			assertNull(client.loadTagRating(TAG_UUID).sync().body().getRating(), "The rating should have been removed");
		}
	}

	@Test
	public void testRatingValidation() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			expect(400, "Bad Request", client.rateTag(TAG_UUID, new TagRatingRequest().setRating(42)));
		}
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			TagCreateRequest request = new TagCreateRequest();
			request.setName("dummy title " + i);
			request.setCollection("test");
			client.createTag(request).sync().body();
		}
		TagListResponse list = client.listTags().sync().body();
		assertThat(list).isValid().hasSize(25).hasPerPage(25);
	}

}
