package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.tables.JooqAnnotationAsset.ANNOTATION_ASSET;
import static io.metaloom.loom.db.jooq.tables.JooqPersonImage.PERSON_IMAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.api.embedding.EmbeddingType;
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
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.detection.Detection;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.model.person.Person;
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
 * The tail of the class pins the tables that do <b>not</b> cascade yet — {@code collection_asset}, {@code tag_asset} and {@code asset_task} are still
 * plain foreign keys, so an asset that is linked there cannot be deleted at all. These block-on-delete tests document the current state until the
 * §2.6 cleanup decides whether those links should cascade, detach or block.
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
		Embedding embedding = embeddingDao().createEmbedding(userUuid, assetUuid, VECTOR_DATA, EmbeddingType.DLIB_FACE_RESNET_v1);
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
	 * {@code collection_asset} is still a plain FK, so an asset that is a member of a collection cannot be deleted - the delete raises a foreign-key
	 * violation. Pins the current state until §2.6 decides the collection-membership delete behaviour.
	 */
	@Test
	public void testCollectionMembershipBlocksAssetDelete() {
		User user = adminUser();
		Asset asset = storeAsset(user, 3);

		Collection collection = collectionDao().load(COLLECTION_UUID);
		collectionDao().linkAsset(collection.getUuid(), asset.getUuid());

		assertThrows(DataAccessException.class, () -> assetDao().delete(asset.getUuid()),
			"collection_asset does not cascade, so the delete must be rejected");
		assertNotNull(assetDao().load(asset.getUuid()), "The rejected delete must leave the asset in place");
	}

	/**
	 * {@code tag_asset} is still a plain FK, so a tagged asset cannot be deleted. Pins the current state.
	 */
	@Test
	public void testTagLinkBlocksAssetDelete() {
		User user = adminUser();
		Asset asset = storeAsset(user, 4);

		AssetTag tag = tagDao().createAssetTag(user, "cascade-tag", "colors");
		tagDao().store(tag);
		tagDao().tagAsset(tag, asset);

		assertThrows(DataAccessException.class, () -> assetDao().delete(asset.getUuid()),
			"tag_asset does not cascade, so the delete must be rejected");
		assertNotNull(assetDao().load(asset.getUuid()), "The rejected delete must leave the asset in place");
	}

	/**
	 * {@code asset_task} is still a plain FK, so an asset with an assigned task cannot be deleted. Pins the current state.
	 */
	@Test
	public void testAssignedTaskBlocksAssetDelete() {
		User user = adminUser();
		Asset asset = storeAsset(user, 5);

		Task task = taskDao().createTask(user, "asset_cascade_task");
		taskDao().store(task);
		taskDao().assignToAsset(task.getUuid(), asset.getUuid());

		assertThrows(DataAccessException.class, () -> assetDao().delete(asset.getUuid()),
			"asset_task does not cascade, so the delete must be rejected");
		assertNotNull(assetDao().load(asset.getUuid()), "The rejected delete must leave the asset in place");
	}
}
