package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.tables.JooqAnnotationAsset.ANNOTATION_ASSET;
import static io.metaloom.loom.db.jooq.tables.JooqAssetTask.ASSET_TASK;
import static io.metaloom.loom.db.jooq.tables.JooqAssetUserMeta.ASSET_USER_META;
import static io.metaloom.loom.db.jooq.tables.JooqCollectionAsset.COLLECTION_ASSET;
import static io.metaloom.loom.db.jooq.tables.JooqLibraryAsset.LIBRARY_ASSET;
import static io.metaloom.loom.db.jooq.tables.JooqPersonImage.PERSON_IMAGE;
import static io.metaloom.loom.db.jooq.tables.JooqTagAsset.TAG_ASSET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.annotation.Annotation;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetAudioComp;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDocComp;
import io.metaloom.loom.db.model.asset.AssetFingerprintComp;
import io.metaloom.loom.db.model.asset.AssetGeoComp;
import io.metaloom.loom.db.model.asset.AssetImageComp;
import io.metaloom.loom.db.model.asset.AssetJsonComp;
import io.metaloom.loom.db.model.asset.AssetLocation;
import io.metaloom.loom.db.model.asset.AssetNodeResult;
import io.metaloom.loom.db.model.asset.AssetSegmentComp;
import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.metaloom.loom.db.model.asset.AssetVideoComp;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.blacklist.Blacklist;
import io.metaloom.loom.db.model.cluster.Cluster;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.comment.Comment;
import io.metaloom.loom.db.model.detection.Detection;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.model.person.Person;
import io.metaloom.loom.db.model.reaction.Reaction;
import io.metaloom.loom.db.model.tag.AssetTag;
import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * Delete-cascade behaviour of the asset hub.
 *
 * <p>
 * The asset is the centre of the schema and the V2.38–V2.48 rework put {@code ON DELETE CASCADE} on the asset foreign key of every table that a node
 * writes per asset. This test deletes an asset that carries one row in each of those tables and asserts that all of them are removed, while a second,
 * untouched asset keeps its identical set of rows. Two of the cascades — {@code detection} (V2.43, the cascade V2.27 had omitted) and
 * {@code attachment} (V2.44, previously a plain foreign key) — were regressions fixed by exactly the kind of FK recreation that a future migration
 * could get wrong again, which is what makes pinning them worthwhile.
 * </p>
 *
 * <p>
 * The tail of the class covers everything an asset is <em>linked</em> to, where the interesting part of a cascade is what <b>survives</b> it. Each of
 * these foreign keys used to block the delete outright and was settled in turn — {@code tag_asset} in V2.72, {@code collection_asset} /
 * {@code asset_task} / {@code asset_user_meta} in V2.73, {@code comment} / {@code reaction} / {@code library_asset} in V2.74 — with the same answer:
 * what is said <em>about</em> the asset goes with it, and the shared object on the other end (the tag, collection, task, library, user) stays, together
 * with every other asset linked to it.
 * </p>
 *
 * <p>
 * Those tests share one fixture, {@link #linkedPair}: two assets wired into the same tag, collection, task and library, each carrying its own user
 * meta, comment (with a reply and a reaction on that comment) and reaction — plus a comment and a reaction on the <b>task</b>, which are social content
 * that no asset owns. Every test deletes the first asset, asserts the specific thing it is about, and then calls
 * {@link #assertOnlyTheVictimsLinksAreGone} to assert the other half: the second asset still has all nine of its links, the shared objects are all
 * there, and the task's own comment and reaction were not caught in the blast. Without that second asset "the tag survived" would also be true of a
 * delete that did nothing at all.
 * </p>
 *
 * <p>
 * After V2.74 the only foreign keys to {@code asset} that are not {@code CASCADE} are the two deliberate {@code SET NULL}s —
 * {@code dedup_group.keep_asset_uuid} and {@code person.primary_image_uuid}.
 * </p>
 *
 * <p>
 * Everything is created through the DAOs (not raw SQL) so the test also exercises the DAO-level FK wiring. The two link tables that have no DAO
 * writer — {@code annotation_asset} and {@code person_image} — are inserted directly with jOOQ, mirroring {@code AnnotationDaoTest} and
 * {@code PersonDaoTest}. {@code asset_remix} also cascades (V2.8) but has no DAO yet, so it is intentionally left out until those operations exist.
 * </p>
 */
public class AssetCascadeTest extends AbstractJooqTest {

	/**
	 * The set of dependent rows attached to a single asset, keyed by their primary key so removal can be asserted precisely.
	 */
	private static class Dependents {
		UUID location;
		UUID geo;
		UUID doc;
		UUID image;
		UUID video;
		UUID audio;
		UUID transcript;
		UUID fingerprint;
		UUID segment;
		UUID json;
		UUID nodeResult;
		UUID embedding;
		UUID detection;
		UUID attachment;
		UUID blacklist;
		UUID annotation;
		UUID person;
		UUID cluster;
	}

	private AssetComponentDao comp() {
		return assetComponentDao();
	}

	/**
	 * Derive a unique SHA-512 from the index so each asset gets its own primary key and natural key.
	 */
	private SHA512 uniqueSha(int i) {
		return SHA512.fromString(SHA512SUM.toString().substring(0, 124) + String.format("%04x", i));
	}

	private Asset storeAsset(User user, int i) {
		Asset asset = assetDao().createAsset(user.getUuid(), uniqueSha(i), IMAGE_MIMETYPE, DUMMY_IMAGE_FILENAME, DUMMY_IMAGE_ORIGIN, 42L);
		assetDao().store(asset);
		return asset;
	}

	/**
	 * Attach exactly one row of every cascading dependent to the given asset. The {@code seed} keeps rows whose natural key is not scoped by the asset
	 * (the location path, the person alias) unique across assets.
	 */
	private Dependents attach(UUID assetUuid, User user, String seed) {
		UUID userUuid = user.getUuid();
		Dependents d = new Dependents();

		// asset_location (V2.10)
		AssetLocation location = assetLocationDao().createAssetLocation("/pool/" + seed + ".jpg", assetUuid, ADMIN_UUID, LIBRARY_UUID);
		assetLocationDao().store(location);
		d.location = location.getUuid();

		// The nine typed components (V2.38–V2.42)
		AssetGeoComp geo = comp().createGeoComp(userUuid, assetUuid, "tika").setMethod("gps-track").setTimeFrom(0L).setGeoLat(48.2).setGeoLon(16.3);
		comp().storeGeoComp(geo);
		d.geo = geo.getUuid();

		AssetDocComp doc = comp().createDocComp(userUuid, assetUuid, "tika").setPageNumber(0).setPageCount(1);
		comp().storeDocComp(doc);
		d.doc = doc.getUuid();

		AssetImageComp image = comp().createImageComp(userUuid, assetUuid, "tika").setStreamIndex(0).setMediaWidth(600).setMediaHeight(600);
		comp().storeImageComp(image);
		d.image = image.getUuid();

		AssetVideoComp video = comp().createVideoComp(userUuid, assetUuid, "tika").setStreamIndex(0).setMediaWidth(1920).setMediaHeight(1080);
		comp().storeVideoComp(video);
		d.video = video.getUuid();

		AssetAudioComp audio = comp().createAudioComp(userUuid, assetUuid, "tika").setStreamIndex(0).setLang("de").setAudioChannels(2);
		comp().storeAudioComp(audio);
		d.audio = audio.getUuid();

		AssetTranscriptComp transcript = comp().createTranscriptComp(userUuid, assetUuid, "whisper").setStreamIndex(0).setLang("de")
			.setTranscriptText("Hallo Welt");
		comp().storeTranscriptComp(transcript);
		d.transcript = transcript.getUuid();

		AssetFingerprintComp fingerprint = comp().createFingerprintComp(userUuid, assetUuid, "fingerprint").setAlgorithm("metaloom-v1")
			.setSectorIndex(0).setTimeFrom(0L).setTimeTo(1000L).setFingerprint("fp-0");
		comp().storeFingerprintComp(fingerprint);
		d.fingerprint = fingerprint.getUuid();

		AssetSegmentComp segment = comp().createSegmentComp(userUuid, assetUuid, "scene-detection").setSegmentType("SCENE").setSeq(0).setTimeFrom(0)
			.setTimeTo(1000);
		comp().storeSegmentComp(segment);
		d.segment = segment.getUuid();

		AssetJsonComp json = comp().createJsonComp(userUuid, assetUuid, "yolo-detector").setSchemaType("yolo-detection");
		json.setData(new JsonObject().put("detections", 3));
		comp().storeJsonComp(json);
		d.json = json.getUuid();

		// asset_node_result (V2.45)
		AssetNodeResult nodeResult = assetNodeResultDao().createNodeResult(userUuid, assetUuid, "whisper", "whisper-1");
		nodeResult.setState(AssetNodeResult.STATE_SUCCESS);
		assetNodeResultDao().upsert(nodeResult);
		d.nodeResult = nodeResult.getUuid();

		// embedding (V2.43)
		Embedding embedding = embeddingDao().createEmbedding(userUuid, assetUuid, VECTOR_DATA, EmbeddingType.DLIB_FACE_RESNET_v1.name());
		embedding.setNodeKind("facedetect");
		embedding.setProducerVersion("dlib-v1");
		embedding.setSubjectIndex(0);
		embeddingDao().store(embedding);
		d.embedding = embedding.getUuid();

		// detection (V2.43 — restores the cascade V2.27 had dropped)
		Detection detection = detectionDao().createDetection(user, "facedetection");
		detection.setAssetUuid(assetUuid);
		detection.setNodeKind("facedetect");
		detectionDao().store(detection);
		d.detection = detection.getUuid();

		// attachment (V2.44 — was a plain FK before)
		Attachment attachment = attachmentDao().createAttachment(userUuid, SHA512SUM, DUMMY_IMAGE_FILENAME, 42L, IMAGE_MIMETYPE,
			AttachmentType.ASSET_THUMBNAIL);
		attachment.setAssetUuid(assetUuid);
		attachmentDao().store(attachment);
		d.attachment = attachment.getUuid();

		// blacklist (V2.14)
		Blacklist blacklist = blacklistDao().createBlacklist(userUuid, assetUuid, "verdict-" + seed);
		blacklistDao().store(blacklist);
		d.blacklist = blacklist.getUuid();

		// annotation + annotation_asset link (V2.16)
		Annotation annotation = annotationDao().createAnnotation(userUuid, assetUuid, "annotation", AnnotationType.FEEDBACK);
		annotationDao().store(annotation);
		d.annotation = annotation.getUuid();
		// annotation_asset has no DAO writer, so the link is inserted directly.
		context.ctx().insertInto(ANNOTATION_ASSET, ANNOTATION_ASSET.ANNOTATION_UUID, ANNOTATION_ASSET.ASSET_UUID)
			.values(annotation.getUuid(), assetUuid)
			.execute();

		// person_image gallery row (V2.26). The person itself is a shared resource and must survive the asset delete.
		Person person = personDao().createPerson(user, "person-" + seed);
		personDao().store(person);
		d.person = person.getUuid();
		context.ctx().insertInto(PERSON_IMAGE, PERSON_IMAGE.PERSON_UUID, PERSON_IMAGE.ASSET_UUID)
			.values(person.getUuid(), assetUuid)
			.execute();

		// cluster (V2.79). A per-asset face cluster describes that asset and is meaningless without it. Confirmed to the person above, to prove the
		// cascade fires even when a reviewer has already decided on it - the asset is what it depends on, not the verdict.
		Cluster cluster = clusterDao().createMachineCluster(Cluster.TYPE_FACE, "facedetect", assetUuid, 0);
		clusterDao().upsertCluster(cluster);
		clusterDao().updateStatus(cluster.getUuid(), Cluster.STATUS_CONFIRMED, person.getUuid(), userUuid);
		d.cluster = cluster.getUuid();

		return d;
	}

	private int countAnnotationAsset(UUID annotationUuid) {
		return context.ctx().fetchCount(ANNOTATION_ASSET, ANNOTATION_ASSET.ANNOTATION_UUID.eq(annotationUuid));
	}

	private int countPersonImage(UUID personUuid) {
		return context.ctx().fetchCount(PERSON_IMAGE, PERSON_IMAGE.PERSON_UUID.eq(personUuid));
	}

	private void assertGone(Dependents d) {
		assertNull(assetLocationDao().load(d.location), "asset_location must cascade with the asset");
		assertNull(comp().loadGeoComp(d.geo), "asset_geo_comp must cascade with the asset");
		assertNull(comp().loadDocComp(d.doc), "asset_doc_comp must cascade with the asset");
		assertNull(comp().loadImageComp(d.image), "asset_image_comp must cascade with the asset");
		assertNull(comp().loadVideoComp(d.video), "asset_video_comp must cascade with the asset");
		assertNull(comp().loadAudioComp(d.audio), "asset_audio_comp must cascade with the asset");
		assertNull(comp().loadTranscriptComp(d.transcript), "asset_transcript_comp must cascade with the asset");
		assertNull(comp().loadFingerprintComp(d.fingerprint), "asset_fingerprint_comp must cascade with the asset");
		assertNull(comp().loadSegmentComp(d.segment), "asset_segment_comp must cascade with the asset");
		assertNull(comp().loadJsonComp(d.json), "asset_json_comp must cascade with the asset");
		assertNull(assetNodeResultDao().load(d.nodeResult), "asset_node_result must cascade with the asset");
		assertNull(embeddingDao().load(d.embedding), "embedding must cascade with the asset");
		assertNull(detectionDao().load(d.detection), "detection must cascade with the asset (V2.43 restored this)");
		assertNull(attachmentDao().load(d.attachment), "attachment must cascade with the asset (V2.44 restored this)");
		assertNull(blacklistDao().load(d.blacklist), "blacklist must cascade with the asset");
		assertNull(annotationDao().load(d.annotation), "annotation must cascade with the asset");
		assertEquals(0, countAnnotationAsset(d.annotation), "annotation_asset link must cascade with the asset");
		assertEquals(0, countPersonImage(d.person), "person_image gallery row must cascade with the asset");
		assertNull(clusterDao().load(d.cluster), "cluster must cascade with the asset (V2.79)");
		assertNotNull(personDao().load(d.person), "the person is a shared resource and must survive its asset");
	}

	private void assertPresent(Dependents d) {
		assertNotNull(assetLocationDao().load(d.location), "asset_location of the other asset must survive");
		assertNotNull(comp().loadGeoComp(d.geo), "asset_geo_comp of the other asset must survive");
		assertNotNull(comp().loadDocComp(d.doc), "asset_doc_comp of the other asset must survive");
		assertNotNull(comp().loadImageComp(d.image), "asset_image_comp of the other asset must survive");
		assertNotNull(comp().loadVideoComp(d.video), "asset_video_comp of the other asset must survive");
		assertNotNull(comp().loadAudioComp(d.audio), "asset_audio_comp of the other asset must survive");
		assertNotNull(comp().loadTranscriptComp(d.transcript), "asset_transcript_comp of the other asset must survive");
		assertNotNull(comp().loadFingerprintComp(d.fingerprint), "asset_fingerprint_comp of the other asset must survive");
		assertNotNull(comp().loadSegmentComp(d.segment), "asset_segment_comp of the other asset must survive");
		assertNotNull(comp().loadJsonComp(d.json), "asset_json_comp of the other asset must survive");
		assertNotNull(assetNodeResultDao().load(d.nodeResult), "asset_node_result of the other asset must survive");
		assertNotNull(embeddingDao().load(d.embedding), "embedding of the other asset must survive");
		assertNotNull(detectionDao().load(d.detection), "detection of the other asset must survive");
		assertNotNull(attachmentDao().load(d.attachment), "attachment of the other asset must survive");
		assertNotNull(blacklistDao().load(d.blacklist), "blacklist of the other asset must survive");
		assertNotNull(annotationDao().load(d.annotation), "annotation of the other asset must survive");
		assertEquals(1, countAnnotationAsset(d.annotation), "annotation_asset link of the other asset must survive");
		assertEquals(1, countPersonImage(d.person), "person_image gallery row of the other asset must survive");
		assertNotNull(clusterDao().load(d.cluster), "cluster of the other asset must survive");
	}

	// ---------------------------------------------------------------------------------------------
	// Link fixture: two assets wired into the same shared objects.
	//
	// Every test below deletes the first asset and then asserts *both* halves of the contract - the
	// victim's own links are gone, and nothing else is: the shared objects survive, the second
	// asset keeps every link of its own, and social content anchored somewhere other than an asset
	// is untouched. Without the second asset "the tag survived" would also be true of a delete that
	// did nothing at all.
	// ---------------------------------------------------------------------------------------------

	/**
	 * Objects that are linked to an asset but not owned by one. Deleting an asset may never remove any of these.
	 */
	private static class Shared {
		AssetTag tag;
		UUID collection;
		UUID task;
		UUID library;
		/** A comment and a reaction on the <b>task</b> - social content that is not about any asset. */
		UUID taskComment;
		UUID taskReaction;
	}

	/**
	 * One asset's links into a {@link Shared} graph, plus the social content written about that asset.
	 */
	private static class Links {
		UUID asset;
		UUID comment;
		UUID reply;
		UUID commentReaction;
		UUID reaction;
		/** A workflow star rating - the same table as {@link #reaction}, but a decision a person made rather than an emoji. */
		UUID rating;
	}

	private static class Fixture {
		Shared shared;
		Asset victim;
		Links victimLinks;
		Asset bystander;
		Links bystanderLinks;
	}

	private Shared shared(User user, String seed) {
		Shared s = new Shared();

		s.tag = tagDao().createAssetTag(user, "shared-tag-" + seed, "colors");
		tagDao().resolveOrCreateAssetTag(s.tag);
		s.collection = COLLECTION_UUID;
		s.library = LIBRARY_UUID;

		Task task = taskDao().createTask(user, "shared-task-" + seed);
		taskDao().store(task);
		s.task = task.getUuid();

		Comment taskComment = commentDao().createCommentForTask(user.getUuid(), s.task, "task-comment-" + seed, "about the task");
		commentDao().store(taskComment);
		s.taskComment = taskComment.getUuid();

		Reaction taskReaction = reactionDao().createReaction(user, ReactionType.THUMBSUP.name());
		taskReaction.setTaskUuid(s.task);
		reactionDao().store(taskReaction);
		s.taskReaction = taskReaction.getUuid();

		return s;
	}

	/**
	 * Link one asset into every shared object, and write a comment (with a reply and a reaction on it) plus a reaction onto the asset itself.
	 */
	private Links link(Shared s, Asset asset, User user, String seed) {
		Links l = new Links();
		l.asset = asset.getUuid();

		tagDao().tagAsset(s.tag, asset);
		collectionDao().linkAsset(s.collection, asset.getUuid());
		taskDao().assignToAsset(s.task, asset.getUuid());

		// library_asset and asset_user_meta have no DAO writer, so both rows are inserted directly.
		context.ctx().insertInto(LIBRARY_ASSET, LIBRARY_ASSET.LIBRARY_UUID, LIBRARY_ASSET.ASSET_UUID)
			.values(s.library, asset.getUuid())
			.execute();
		context.ctx().insertInto(ASSET_USER_META, ASSET_USER_META.ASSET_UUID, ASSET_USER_META.USER_UUID, ASSET_USER_META.META)
			.values(asset.getUuid(), user.getUuid(), new JsonObject().put("rating", 5))
			.execute();

		Comment comment = commentDao().createComment(user.getUuid(), asset.getUuid(), "comment-" + seed, "about the asset");
		commentDao().store(comment);
		l.comment = comment.getUuid();

		Comment reply = commentDao().createComment(user.getUuid(), asset.getUuid(), "reply-" + seed, "re: about the asset");
		reply.setParentUuid(l.comment);
		commentDao().store(reply);
		l.reply = reply.getUuid();

		Reaction commentReaction = reactionDao().createReaction(user, ReactionType.THUMBSUP.name());
		commentReaction.setCommentUuid(l.comment);
		reactionDao().store(commentReaction);
		l.commentReaction = commentReaction.getUuid();

		Reaction reaction = reactionDao().createReaction(user, ReactionType.SATISFIED.name());
		reaction.setAssetUuid(asset.getUuid());
		reactionDao().store(reaction);
		l.reaction = reaction.getUuid();

		// A rating by the same user on the same asset. It only fits alongside the reaction above because
		// RATING is its own type - UNIQUE (creator_uuid, type, asset_uuid) would otherwise reject it, which
		// is exactly the collision the dedicated type removed.
		Reaction rating = reactionDao().createReaction(user, ReactionType.RATING.name());
		rating.setAssetUuid(asset.getUuid());
		rating.setRating(9);
		reactionDao().store(rating);
		l.rating = rating.getUuid();

		return l;
	}

	private Fixture linkedPair(User user, int victimIndex, int bystanderIndex, String seed) {
		Fixture f = new Fixture();
		f.shared = shared(user, seed);
		f.victim = storeAsset(user, victimIndex);
		f.bystander = storeAsset(user, bystanderIndex);
		f.victimLinks = link(f.shared, f.victim, user, seed + "-victim");
		f.bystanderLinks = link(f.shared, f.bystander, user, seed + "-bystander");
		return f;
	}

	private int countFor(org.jooq.Table<?> table, org.jooq.TableField<?, UUID> assetField, UUID assetUuid) {
		return context.ctx().fetchCount(table, assetField.eq(assetUuid));
	}

	private void assertLinkRowsGone(Links l) {
		assertEquals(0, countFor(TAG_ASSET, TAG_ASSET.ASSET_UUID, l.asset), "tag_asset must cascade with the asset (V2.72)");
		assertEquals(0, countFor(COLLECTION_ASSET, COLLECTION_ASSET.ASSET_UUID, l.asset), "collection_asset must cascade with the asset (V2.73)");
		assertEquals(0, countFor(ASSET_TASK, ASSET_TASK.ASSET_UUID, l.asset), "asset_task must cascade with the asset (V2.73)");
		assertEquals(0, countFor(ASSET_USER_META, ASSET_USER_META.ASSET_UUID, l.asset), "asset_user_meta must cascade with the asset (V2.73)");
		assertEquals(0, countFor(LIBRARY_ASSET, LIBRARY_ASSET.ASSET_UUID, l.asset), "library_asset must cascade with the asset (V2.74)");
		assertNull(commentDao().load(l.comment), "a comment about the asset must cascade with it (V2.74)");
		assertNull(commentDao().load(l.reply), "the reply subtree goes with the comment it hangs from (V2.35)");
		assertNull(reactionDao().load(l.commentReaction), "a reaction on a cascade-deleted comment goes with it (V2.35)");
		assertNull(reactionDao().load(l.reaction), "a reaction to the asset must cascade with it (V2.74)");
		assertNull(reactionDao().load(l.rating), "a rating of the asset must cascade with it (V2.74)");
	}

	/**
	 * Every link of the untouched asset, except its tag placement - split out so the tag-delete test can reuse the rest.
	 */
	private void assertNonTagLinkRowsPresent(Links l) {
		assertEquals(1, countFor(COLLECTION_ASSET, COLLECTION_ASSET.ASSET_UUID, l.asset), "the other asset must stay in the collection");
		assertEquals(1, countFor(ASSET_TASK, ASSET_TASK.ASSET_UUID, l.asset), "the other asset must stay on the task");
		assertEquals(1, countFor(ASSET_USER_META, ASSET_USER_META.ASSET_UUID, l.asset), "the other asset must keep its per-user meta");
		assertEquals(1, countFor(LIBRARY_ASSET, LIBRARY_ASSET.ASSET_UUID, l.asset), "the other asset must stay in the library");
		assertNotNull(commentDao().load(l.comment), "a comment on the other asset must survive");
		assertNotNull(commentDao().load(l.reply), "a reply on the other asset must survive");
		assertNotNull(reactionDao().load(l.commentReaction), "a reaction on the other asset's comment must survive");
		assertNotNull(reactionDao().load(l.reaction), "a reaction to the other asset must survive");
		assertNotNull(reactionDao().load(l.rating), "a rating of the other asset must survive");
	}

	private void assertLinkRowsPresent(Links l) {
		assertEquals(1, countFor(TAG_ASSET, TAG_ASSET.ASSET_UUID, l.asset), "the other asset must keep its tag placement");
		assertNonTagLinkRowsPresent(l);
	}

	private void assertSharedIntact(Shared s) {
		assertNotNull(tagDao().load(s.tag.getUuid()), "the tag is a shared object and must survive");
		assertNotNull(collectionDao().load(s.collection), "the collection must survive");
		assertNotNull(taskDao().load(s.task), "the task must survive");
		assertNotNull(libraryDao().load(s.library), "the library must survive");
		assertNotNull(commentDao().load(s.taskComment), "a comment on the task is not about any asset and must survive");
		assertNotNull(reactionDao().load(s.taskReaction), "a reaction on the task is not about any asset and must survive");
		assertNotNull(userDao().load(ADMIN_UUID), "the user who wrote all of this must survive");
	}

	/**
	 * The one assertion every link test shares: the victim's links are gone and <b>nothing else is</b>.
	 */
	private void assertOnlyTheVictimsLinksAreGone(Fixture f) {
		assertNull(assetDao().load(f.victim.getUuid()), "the deleted asset is gone");
		assertLinkRowsGone(f.victimLinks);

		assertNotNull(assetDao().load(f.bystander.getUuid()), "the second asset must survive");
		assertLinkRowsPresent(f.bystanderLinks);
		assertSharedIntact(f.shared);
	}

	/**
	 * Deleting an asset removes every dependent row that cascades from it, and leaves an identical set on a second asset untouched.
	 */
	@Test
	public void testDeletingAssetCascadesAllDependents() {
		User user = adminUser();

		Asset asset = storeAsset(user, 1);
		Dependents dependents = attach(asset.getUuid(), user, "primary");

		// A second asset carrying the same set of rows - the negative control.
		Asset other = storeAsset(user, 2);
		Dependents survivors = attach(other.getUuid(), user, "bystander");

		assertNotNull(assetDao().load(asset.getUuid()), "The asset exists before the delete");

		assetDao().delete(asset.getUuid());

		assertNull(assetDao().load(asset.getUuid()), "The asset is gone");
		assertGone(dependents);

		assertNotNull(assetDao().load(other.getUuid()), "The unrelated asset must not be affected");
		assertPresent(survivors);
	}

	/**
	 * Deleting an asset removes it from its collections and leaves the collections themselves alone (V2.73).
	 *
	 * <p>
	 * Membership is not content: the row only says that this asset sits in that collection. Until V2.73 it blocked the delete outright, so an asset
	 * that had been filed anywhere could not be deleted at all.
	 * </p>
	 */
	@Test
	public void testDeletingAssetLeavesTheCollection() {
		User user = adminUser();
		Fixture f = linkedPair(user, 3, 9, "coll");
		Collection collection = collectionDao().load(f.shared.collection);

		assetDao().delete(f.victim.getUuid());

		assertEquals(0, countFor(COLLECTION_ASSET, COLLECTION_ASSET.ASSET_UUID, f.victim.getUuid()),
			"its collection membership must have gone with it");
		assertNotNull(collectionDao().load(collection.getUuid()), "the collection itself must survive");
		assertEquals(1, context.ctx().fetchCount(COLLECTION_ASSET,
			COLLECTION_ASSET.COLLECTION_UUID.eq(collection.getUuid()).and(COLLECTION_ASSET.ASSET_UUID.eq(f.bystander.getUuid()))),
			"...and keep every other asset filed in it");

		assertOnlyTheVictimsLinksAreGone(f);
	}

	/**
	 * Deleting an asset removes the per-user metadata written onto it (V2.73).
	 *
	 * <p>
	 * {@code asset_user_meta} has no DAO writer yet, so the row is inserted directly with jOOQ - the same way {@code annotation_asset} and
	 * {@code person_image} are handled above. The cascade is pinned now so that the table cannot grow a writer and an orphan problem at the same time.
	 * </p>
	 */
	@Test
	public void testDeletingAssetRemovesUserMeta() {
		User user = adminUser();
		Fixture f = linkedPair(user, 10, 18, "meta");

		assetDao().delete(f.victim.getUuid());

		assertEquals(0, countFor(ASSET_USER_META, ASSET_USER_META.ASSET_UUID, f.victim.getUuid()),
			"asset_user_meta must cascade with the asset");
		assertNotNull(userDao().load(user.getUuid()), "the user must survive - only their note on this asset is gone");

		assertOnlyTheVictimsLinksAreGone(f);
	}

	/**
	 * Deleting a tagged asset removes its tag <em>assignments</em> and nothing else (V2.72).
	 *
	 * <p>
	 * A tag assignment is a statement that this tag applies to that asset; with the asset gone the statement is meaningless. The tag is not: it is a
	 * global object which other assets carry, and which a curator may have created before using it. Until V2.72 the join row blocked the delete
	 * outright, so deleting a tagged asset answered 500.
	 * </p>
	 */
	@Test
	public void testDeletingATaggedAssetKeepsTheTag() {
		User user = adminUser();
		Fixture f = linkedPair(user, 4, 7, "tag");

		assetDao().delete(f.victim.getUuid());

		assertEquals(0, countFor(TAG_ASSET, TAG_ASSET.ASSET_UUID, f.victim.getUuid()), "its tag assignments must have gone with it");
		assertNotNull(tagDao().load(f.shared.tag.getUuid()), "the tag itself must survive");
		assertEquals(1, tagDao().assetTags(f.bystander).stream().filter(t -> t.getUuid().equals(f.shared.tag.getUuid())).count(),
			"...and stay attached to every other asset carrying it");

		assertOnlyTheVictimsLinksAreGone(f);
	}

	/**
	 * The mirror image: deleting a tag removes its assignments and leaves both assets - and everything else they are linked to - alone.
	 */
	@Test
	public void testDeletingATagKeepsTheAsset() {
		User user = adminUser();
		Fixture f = linkedPair(user, 8, 19, "tagdel");

		tagDao().delete(f.shared.tag.getUuid());

		assertNull(tagDao().load(f.shared.tag.getUuid()), "the tag is gone");
		assertEquals(0, context.ctx().fetchCount(TAG_ASSET, TAG_ASSET.TAG_UUID.eq(f.shared.tag.getUuid())),
			"its assignments must have gone with it");

		// Only the assignments. Both assets, all their other links and the rest of the shared graph are untouched.
		assertNotNull(assetDao().load(f.victim.getUuid()), "the asset the tag was on must survive");
		assertNotNull(assetDao().load(f.bystander.getUuid()), "so must the second asset");
		assertNonTagLinkRowsPresent(f.victimLinks);
		assertNonTagLinkRowsPresent(f.bystanderLinks);
		assertNotNull(collectionDao().load(f.shared.collection), "the collection must survive a tag delete");
		assertNotNull(taskDao().load(f.shared.task), "the task must survive a tag delete");
		assertNotNull(libraryDao().load(f.shared.library), "the library must survive a tag delete");
		assertNotNull(commentDao().load(f.shared.taskComment), "the comment on the task must survive a tag delete");
		assertNotNull(reactionDao().load(f.shared.taskReaction), "the reaction on the task must survive a tag delete");
	}

	/**
	 * Deleting an asset takes it out of the tasks that referenced it, and the tasks themselves stay (V2.73).
	 *
	 * <p>
	 * A task may be about several assets - {@code asset_task} has been many-to-many since V2.8 - so losing one of them must drop that one link rather
	 * than the task. The task keeps its title, status, comments and reactions, and every other asset it referenced. Until V2.73 an asset with a task on
	 * it could not be deleted at all.
	 * </p>
	 */
	@Test
	public void testDeletingAssetLeavesTheTask() {
		User user = adminUser();
		Fixture f = linkedPair(user, 5, 11, "task");

		assetDao().delete(f.victim.getUuid());

		assertEquals(0, countFor(ASSET_TASK, ASSET_TASK.ASSET_UUID, f.victim.getUuid()), "its task link must have gone with it");
		assertNotNull(taskDao().load(f.shared.task), "the task itself must survive");
		assertEquals(1, context.ctx().fetchCount(ASSET_TASK,
			ASSET_TASK.TASK_UUID.eq(f.shared.task).and(ASSET_TASK.ASSET_UUID.eq(f.bystander.getUuid()))),
			"...and keep referencing the other asset it was about");

		assertOnlyTheVictimsLinksAreGone(f);
	}

	/**
	 * Deleting an asset deletes the comments written about it, and their replies (V2.74).
	 *
	 * <p>
	 * Unlike a membership row this is content somebody wrote, but it is content <em>about the asset</em> - with the asset gone the thread has nothing
	 * left to be about. Replies follow through {@code comment.parent_uuid} and reactions on those comments through {@code reaction.comment_uuid}, both
	 * of which have cascaded since V2.35. What must not move is a comment anchored elsewhere: the one on the task is written by the same user in the
	 * same test and has to survive.
	 * </p>
	 */
	@Test
	public void testDeletingAssetRemovesItsComments() {
		User user = adminUser();
		Fixture f = linkedPair(user, 12, 13, "comment");

		assetDao().delete(f.victim.getUuid());

		assertNull(commentDao().load(f.victimLinks.comment), "the comment about the asset must go with it");
		assertNull(commentDao().load(f.victimLinks.reply), "and so must its reply subtree");
		assertNull(reactionDao().load(f.victimLinks.commentReaction), "and the reactions on that comment");
		assertEquals(0, commentDao().loadForAsset(f.victim.getUuid()).size(), "no comment may be left pointing at the deleted asset");

		assertEquals(2, commentDao().loadForAsset(f.bystander.getUuid()).size(), "the other asset keeps its comment and its reply");
		assertEquals(1, commentDao().loadForTask(f.shared.task).size(), "a comment on the task is about the task, not the asset");

		assertOnlyTheVictimsLinksAreGone(f);
	}

	/**
	 * Deleting an asset deletes the reactions to it (V2.74) - and only those.
	 */
	@Test
	public void testDeletingAssetRemovesItsReactions() {
		User user = adminUser();
		Fixture f = linkedPair(user, 14, 15, "reaction");

		assetDao().delete(f.victim.getUuid());

		assertNull(reactionDao().load(f.victimLinks.reaction), "the reaction to the asset must go with it");
		assertNull(reactionDao().load(f.victimLinks.rating), "the rating of the asset must go with it");
		assertNotNull(reactionDao().load(f.bystanderLinks.reaction), "a reaction to the other asset must survive");
		assertNotNull(reactionDao().load(f.bystanderLinks.rating), "a rating of the other asset must survive");
		assertNotNull(reactionDao().load(f.shared.taskReaction), "a reaction on the task must survive");

		assertOnlyTheVictimsLinksAreGone(f);
	}

	/**
	 * Deleting an asset removes it from its libraries; the library survives with everything else in it (V2.74).
	 *
	 * <p>
	 * {@code library_asset} has no DAO writer yet, so both rows are inserted directly with jOOQ. The direction that stays blocked is the other one:
	 * {@code library_asset.library_uuid} is still a plain reference, so a library cannot be deleted out from under the assets in it.
	 * </p>
	 */
	@Test
	public void testDeletingAssetLeavesTheLibrary() {
		User user = adminUser();
		Fixture f = linkedPair(user, 16, 17, "library");

		assetDao().delete(f.victim.getUuid());

		assertEquals(0, countFor(LIBRARY_ASSET, LIBRARY_ASSET.ASSET_UUID, f.victim.getUuid()), "its library membership must have gone with it");
		assertNotNull(libraryDao().load(f.shared.library), "the library itself must survive");
		assertEquals(1, context.ctx().fetchCount(LIBRARY_ASSET,
			LIBRARY_ASSET.LIBRARY_UUID.eq(f.shared.library).and(LIBRARY_ASSET.ASSET_UUID.eq(f.bystander.getUuid()))),
			"...and keep every other asset in it");

		assertOnlyTheVictimsLinksAreGone(f);
	}
}
