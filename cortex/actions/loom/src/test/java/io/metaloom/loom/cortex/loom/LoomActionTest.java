package io.metaloom.loom.cortex.loom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.metaloom.cortex.action.loom.LoomAction;
import io.metaloom.cortex.action.loom.LoomActionOptions;
import io.metaloom.cortex.api.action.ActionResult;
import io.metaloom.cortex.api.action.context.ActionContext;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.action.media.AbstractMediaTest;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.asset.AssetBulkResponse;
import io.metaloom.loom.rest.model.asset.AssetBulkUpdateRequest;

public class LoomActionTest extends AbstractMediaTest {

	@Test
	public void testOfflineMode() throws IOException {
		// When client is null, action runs in offline mode and skips
		LoomAction action = new LoomAction(null, options(), new LoomActionOptions());
		LoomMedia media = mediaVideo1();
		ActionResult result = action.process(media);
		assertNotNull(result);
	}

	@Test
	public void testBulkAccumulation() throws IOException, LoomClientException {
		// Mock client with bulk update support
		LoomHttpClient client = mockBulkClient();
		LoomAction action = new LoomAction(client, options(), new LoomActionOptions());

		// Process 3 items - should not trigger flush (batch size is 50)
		for (int i = 0; i < 3; i++) {
			LoomMedia media = mediaVideo1();
			ActionResult result = action.process(ActionContext.create(media));
			assertNotNull(result);
		}

		// Verify no bulk call was made yet (batch not full)
		verify(client, never()).bulkUpdateAssets(any(AssetBulkUpdateRequest.class));

		// Flush manually
		action.flush();

		// Now verify the bulk call was made with all 3 entries
		ArgumentCaptor<AssetBulkUpdateRequest> captor = ArgumentCaptor.forClass(AssetBulkUpdateRequest.class);
		verify(client, times(1)).bulkUpdateAssets(captor.capture());
		AssetBulkUpdateRequest capturedRequest = captor.getValue();
		assertEquals(3, capturedRequest.getAssets().size());
	}

	@Test
	public void testFlushOnEmptyIsNoop() throws IOException, LoomClientException {
		LoomHttpClient client = mockBulkClient();
		LoomAction action = new LoomAction(client, options(), new LoomActionOptions());

		// Flush on empty should not make any calls
		action.flush();
		verify(client, never()).bulkUpdateAssets(any(AssetBulkUpdateRequest.class));
	}

	@SuppressWarnings("unchecked")
	private LoomHttpClient mockBulkClient() throws LoomClientException {
		LoomHttpClient client = mock(LoomHttpClient.class);

		// Mock bulk update response
		LoomClientRequest<AssetBulkResponse> bulkRequest = mock(LoomClientRequest.class);
		AssetBulkResponse bulkResponse = new AssetBulkResponse();
		bulkResponse.setTotal(0);
		bulkResponse.setCreated(0);
		bulkResponse.setFailed(0);
		when(bulkRequest.sync()).thenReturn(bulkResponse);
		when(client.bulkUpdateAssets(any(AssetBulkUpdateRequest.class))).thenReturn(bulkRequest);

		return client;
	}

}
