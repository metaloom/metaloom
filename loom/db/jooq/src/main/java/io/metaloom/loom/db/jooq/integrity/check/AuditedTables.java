package io.metaloom.loom.db.jooq.integrity.check;

import java.util.List;

/**
 * Every table carrying both {@code created} and {@code edited}, which is the set the timestamp
 * checks sweep.
 *
 * <p>
 * Hand-written and deliberately explicit. Three tables that do carry {@code created} are absent
 * because they have no {@code edited} column at all and so cannot violate an ordering between the
 * two: {@code tag_asset} (V2.71), {@code pipeline_version} (V2.30) and {@code embedding_cluster}
 * (V2.79). {@code loom} has neither.
 * </p>
 *
 * <p>
 * {@code DbIntegrityChecksTest} asserts this list against the live schema, so a table added with the
 * standard audit block and not added here fails a test rather than quietly going unchecked.
 * </p>
 */
final class AuditedTables {

	static final List<String> ALL = List.of(
		"annotation",
		"asset",
		"asset_audio_comp",
		"asset_doc_comp",
		"asset_fingerprint_comp",
		"asset_geo_comp",
		"asset_image_comp",
		"asset_json_comp",
		"asset_location",
		"asset_node_result",
		"asset_pool",
		"asset_remix",
		"asset_segment_comp",
		"asset_transcript_comp",
		"asset_video_comp",
		"attachment",
		"blacklist",
		"chat",
		"chat_session",
		"cluster",
		"collection",
		"comment",
		"cortex_instance",
		"dedup_group",
		"detection",
		"embedding",
		"group",
		"library",
		"memory_deny_rule",
		"memory_entry",
		"node_descriptor",
		"notification",
		"person",
		"pipeline",
		"pipeline_node_task",
		"pipeline_run",
		"pipeline_run_item",
		"project",
		"reaction",
		"role",
		"skill",
		"skill_version",
		"tag",
		"task",
		"token",
		"user",
		"vector_config");

	/** The ones without a {@code uuid} column, so a finding cannot be named by one. */
	static boolean hasUuid(String table) {
		return !"asset_remix".equals(table);
	}

	private AuditedTables() {
	}
}
