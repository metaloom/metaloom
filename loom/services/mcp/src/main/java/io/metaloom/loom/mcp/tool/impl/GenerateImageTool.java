package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpResult;
import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;
import static io.metaloom.loom.mcp.tool.MCPToolResults.reference;
import static io.metaloom.loom.mcp.tool.MCPToolResults.visual;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.ImageGenToolOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.mcp.imagegen.ImageEditClient;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.metaloom.loom.rest.service.impl.BinaryStorageResolver;
import io.metaloom.loom.rest.service.impl.ProducedAssetIngestor;
import io.metaloom.loom.storage.BinaryStorage;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Generate or edit an image from the chat window, and file the result as a real asset.
 *
 * <h2>Why this calls the sidecar directly</h2>
 *
 * <p>
 * The obvious implementation — ask the {@code imagegen} Cortex node to do it — cannot work today.
 * A node writes its PNG to {@code metaPath/imagegen_bin/…} on whichever worker ran it, and Loom has
 * no way to fetch those bytes back; {@code ProbeEligibility} enforces exactly that by refusing to
 * probe any node kind with an {@code artifact/*} output port. So this tool calls the sidecar itself
 * and ingests the result through {@link ProducedAssetIngestor}.
 * </p>
 *
 * <h2>Why the answer is an asset rather than an image</h2>
 *
 * <p>
 * MCP tool results carry text; images reach the chat through the {@code visuals} envelope, and
 * {@code VisualExtractor} silently discards any visual over 32 KB — so a base64 PNG in the payload
 * is not an option. Ingesting the result and emitting an {@code asset-viewer} visual solves that and
 * costs nothing new: the UI already renders that visual type for {@code show_asset}, and fetches the
 * bytes over the authenticated binary route. <b>No UI change is needed.</b>
 * </p>
 *
 * <p>
 * It also means a generated image is an ordinary asset — searchable, thumbnailed, and processed by
 * whatever pipelines match it, because the ingest publishes a created event like any upload.
 * </p>
 */
@Singleton
public class GenerateImageTool implements MCPTool {

	private static final Logger log = LoggerFactory.getLogger(GenerateImageTool.class);

	public static final String NAME = "generate_image";

	/** The same visual type {@code show_asset} emits, so the existing renderer handles it. */
	public static final String VISUAL_TYPE = ShowAssetTool.VISUAL_TYPE;

	static final String ORIGIN = "mcp:generate_image";

	private final DaoCollection daos;
	private final ImageEditClient client;
	private final ProducedAssetIngestor ingestor;
	private final BinaryStorageResolver storageResolver;
	private final ImageGenToolOptions options;

	@Inject
	public GenerateImageTool(DaoCollection daos, ImageEditClient client, ProducedAssetIngestor ingestor,
		BinaryStorageResolver storageResolver, LoomOptions loomOptions) {
		this.daos = daos;
		this.client = client;
		this.ingestor = ingestor;
		this.storageResolver = storageResolver;
		this.options = loomOptions.getImageGenTool();
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(NAME,
			"Generate a new image, or edit existing ones, and show the result in the chat. With no assetIds it draws "
				+ "from the prompt alone. With one it edits that image - 'make the hair dark', 'put it on a beach'. With "
				+ "several it combines them into a single picture, so say which element comes from which image "
				+ "('the person from image 1 holding the product from image 2'). Set maskPrompt to name a region and only "
				+ "that region changes, leaving the rest of the picture alone. The result is saved as a new asset in the "
				+ "library, so it can be searched for and referred to later.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("prompt", "string",
					"What to draw, or what to change about the input images. Describe the intended result, not the steps.", true),
				new MCPToolParam("assetIds", "array",
					"Input images, by asset UUID or SHA-512, as returned by the search tools. Order matters: the first is the "
						+ "image being edited and the rest are references drawn from. Omit entirely for text-to-image.",
					false),
				new MCPToolParam("maskPrompt", "string",
					"Confine the edit to one region, named in words - \"the boy's hair\", \"the sky\". Everything outside it is "
						+ "left as it was. Only meaningful when assetIds is given.",
					false),
				new MCPToolParam("width", "integer", "Width in pixels. Text-to-image only; an edit follows its source.", false),
				new MCPToolParam("height", "integer", "Height in pixels. Text-to-image only; an edit follows its source.", false),
				new MCPToolParam("seed", "integer", "Fix the seed to get the same picture again from the same inputs.", false))),
			List.of("READ_ASSET", "READ_ASSET_BINARY", "CREATE_ASSET", "GENERATE_MCP_IMAGE"),
			// It stamps a creator on a new asset, so it cannot run without knowing who is asking.
			true);
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		return Future.failedFuture(NAME + " requires an authenticated caller and cannot be dispatched without one.");
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments, MCPCallerContext ctx) {
		if (!ctx.isAuthenticated()) {
			return Future.failedFuture(NAME + " requires an authenticated caller.");
		}
		try {
			String prompt = arguments.getString("prompt");
			if (prompt == null || prompt.isBlank()) {
				return Future.failedFuture("Parameter 'prompt' is required");
			}

			List<String> assetIds = stringList(arguments.getJsonArray("assetIds"));
			if (assetIds.size() > options.getMaxImages()) {
				// An answer rather than a failure: the model can drop one and try again.
				return Future.succeededFuture(mcpTextResult("Too many input images: " + assetIds.size()
					+ " were given and at most " + options.getMaxImages() + " can be combined in one image."));
			}

			List<Asset> inputs = new ArrayList<>();
			List<byte[]> images = new ArrayList<>();
			for (String assetId : assetIds) {
				Asset asset = daos.assetDao().loadById(AssetId.assetId(assetId));
				if (asset == null) {
					return Future.succeededFuture(mcpTextResult("Asset not found, nothing to generate from: " + assetId));
				}
				if (asset.getMimeType() == null || !asset.getMimeType().startsWith("image/")) {
					return Future.succeededFuture(mcpTextResult("Not an image, so it cannot be used as an input: "
						+ asset.getFilename() + " (" + asset.getMimeType() + ")"));
				}
				byte[] bytes = readBinary(asset);
				if (bytes == null) {
					return Future.succeededFuture(mcpTextResult("The stored file for " + asset.getFilename()
						+ " is missing, so it cannot be used as an input."));
				}
				inputs.add(asset);
				images.add(bytes);
			}

			UUID libraryUuid = resolveLibrary(inputs);
			if (libraryUuid == null) {
				// Said plainly rather than failed: this is a deployment gap the user can act on, and
				// the model repeating the call will not fix it.
				return Future.succeededFuture(mcpTextResult(
					"There is nowhere to file the generated image. A text-to-image request needs a default library, which this "
						+ "deployment has not configured (LOOM_MCP_IMAGEGEN_LIBRARY). Editing an existing image works, because the "
						+ "result is filed alongside its source."));
			}

			String maskPrompt = blankToNull(arguments.getString("maskPrompt"));
			Integer seed = arguments.getInteger("seed");
			byte[] png = client.generate(prompt, images, maskPrompt, arguments.getInteger("width"), arguments.getInteger("height"), seed);

			String filename = filename(prompt, seed);
			Asset asset = ingestor.ingest(ctx.userUuid(), libraryUuid, png, filename, "image/png", ORIGIN);

			log.info("generate_image produced asset {} ({} bytes) from {} input image(s) for user {}",
				asset.getUuid(), png.length, images.size(), ctx.userUuid());

			return Future.succeededFuture(render(asset, prompt, inputs, maskPrompt));
		} catch (Exception e) {
			log.error("generate_image failed", e);
			// Surfaced as text so the model can relay something useful - most failures here are a
			// sidecar that is down or still loading its weights, which is worth saying out loud.
			return Future.succeededFuture(mcpTextResult("The image could not be generated: " + rootMessage(e)));
		}
	}

	private JsonObject render(Asset asset, String prompt, List<Asset> inputs, String maskPrompt) {
		String uuid = asset.getUuid().toString();
		String filename = asset.getFilename();

		StringBuilder text = new StringBuilder();
		if (inputs.isEmpty()) {
			text.append("Generated a new image from the prompt");
		} else if (inputs.size() == 1) {
			text.append("Edited ").append(inputs.get(0).getFilename());
		} else {
			text.append("Combined ").append(inputs.size()).append(" images");
		}
		if (maskPrompt != null) {
			text.append(", changing only ").append(maskPrompt);
		}
		text.append(". It is saved as the asset ").append(filename)
			.append(" (").append(uuid).append(") and is shown in the chat.");

		JsonArray references = new JsonArray().add(reference("asset", uuid, filename));
		for (Asset input : inputs) {
			references.add(reference("asset", input.getUuid().toString(), input.getFilename()));
		}

		// Byte-for-byte the payload ShowAssetTool emits, so the existing renderer draws it.
		JsonObject payload = new JsonObject()
			.put("assetUuid", uuid)
			.put("filename", filename)
			.put("mimeType", "image/png")
			.put("kind", "image")
			.put("size", asset.getSize())
			.put("caption", prompt);

		// The text stands alone: a client that cannot render the visual still has the asset uuid and
		// can say what happened.
		return mcpResult(text.toString(), references, new JsonArray().add(visual(VISUAL_TYPE, uuid, filename, payload)));
	}

	/**
	 * Where the generated image is filed: alongside the first input, or the configured default.
	 *
	 * <p>
	 * Inheriting the input's library is what makes an edit land next to what it was made from, which
	 * is nearly always where someone would look for it.
	 * </p>
	 */
	private UUID resolveLibrary(List<Asset> inputs) {
		if (!inputs.isEmpty()) {
			AssetBinary binary = daos.assetBinaryDao().loadPrimaryByAssetUuid(inputs.get(0).getUuid());
			if (binary != null && binary.getLibraryUuid() != null) {
				return binary.getLibraryUuid();
			}
		}
		String configured = options.getLibraryUuid();
		if (configured == null || configured.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(configured.trim());
		} catch (IllegalArgumentException e) {
			log.warn("LOOM_MCP_IMAGEGEN_LIBRARY is not a valid UUID: {}", configured);
			return null;
		}
	}

	private byte[] readBinary(Asset asset) throws Exception {
		AssetBinary binary = daos.assetBinaryDao().loadPrimaryByAssetUuid(asset.getUuid());
		if (binary == null || binary.getPath() == null) {
			return null;
		}
		BinaryStorage storage = storageResolver.forPool(binary.getPoolUuid());
		String locator = binary.getPath();
		if (!storage.exists(locator)) {
			return null;
		}
		try (InputStream in = storage.read(locator, 0, storage.size(locator))) {
			return in.readAllBytes();
		}
	}

	/** A name that says where the file came from, since nobody chose one. */
	static String filename(String prompt, Integer seed) {
		String slug = prompt.toLowerCase()
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		if (slug.length() > 48) {
			slug = slug.substring(0, 48).replaceAll("-$", "");
		}
		if (slug.isEmpty()) {
			slug = "image";
		}
		return "generated-" + slug + (seed != null ? "-" + seed : "") + ".png";
	}

	private static List<String> stringList(JsonArray array) {
		List<String> values = new ArrayList<>();
		if (array == null) {
			return values;
		}
		for (Object entry : array) {
			if (entry != null && !String.valueOf(entry).isBlank()) {
				values.add(String.valueOf(entry));
			}
		}
		return values;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	/** The innermost message, because the useful sentence is usually at the bottom of the chain. */
	private static String rootMessage(Throwable e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause.getMessage() != null ? cause.getMessage() : cause.toString();
	}
}
