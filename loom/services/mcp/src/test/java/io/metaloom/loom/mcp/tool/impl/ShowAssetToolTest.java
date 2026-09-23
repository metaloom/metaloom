package io.metaloom.loom.mcp.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * {@code show_asset}, the tool that puts an asset on screen.
 *
 * <p>
 * What is pinned here is the envelope, because the envelope <em>is</em> the feature: the model never sees the visual, so nothing in the conversation
 * would notice it going missing. A silently dropped {@code payload.kind} is a player that renders as a grey box, and only a test says so.
 * </p>
 */
public class ShowAssetToolTest {

	private static final UUID ASSET_UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000f00d");

	private DaoCollection daos;

	private AssetDao assetDao;

	@BeforeEach
	public void setup() {
		daos = mock(DaoCollection.class);
		assetDao = mock(AssetDao.class);
		when(daos.assetDao()).thenReturn(assetDao);
	}

	private void catalogHolds(String filename, String mimeType) {
		Asset asset = mock(Asset.class);
		when(asset.getUuid()).thenReturn(ASSET_UUID);
		when(asset.getFilename()).thenReturn(filename);
		when(asset.getMimeType()).thenReturn(mimeType);
		when(asset.getSize()).thenReturn(4_711_000L);
		when(assetDao.loadById(any())).thenReturn(asset);
	}

	private static String text(JsonObject result) {
		JsonArray content = result.getJsonArray("content");
		assertNotNull(content, "The tool result should carry content");
		return content.getJsonObject(0).getString("text");
	}

	private static JsonObject onlyVisual(JsonObject result) {
		JsonArray visuals = result.getJsonArray("visuals");
		assertNotNull(visuals, "The result should carry a visual — it is the whole output of this tool");
		assertEquals(1, visuals.size(), "One asset, one viewer");
		return visuals.getJsonObject(0);
	}

	@Test
	public void testDescriptor() {
		ShowAssetTool tool = new ShowAssetTool(daos);
		assertEquals("show_asset", tool.descriptor().name());
		// The card pulls a poster and a stream, and both routes gate on READ_ASSET_BINARY. Declaring only
		// READ_ASSET would advertise the tool to a caller whose viewer can then only 401.
		assertEquals(List.of("READ_ASSET", "READ_ASSET_BINARY"), tool.descriptor().requiredPermissions());

		JsonObject schema = tool.descriptor().inputSchema();
		assertEquals(List.of("assetId"), schema.getJsonArray("required").getList());
		JsonObject properties = schema.getJsonObject("properties");
		assertTrue(properties.containsKey("startMs"), "Opening on the passage is the point of calling this after a transcript search");
		assertFalse(properties.containsKey("startSeconds"), "One time parameter, in the unit the search tools answer in — see testTakesTheSearchHitsOwnUnit");
		assertTrue(properties.containsKey("caption"));
	}

	@Test
	public void testShowsAVideoAsAViewerVisual() {
		catalogHolds("bigbuckbunny.mkv", "video/x-matroska");

		JsonObject result = new ShowAssetTool(daos).execute(new JsonObject().put("assetId", ASSET_UUID.toString())).result();

		JsonObject visual = onlyVisual(result);
		assertEquals("asset-viewer", visual.getString("type"));
		assertEquals(ASSET_UUID.toString(), visual.getString("uuid"));
		assertEquals("bigbuckbunny.mkv", visual.getString("label"));

		JsonObject payload = visual.getJsonObject("payload");
		assertEquals(ASSET_UUID.toString(), payload.getString("assetUuid"));
		assertEquals("bigbuckbunny.mkv", payload.getString("filename"));
		assertEquals("video/x-matroska", payload.getString("mimeType"));
		assertEquals("video", payload.getString("kind"), "A .mkv is a video even though no browser decodes the container");
		assertEquals(4_711_000L, payload.getLong("size"));
		assertNull(payload.getValue("startSeconds"), "Nothing was asked for, so the viewer opens at the top");

		// The reference is what makes the asset clickable elsewhere in the transcript; the text is what
		// the model reads back, and it has to say the showing happened.
		assertEquals("asset", result.getJsonArray("references").getJsonObject(0).getString("type"));
		assertTrue(text(result).contains("bigbuckbunny.mkv"));
	}

	/**
	 * Milliseconds in, seconds out — and the conversion happens here rather than in the model.
	 *
	 * <p>
	 * This is the whole reason the parameter is not called {@code startSeconds}. It was, for exactly one deployment, and the first model to use it
	 * passed a transcript hit's raw {@code timeFromMs} straight through: the viewer opened ten days into a 43-minute episode while the same answer
	 * claimed "15 minutes in". The number is copied from another tool's output, so it is read in that tool's unit.
	 * </p>
	 */
	@Test
	public void testTakesTheSearchHitsOwnUnit() {
		catalogHolds("sg1-s01e01.mkv", "video/x-matroska");

		JsonObject result = new ShowAssetTool(daos)
			.execute(new JsonObject().put("assetId", ASSET_UUID.toString()).put("startMs", 604_500)).result();

		assertEquals(604.5d, onlyVisual(result).getJsonObject("payload").getDouble("startSeconds"),
			"The card speaks seconds; turning the hit's milliseconds into them is this tool's job");
		assertTrue(text(result).contains("10:04"), "The text has to name the position too, or the model cannot say where it opened");
	}

	/**
	 * An offset no media file could have is reported, not obeyed and not swallowed.
	 *
	 * <p>
	 * Silently opening at the start would leave the model free to tell the user the viewer is at 15 minutes when it is at zero, which is exactly how
	 * the millisecond bug read from the outside: two numbers in one answer that could not both be true.
	 * </p>
	 */
	@Test
	public void testRefusesAnOffsetLongerThanAnyMedia() {
		catalogHolds("sg1-s10e06.mkv", "video/x-matroska");

		JsonObject result = new ShowAssetTool(daos)
			.execute(new JsonObject().put("assetId", ASSET_UUID.toString()).put("startMs", ShowAssetTool.MAX_START_MS + 1)).result();

		assertNull(onlyVisual(result).getJsonObject("payload").getValue("startSeconds"), "The viewer opens at the start");
		assertTrue(text(result).contains("ignored"), text(result));
		assertTrue(text(result).contains("milliseconds"), "The model has to be told which unit it got wrong: " + text(result));
	}

	/**
	 * A quoted number still opens the player where the model meant.
	 *
	 * <p>
	 * The schema says {@code number}, and a grammar-constrained server honours it; plenty of models emit a string anyway, and the resulting failure —
	 * a viewer opening at 0:00 — is invisible to the model, to the logs and to everything except the person watching.
	 * </p>
	 */
	@Test
	public void testAcceptsAQuotedOffset() {
		catalogHolds("sg1-s01e01.mkv", "video/x-matroska");

		JsonObject result = new ShowAssetTool(daos)
			.execute(new JsonObject().put("assetId", ASSET_UUID.toString()).put("startMs", "604500")).result();

		assertEquals(604.5d, onlyVisual(result).getJsonObject("payload").getDouble("startSeconds"));
	}

	/** A negative offset is a bad number, not a refusal: opening at the start is the right answer to it. */
	@Test
	public void testDropsAnImpossibleOffset() {
		catalogHolds("clip.mp4", "video/mp4");

		JsonObject result = new ShowAssetTool(daos)
			.execute(new JsonObject().put("assetId", ASSET_UUID.toString()).put("startMs", -12_000)).result();

		assertNull(onlyVisual(result).getJsonObject("payload").getValue("startSeconds"));
	}

	@Test
	public void testCarriesACaptionButNotAWholeAnswer() {
		catalogHolds("harbour.jpg", "image/jpeg");
		String essay = "x".repeat(ShowAssetTool.MAX_CAPTION_CHARS + 50);

		JsonObject payload = onlyVisual(new ShowAssetTool(daos)
			.execute(new JsonObject().put("assetId", ASSET_UUID.toString()).put("caption", essay)).result())
				.getJsonObject("payload");

		assertEquals("image", payload.getString("kind"));
		assertTrue(payload.getString("caption").length() <= ShowAssetTool.MAX_CAPTION_CHARS + 1,
			"A caption is a line under a player; an unbounded one is a model writing its answer into the card");
	}

	/**
	 * A PDF has no preview here, and the difference between "the viewer shows details" and "the viewer shows the document" is one the model has to be
	 * able to state — it is the only thing standing between the user and being told to look at something that is not there.
	 */
	@Test
	public void testSaysSoWhenThereIsNothingToRender() {
		catalogHolds("contract.pdf", "application/pdf");

		JsonObject result = new ShowAssetTool(daos).execute(new JsonObject().put("assetId", ASSET_UUID.toString())).result();

		assertEquals("document", onlyVisual(result).getJsonObject("payload").getString("kind"));
		assertTrue(text(result).contains("not a media file"));
	}

	@Test
	public void testAnUnknownAssetIsAnAnswerNotAFailure() {
		when(assetDao.loadById(any())).thenReturn(null);

		JsonObject result = new ShowAssetTool(daos).execute(new JsonObject().put("assetId", ASSET_UUID.toString())).result();

		assertNull(result.getJsonArray("visuals"), "Nothing to show means no viewer, not an empty one");
		assertTrue(text(result).contains("not found"));
	}

	@Test
	public void testAMissingAssetIdIsRejected() {
		assertTrue(new ShowAssetTool(daos).execute(new JsonObject()).failed());
		assertFalse(new ShowAssetTool(daos).execute(new JsonObject().put("assetId", "  ")).succeeded());
	}

}
