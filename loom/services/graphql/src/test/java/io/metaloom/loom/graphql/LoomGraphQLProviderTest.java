package io.metaloom.loom.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetAudioComp;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.asset.AssetImageComp;
import io.metaloom.loom.db.model.asset.AssetLocation;
import io.metaloom.loom.db.model.asset.AssetLocationDao;
import io.metaloom.loom.db.model.asset.AssetVideoComp;
import io.metaloom.utils.hash.SHA512;

/**
 * Tests for the {@link LoomGraphQLProvider}.
 */
public class LoomGraphQLProviderTest {

	private static final UUID ASSET_UUID = UUID.fromString("d5781040-1234-5678-9abc-def012345678");

	private DaoCollection daos;
	private AssetDao assetDao;
	private AssetLocationDao locationDao;
	private AssetComponentDao componentDao;
	private LoomGraphQLProvider provider;

	@BeforeEach
	void setup() {
		daos = mock(DaoCollection.class);
		assetDao = mock(AssetDao.class);
		locationDao = mock(AssetLocationDao.class);
		componentDao = mock(AssetComponentDao.class);

		when(daos.assetDao()).thenReturn(assetDao);
		when(daos.assetLocationDao()).thenReturn(locationDao);
		when(daos.assetComponentDao()).thenReturn(componentDao);

		// Default stubs
		when(assetDao.load(any())).thenReturn(null);
		doReturn(Stream.empty()).when(assetDao).findAll();
		doReturn(Stream.empty()).when(locationDao).findAll();
		when(componentDao.loadImageComps(any())).thenReturn(Collections.emptyList());
		when(componentDao.loadVideoComps(any())).thenReturn(Collections.emptyList());
		when(componentDao.loadAudioComps(any())).thenReturn(Collections.emptyList());

		provider = new LoomGraphQLProvider(daos);
	}

	@Test
	void testQueryAssetByUuid() {
		Asset asset = mockAsset(ASSET_UUID, "test.jpg", "image/jpeg", 1024L);
		when(assetDao.load(ASSET_UUID)).thenReturn(asset);
		when(componentDao.loadImageComps(ASSET_UUID)).thenReturn(Collections.emptyList());
		when(componentDao.loadVideoComps(ASSET_UUID)).thenReturn(Collections.emptyList());
		when(componentDao.loadAudioComps(ASSET_UUID)).thenReturn(Collections.emptyList());

		String query = "{ asset(uuid: \"" + ASSET_UUID + "\") { uuid filename mimeType size } }";
		ExecutionResult result = execute(query);

		assertTrue(result.getErrors().isEmpty(), "Expected no errors but got: " + result.getErrors());
		Map<String, Object> data = result.getData();
		assertNotNull(data);
		Map<String, Object> assetData = (Map<String, Object>) data.get("asset");
		assertNotNull(assetData);
		assertEquals(ASSET_UUID.toString(), assetData.get("uuid"));
		assertEquals("test.jpg", assetData.get("filename"));
		assertEquals("image/jpeg", assetData.get("mimeType"));
		assertEquals(1024L, ((Number) assetData.get("size")).longValue());
	}

	@Test
	void testQueryAssetNotFound() {
		String query = "{ asset(uuid: \"" + UUID.randomUUID() + "\") { uuid filename } }";
		ExecutionResult result = execute(query);

		assertTrue(result.getErrors().isEmpty(), "Expected no errors but got: " + result.getErrors());
		Map<String, Object> data = result.getData();
		assertNull(data.get("asset"));
	}

	@Test
	void testQueryAssets() {
		Asset asset1 = mockAsset(UUID.randomUUID(), "a.jpg", "image/jpeg", 100L);
		Asset asset2 = mockAsset(UUID.randomUUID(), "b.mp4", "video/mp4", 200L);
		doReturn(Stream.of(asset1, asset2)).when(assetDao).findAll();

		String query = "{ assets { uuid filename mimeType } }";
		ExecutionResult result = execute(query);

		assertTrue(result.getErrors().isEmpty(), "Expected no errors but got: " + result.getErrors());
		Map<String, Object> data = result.getData();
		List<Map<String, Object>> assets = (List<Map<String, Object>>) data.get("assets");
		assertEquals(2, assets.size());
		assertEquals("a.jpg", assets.get(0).get("filename"));
		assertEquals("b.mp4", assets.get(1).get("filename"));
	}

	@Test
	void testQueryAssetWithImageComponents() {
		Asset asset = mockAsset(ASSET_UUID, "photo.jpg", "image/jpeg", 5000L);
		when(assetDao.load(ASSET_UUID)).thenReturn(asset);

		AssetImageComp imageComp = mock(AssetImageComp.class);
		when(imageComp.getUuid()).thenReturn(UUID.randomUUID());
		when(imageComp.getSource()).thenReturn("exif");
		when(imageComp.getImageDominantColor()).thenReturn("#ff0000");
		when(imageComp.getMediaWidth()).thenReturn(1920);
		when(imageComp.getMediaHeight()).thenReturn(1080);
		when(componentDao.loadImageComps(ASSET_UUID)).thenReturn(List.of(imageComp));
		when(componentDao.loadVideoComps(ASSET_UUID)).thenReturn(Collections.emptyList());
		when(componentDao.loadAudioComps(ASSET_UUID)).thenReturn(Collections.emptyList());

		String query = "{ asset(uuid: \"" + ASSET_UUID + "\") { uuid imageComponents { source dominantColor width height } } }";
		ExecutionResult result = execute(query);

		assertTrue(result.getErrors().isEmpty(), "Expected no errors but got: " + result.getErrors());
		Map<String, Object> data = result.getData();
		Map<String, Object> assetData = (Map<String, Object>) data.get("asset");
		List<Map<String, Object>> comps = (List<Map<String, Object>>) assetData.get("imageComponents");
		assertEquals(1, comps.size());
		assertEquals("exif", comps.get(0).get("source"));
		assertEquals("#ff0000", comps.get(0).get("dominantColor"));
		assertEquals(1920, comps.get(0).get("width"));
		assertEquals(1080, comps.get(0).get("height"));
	}

	@Test
	void testQueryAssetWithLocations() {
		Asset asset = mockAsset(ASSET_UUID, "doc.pdf", "application/pdf", 3000L);
		when(assetDao.load(ASSET_UUID)).thenReturn(asset);
		when(componentDao.loadImageComps(ASSET_UUID)).thenReturn(Collections.emptyList());
		when(componentDao.loadVideoComps(ASSET_UUID)).thenReturn(Collections.emptyList());
		when(componentDao.loadAudioComps(ASSET_UUID)).thenReturn(Collections.emptyList());

		AssetLocation location = mock(AssetLocation.class);
		UUID locationUuid = UUID.randomUUID();
		UUID libraryUuid = UUID.randomUUID();
		when(location.getUuid()).thenReturn(locationUuid);
		when(location.getPath()).thenReturn("/data/files/doc.pdf");
		when(location.getAssetUuid()).thenReturn(ASSET_UUID);
		when(location.getLibraryUuid()).thenReturn(libraryUuid);
		when(location.getMimeType()).thenReturn("application/pdf");
		doReturn(Stream.of(location)).when(locationDao).findAll();

		String query = "{ asset(uuid: \"" + ASSET_UUID + "\") { uuid locations { path mimeType } } }";
		ExecutionResult result = execute(query);

		assertTrue(result.getErrors().isEmpty(), "Expected no errors but got: " + result.getErrors());
		Map<String, Object> data = result.getData();
		Map<String, Object> assetData = (Map<String, Object>) data.get("asset");
		List<Map<String, Object>> locations = (List<Map<String, Object>>) assetData.get("locations");
		assertEquals(1, locations.size());
		assertEquals("/data/files/doc.pdf", locations.get(0).get("path"));
		assertEquals("application/pdf", locations.get(0).get("mimeType"));
	}

	// --- Helpers ---

	@SuppressWarnings("unchecked")
	private ExecutionResult execute(String query) {
		return provider.graphQL().execute(
			ExecutionInput.newExecutionInput().query(query).build());
	}

	private Asset mockAsset(UUID uuid, String filename, String mimeType, long size) {
		Asset asset = mock(Asset.class);
		when(asset.getUuid()).thenReturn(uuid);
		when(asset.getFilename()).thenReturn(filename);
		when(asset.getMimeType()).thenReturn(mimeType);
		when(asset.getSize()).thenReturn(size);
		return asset;
	}
}
