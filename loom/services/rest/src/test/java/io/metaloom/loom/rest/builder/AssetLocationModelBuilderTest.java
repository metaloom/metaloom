package io.metaloom.loom.rest.builder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.asset.AssetLocation;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.asset.location.AssetLocationListResponse;

public class AssetLocationModelBuilderTest extends AbstractModelBuilderTest {

	@Test
	@Override
	void testResponseModel() throws IOException {
		AssetLocation location = mockLocation();
		assertWithModel(builder().toResponse(location), "location.response");
	}

	@Test
	@Override
	void testListResponseModel() throws IOException {
		AssetLocation location1 = mockLocation();
		AssetLocation location2 = mockLocation();
		Page<AssetLocation> page = mockPage(location1, location2);
		AssetLocationListResponse list = builder().toLocationList(page);
		assertWithModel(list, "location.list_response");
	}

	private AssetLocation mockLocation() {
		AssetLocation location = mock(AssetLocation.class);
		when(location.getUuid()).thenReturn(ASSET_LOCATION_UUID);
		when(location.getAssetUuid()).thenReturn(ASSET_UUID);
		when(location.getLibraryUuid()).thenReturn(LIBRARY_UUID);
		when(location.getPath()).thenReturn("/pool/bigbuckbunny-4k.mp4");
		when(location.getMimeType()).thenReturn("video/mp4");
		when(location.getPoolUuid()).thenReturn(ASSET_POOL_UUID);
		when(location.getState()).thenReturn("PRESENT");
		when(location.getLicense()).thenReturn("CC-BY-4.0");
		when(location.getLockedByUuid()).thenReturn(USER_UUID);
		when(location.getFilekeyInode()).thenReturn(42L);
		when(location.getFilekeyStDev()).thenReturn(12L);
		when(location.getFilekeyEdate()).thenReturn(100L);
		when(location.getFilekeyEdateNano()).thenReturn(5L);
		mockCreatorEditor(location);
		return location;
	}

}
