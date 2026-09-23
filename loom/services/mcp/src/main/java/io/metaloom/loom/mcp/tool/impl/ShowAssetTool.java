package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpResult;
import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;
import static io.metaloom.loom.mcp.tool.MCPToolResults.reference;
import static io.metaloom.loom.mcp.tool.MCPToolResults.visual;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: show_asset
 *
 * <p>
 * Puts one asset <em>on screen</em>, as a player or a picture embedded in the conversation, rather than describing it. Everything else in the catalog
 * half of the tool set answers in prose about media the user cannot see; this is the one that hands the media over.
 * </p>
 *
 * <p>
 * <b>The visual is the whole point, and the model never sees it.</b> A tool result's {@code visuals} array is stripped out before the result reaches
 * the model ({@code VisualExtractor}), so this tool is the only one whose value is entirely in the part it cannot read back. The text it returns is
 * therefore a confirmation - "the viewer is open, here is what is in it" - not a substitute: a client that renders nothing still learns what the asset
 * is, and the model learns that showing it worked.
 * </p>
 *
 * <p>
 * <b>Why a tool and not a reference chip.</b> Search results already arrive as {@code references}, which the chat renders as chips - a name and an icon.
 * That answers "which file", never "is this the right one". Deciding that means looking, and looking means a player. Keeping it a separate, explicit
 * call also keeps the decision with the model: a search that returned forty files must not paint forty video players into the transcript.
 * </p>
 *
 * <p>
 * <b>{@code startMs} is what makes this worth calling after a transcript search.</b> {@code search_transcript} answers with the offset a passage was
 * spoken at; passing that here opens the player on the sentence instead of at the top of a 43-minute episode.
 * </p>
 *
 * <p>
 * <b>Milliseconds, because that is the unit the answer it is copied from is written in.</b> The parameter was {@code startSeconds} for exactly one
 * deployment, and the first model to use it in anger passed {@code 900416} - the raw {@code timeFromMs} of a transcript hit, undivided - which opened
 * the viewer ten days into a 43-minute episode while the same answer claimed "15 minutes in". Asking a small model to change units between one tool's
 * output and the next tool's input is asking for that, and no amount of prompt wording fixes an arithmetic step that need not exist. The conversion to
 * the seconds the player wants happens here, where it is a division rather than a hope.
 * </p>
 */
@Singleton
public class ShowAssetTool implements MCPTool {

	public static final String NAME = "show_asset";

	/** The visual type the chat renders as an embedded player or picture. */
	public static final String VISUAL_TYPE = "asset-viewer";

	/**
	 * Longest caption relayed to the card. A caption is a line under a player, and a model handed an open-ended string field will occasionally write its
	 * whole answer into it.
	 */
	static final int MAX_CAPTION_CHARS = 200;

	/**
	 * Largest offset taken seriously: 24 hours.
	 *
	 * <p>
	 * Not a limit on media length - it is a wrongness detector. The number handed here is copied out of another tool's answer, and the way that goes
	 * wrong is by a factor of a thousand. A player opened ten days into a 43-minute episode is not a smaller error than one opened at the start, it is
	 * a stranger one, so an offset past this is dropped and the text says it was.
	 * </p>
	 */
	static final long MAX_START_MS = 24L * 60 * 60 * 1000;

	private final DaoCollection daos;

	@Inject
	public ShowAssetTool(DaoCollection daos) {
		this.daos = daos;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			NAME,
			"Embed a viewer for one asset directly in the chat window, so the user can watch, listen to or look at it without leaving the "
				+ "conversation. Call it whenever the user asks to see, show, play, watch, preview or open an asset, and whenever you have "
				+ "identified the one file an answer is about - a picture is the answer to 'which one is it?', a filename is not. "
				+ "Show one asset per call and only the ones you are actually talking about; do not open a viewer for every search result.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("assetId", "string", "Asset UUID or SHA-512 hash, as returned by the search tools", true),
				new MCPToolParam("startMs", "integer",
					"Where a video or audio viewer should open, in MILLISECONDS from the start. This is exactly the 'timeFromMs' the search "
						+ "tools report for a matching passage - copy it across unchanged, do not convert it. For a position you worked out "
						+ "yourself, multiply the seconds by 1000. Omit to start at the beginning.",
					false),
				new MCPToolParam("caption", "string",
					"One short line shown under the viewer saying why this asset is on screen, e.g. 'the harbour shot you asked about'. Optional.",
					false))),
			List.of("READ_ASSET", "READ_ASSET_BINARY"));
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		try {
			String assetIdStr = arguments.getString("assetId");
			if (assetIdStr == null || assetIdStr.isBlank()) {
				return Future.failedFuture("Parameter 'assetId' is required");
			}

			AssetDao assetDao = daos.assetDao();
			Asset asset = assetDao.loadById(AssetId.assetId(assetIdStr));
			if (asset == null) {
				// An answer, not a failure: the model handed over an id it read somewhere and can correct itself.
				return Future.succeededFuture(mcpTextResult("Asset not found, nothing to show: " + assetIdStr));
			}

			String uuid = asset.getUuid().toString();
			String filename = asset.getFilename();
			String mimeType = asset.getMimeType();
			String kind = kindOf(mimeType);
			Long startMs = startMs(arguments);
			boolean offsetRefused = startMs != null && startMs > MAX_START_MS;
			Double startSeconds = startMs == null || offsetRefused ? null : startMs / 1000d;
			String caption = caption(arguments);

			JsonObject payload = new JsonObject()
				.put("assetUuid", uuid)
				.put("filename", filename)
				.put("mimeType", mimeType)
				// The client could derive this from the mime type and does not have to: a deployment that
				// stores an unhelpful "application/octet-stream" is better served by one place deciding.
				.put("kind", kind)
				.put("size", asset.getSize());
			if (startSeconds != null) {
				payload.put("startSeconds", startSeconds);
			}
			if (caption != null) {
				payload.put("caption", caption);
			}

			StringBuilder text = new StringBuilder("Showing ").append(filename).append(" (").append(mimeType).append(") in the chat viewer.");
			if (startSeconds != null) {
				text.append(" It opens at ").append(timecode(startSeconds)).append('.');
			} else if (offsetRefused) {
				// Said out loud, because the alternative is the model telling the user the viewer opened
				// somewhere it did not - which is what the undivided-milliseconds bug looked like.
				text.append(" The offset of ").append(startMs)
					.append("ms is longer than any media file, so it was ignored and the viewer opens at the start;")
					.append(" startMs is milliseconds and must not be converted from the search result.");
			}
			if (!"video".equals(kind) && !"image".equals(kind) && !"audio".equals(kind)) {
				// Say so rather than let the model claim a preview that is a filename and a download button.
				text.append(" This is not a media file, so the viewer shows its details and a link rather than a preview.");
			}

			return Future.succeededFuture(mcpResult(
				text.toString(),
				new JsonArray().add(reference("asset", uuid, filename)),
				new JsonArray().add(visual(VISUAL_TYPE, uuid, filename, payload))));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

	/**
	 * What the viewer should build: a player, a picture, or neither.
	 *
	 * <p>
	 * By mime type rather than by extension, because the extension is whatever the uploader's operating system decided.
	 * </p>
	 */
	static String kindOf(String mimeType) {
		if (mimeType == null || mimeType.isBlank()) {
			return "other";
		}
		String mime = mimeType.toLowerCase();
		if (mime.startsWith("video/")) {
			return "video";
		}
		if (mime.startsWith("audio/")) {
			return "audio";
		}
		if (mime.startsWith("image/")) {
			return "image";
		}
		if (mime.startsWith("text/") || mime.startsWith("application/")) {
			return "document";
		}
		return "other";
	}

	/**
	 * The requested offset in milliseconds, or null when there is not a usable number there.
	 *
	 * <p>
	 * A negative or unparseable offset is dropped rather than rejected: the viewer opening at the start is the right answer to a bad number, and a
	 * refusal is not. An implausibly large one is returned as it stands, so the caller can say what it ignored - see {@link #MAX_START_MS}.
	 * </p>
	 *
	 * <p>
	 * A quoted number is accepted too. The schema says {@code integer} and a grammar-constrained server will honour that, but plenty of models emit
	 * {@code "604500"} anyway, and the failure that produces - a player silently opening at 0:00 - is invisible to everyone including the model.
	 * </p>
	 */
	private Long startMs(JsonObject arguments) {
		Object raw = arguments.getValue("startMs");
		double millis;
		if (raw instanceof Number number) {
			millis = number.doubleValue();
		} else if (raw instanceof String text) {
			try {
				millis = Double.parseDouble(text.trim());
			} catch (NumberFormatException e) {
				return null;
			}
		} else {
			return null;
		}
		if (!Double.isFinite(millis) || millis <= 0d) {
			return null;
		}
		return Math.round(millis);
	}

	private String caption(JsonObject arguments) {
		String caption = arguments.getString("caption");
		if (caption == null || caption.isBlank()) {
			return null;
		}
		String trimmed = caption.trim();
		return trimmed.length() <= MAX_CAPTION_CHARS ? trimmed : trimmed.substring(0, MAX_CAPTION_CHARS).trim() + "…";
	}

	private String timecode(double seconds) {
		int total = (int) Math.floor(seconds);
		int h = total / 3600;
		int m = (total % 3600) / 60;
		int s = total % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
	}

}
