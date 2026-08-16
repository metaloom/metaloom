package io.metaloom.loom.db.model.chat;

import java.util.Set;

import io.vertx.core.json.JsonObject;

/**
 * The keys of the {@code chat.meta} jsonb document, and which of them a client may write.
 *
 * <p>
 * {@code chat.meta} is a mixed document: some of it is client state the UI round-trips (which skills are toggled on for this chat), and some of it is
 * written by the agent loop as it runs. Those two halves have very different trust levels, and this class is where the line between them is drawn so the
 * REST layer and the loop cannot drift apart on it — they live in different modules and neither can see the other's constants.
 * </p>
 *
 * @see #SERVER_OWNED_KEYS
 */
public final class ChatMeta {

	/** Skills the user activated for this chat. Client-writable — this is UI state. */
	public static final String ACTIVE_SKILL_UUIDS = "activeSkillUuids";

	/** The model that served the most recent run. Server-written, but harmless and historically client-writable. */
	public static final String MODEL = "model";

	/** Timestamp of the last terminal error, set by the loop and cleared on the next success. */
	public static final String LAST_ERROR = "lastError";

	/**
	 * The rolling conversation summary: {@code {text, throughMessageIndex, tokens, model}}.
	 *
	 * <p>
	 * <b>Server-owned.</b> The loop replays this text into a later run as a delimited <em>system</em> block — the most trusted position in the prompt — so a
	 * client able to set it could author what the agent believes happened in a conversation that never took place.
	 * </p>
	 */
	public static final String SUMMARY = "summary";

	/**
	 * Correction factor for the loop's token estimator, learned from the token counts the model server reports.
	 *
	 * <p>
	 * <b>Server-owned.</b> It scales every estimate, so a client setting it high enough would evict the whole transcript before each run, and setting it low
	 * enough would defeat eviction and let the request overflow the window.
	 * </p>
	 */
	public static final String TOKEN_CALIBRATION = "tokenCalibration";

	/** Token spend and duration of the most recent run. <b>Server-owned</b> — it is a measurement, and a client-supplied one would be a lie. */
	public static final String LAST_RUN = "lastRun";

	/**
	 * Keys only the agent loop may write.
	 *
	 * <p>
	 * {@code POST /api/v1/chats/:uuid} strips these from an incoming body and carries the stored values forward instead. This is the narrow half of
	 * CHAT_TASKS SEC2 — the broader problem, that {@code chat.messages} is client-writable at all, is still open and tracked there.
	 * </p>
	 */
	public static final Set<String> SERVER_OWNED_KEYS = Set.of(SUMMARY, TOKEN_CALIBRATION, LAST_RUN);

	private ChatMeta() {
	}

	/**
	 * Apply a client-supplied {@code meta} document on top of the stored one, keeping every server-owned key at its stored value.
	 *
	 * <p>
	 * Silently ignoring the stripped keys is deliberate: a UI that round-trips the whole {@code meta} object it was handed by {@code GET} is behaving
	 * correctly and must not start failing, so this is a filter rather than a validation error.
	 * </p>
	 *
	 * @param incoming
	 *            The client-supplied document. Null means "not part of this request" and is returned as null.
	 * @param stored
	 *            The document currently on the row, may be null.
	 * @return The document to persist, or null when {@code incoming} was null.
	 */
	public static JsonObject merge(JsonObject incoming, JsonObject stored) {
		if (incoming == null) {
			return null;
		}
		JsonObject merged = incoming.copy();
		for (String key : SERVER_OWNED_KEYS) {
			merged.remove(key);
			Object value = stored == null ? null : stored.getValue(key);
			if (value != null) {
				merged.put(key, value);
			}
		}
		return merged;
	}
}
