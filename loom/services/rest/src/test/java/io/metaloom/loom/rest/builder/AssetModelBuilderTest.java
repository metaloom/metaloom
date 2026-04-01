package io.metaloom.loom.rest.builder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.asset.AssetListResponse;

public class AssetModelBuilderTest extends AbstractModelBuilderTest {

	@Test
	@Override
	void testResponseModel() throws IOException {
		Asset asset = mockAsset("primary");
		assertWithModel(builder().toResponse(asset), "asset.response");
	}

	@Test
	@Override
	void testListResponseModel() throws IOException {
		Asset asset1 = mockAsset("primary");
		Asset asset2 = mockAsset("secondary");
		Page<Asset> page = mockPage(asset1, asset2);
		AssetListResponse list = builder().toAssetList(page);
		assertWithModel(list, "asset.list_response");
	}

	private Asset mockAsset(String title) {
		Asset asset = mock(Asset.class);
		when(asset.getUuid()).thenReturn(TASK_UUID);
		when(asset.getSHA512()).thenReturn(SHA512SUM);
		when(asset.getSHA256()).thenReturn(SHA256SUM);
		when(asset.getMD5()).thenReturn(MD5SUM);
		return asset;
	}

}
