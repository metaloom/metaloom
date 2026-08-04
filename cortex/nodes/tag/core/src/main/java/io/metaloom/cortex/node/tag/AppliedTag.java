package io.metaloom.cortex.node.tag;

import java.util.UUID;

import io.vertx.core.json.JsonObject;

/**
 * One tag this node put on an asset, as it is recorded in the {@code tags} component.
 *
 * <p>
 * This record <em>is</em> the node's provenance. {@code tag_asset} carries no {@code node_id},
 * {@code confidence} or timestamp of its own, so the component the node writes is the only evidence
 * that a given tag on a given asset came from this node instance — and therefore the only thing that
 * makes withdrawing one safe. The {@code uuid} is what the withdrawal needs: tags are shared rows, so
 * removing one means untagging by its id, not by its name.
 * </p>
 *
 * <p>
 * Mutable in one respect only: the uuid is filled in after the tag is attached, because that is when
 * Loom resolves which shared row the name landed on.
 * </p>
 */
public final class AppliedTag {

	private final String name;

	private final String collection;

	private final String ruleId;

	private final double confidence;

	private UUID uuid;

	public AppliedTag(String name, String collection, String ruleId, double confidence, UUID uuid) {
		this.name = name;
		this.collection = collection;
		this.ruleId = ruleId;
		this.confidence = confidence;
		this.uuid = uuid;
	}

	public static AppliedTag from(JsonObject json) {
		String rawUuid = json.getString("uuid");
		UUID uuid = null;
		if (rawUuid != null) {
			try {
				uuid = UUID.fromString(rawUuid);
			} catch (IllegalArgumentException e) {
				uuid = null;
			}
		}
		return new AppliedTag(json.getString("tag"), json.getString("collection"), json.getString("ruleId"),
			json.getDouble("confidence", 1.0), uuid);
	}

	public String name() {
		return name;
	}

	public String collection() {
		return collection;
	}

	public String ruleId() {
		return ruleId;
	}

	public double confidence() {
		return confidence;
	}

	public UUID uuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject()
			.put("tag", name)
			.put("collection", collection)
			.put("ruleId", ruleId)
			.put("confidence", confidence);
		if (uuid != null) {
			json.put("uuid", uuid.toString());
		}
		return json;
	}
}
