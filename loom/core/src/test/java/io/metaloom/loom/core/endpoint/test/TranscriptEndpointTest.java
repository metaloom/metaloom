package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.transcript.TranscriptCreateRequest;
import io.metaloom.loom.rest.model.transcript.TranscriptListResponse;
import io.metaloom.loom.rest.model.transcript.TranscriptResponse;
import io.metaloom.loom.rest.model.transcript.TranscriptUpdateRequest;

public class TranscriptEndpointTest extends AbstractEndpointTest {

	@Test
	public void testCreate() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			TranscriptResponse response = createTranscript(client, "whisper", 0, "en", "Hello world");
			assertNotNull(response.getUuid());
			assertEquals("whisper", response.getSource());
			assertEquals("en", response.getLang());
			assertEquals(0, response.getStreamIndex());
			assertEquals("Hello world", response.getTranscriptText());
			assertEquals(ASSET_UUID.toString(), response.getAssetUuid());
		}
	}

	/**
	 * Re-posting the same (asset, source, stream, lang) must upsert the single row rather than fail on the unique constraint. This is what makes a
	 * node re-run idempotent.
	 */
	@Test
	public void testCreateIsIdempotent() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			createTranscript(client, "whisper", 0, "en", "first version");
			createTranscript(client, "whisper", 0, "en", "second version");

			TranscriptListResponse list = client.listAssetTranscripts(ASSET_UUID).sync().body();
			assertNotNull(list);
			assertEquals(1, list.getData().size(), "Re-posting the same key must replace the row, not append a duplicate");
			assertEquals("second version", list.getData().get(0).getTranscriptText());
		}
	}

	/**
	 * A different track (stream_index) is a distinct transcript and must coexist.
	 */
	@Test
	public void testDifferentTrackCoexists() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			createTranscript(client, "whisper", 0, "en", "track zero");
			createTranscript(client, "whisper", 1, "en", "track one");

			TranscriptListResponse list = client.listAssetTranscripts(ASSET_UUID).sync().body();
			assertEquals(2, list.getData().size());
		}
	}

	/**
	 * A user without UPDATE_ASSET may not write a transcript for the asset.
	 */
	@Test
	public void testCreateRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			TranscriptCreateRequest request = new TranscriptCreateRequest();
			request.setSource("whisper");
			request.setStreamIndex(0);
			request.setLang("en");
			request.setTranscriptText("hello");
			expect(403, "Forbidden", client.createAssetTranscript(ASSET_UUID, request));
		}
	}

	/**
	 * A user without READ_ASSET may not list the transcripts.
	 */
	@Test
	public void testListRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.listAssetTranscripts(ASSET_UUID));
		}
	}

	/**
	 * A user without READ_ASSET may not load a single transcript - the check fires before the DAO lookup, so even a
	 * non-existent uuid must still be rejected with 403, not 404.
	 */
	@Test
	public void testLoadRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.loadAssetTranscript(ASSET_UUID, UUID.randomUUID()));
		}
	}

	/**
	 * A user without UPDATE_ASSET may not update a transcript.
	 */
	@Test
	public void testUpdateRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			TranscriptUpdateRequest request = new TranscriptUpdateRequest();
			request.setTranscriptText("changed");
			expect(403, "Forbidden", client.updateAssetTranscript(ASSET_UUID, UUID.randomUUID(), request));
		}
	}

	/**
	 * A user without UPDATE_ASSET may not delete a transcript.
	 */
	@Test
	public void testDeleteRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.deleteAssetTranscript(ASSET_UUID, UUID.randomUUID()));
		}
	}

	private TranscriptResponse createTranscript(LoomHttpClient client, String source, int streamIndex, String lang, String text)
		throws LoomClientException {
		TranscriptCreateRequest request = new TranscriptCreateRequest();
		request.setSource(source);
		request.setProducerVersion("ggml-base");
		request.setStreamIndex(streamIndex);
		request.setLang(lang);
		request.setTranscriptText(text);
		return client.createAssetTranscript(ASSET_UUID, request).sync().body();
	}
}
