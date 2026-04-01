package io.metaloom.loom.rest.model.example;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.rest.model.annotation.AnnotationResponse;
import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.model.asset.info.GeoLocationInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.rest.model.asset.location.social.SocialInfo;
import io.metaloom.loom.rest.model.comment.CommentResponse;
import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;
import io.metaloom.loom.rest.model.common.PagingInfo;
import io.metaloom.loom.rest.model.tag.TagReference;
import io.metaloom.loom.rest.model.task.TaskResponse;
import io.metaloom.loom.rest.model.user.UserReference;
import io.metaloom.utils.UUIDUtils;
import io.metaloom.utils.hash.MD5;
import io.metaloom.utils.hash.SHA256;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

public interface ExampleValues {

	public static final String IMAGE_MIMETYPE = "image/jpeg";

	public static final String AUDIO_FINGERPRINT = "0002000100ffdfffdfdfdfffdfdf9ffd9fff9f193f007800780078807810b806e83e8718017d";

	public static final String VIDEO_FINGERPRINT = "0002000100ffdfffdfdfdfffdfdf9ffd9fff9f193f007800780078807810b806e83e8718017d";

	public static final Instant DATE_OLD = Instant.parse("2018-10-12T14:15:06.024Z");

	public static final Instant DATE_NEW = Instant.parse("2018-11-20T20:12:01.084Z");

	public static final Float[] VECTOR_DATA = { 0.42f, 0.24f, 0.44f, 21.5f };

	default UUID uuidA() {
		return UUIDUtils.fromString("f04e89d0-076d-4d90-b192-715a25a2cd59");
	}

	default UUID uuidB() {
		return UUIDUtils.fromString("86abc160-4da2-4951-a91f-da0c33fbc634");
	}

	default UUID uuidC() {
		return UUIDUtils.fromString("0f3332a6-e404-4777-88a9-1fa984a311bc");
	}

	default UserReference userReferenceA() {
		UserReference reference = new UserReference();
		reference.setUuid(uuidA());
		reference.setName("joedoe");
		return reference;
	}

	default PagingInfo pagingInfo() {
		PagingInfo info = new PagingInfo();
		info.setTotalCount(28);
		info.setPerPage(2L);
		return info;
	}

	default void setCreatorEditor(AbstractCreatorEditorRestResponse<?> model) {
		model.getStatus().setCreator(userReferenceA());
		model.getStatus().setCreated(DATE_OLD);
		model.getStatus().setEditor(userReferenceA());
		model.getStatus().setEdited(DATE_NEW);
	}

	default TagReference tagReferenceA() {
		TagReference model = new TagReference();
		model.setUuid(uuidA());
		model.setName("red");
		return model;
	}

	default TagReference tagReferenceB() {
		TagReference model = new TagReference();
		model.setUuid(uuidB());
		model.setName("blue");
		return model;
	}

	default JsonObject meta() {
		JsonObject meta = new JsonObject();
		meta.put("abc", "cdef");
		return meta;
	}

	default SHA512 sha512sum() {
		return SHA512.fromString(
			"0e3e75234abc68f4378a86b3f4b32a198ba301845b0cd6e50106e874345700cc6663a86c1ea125dc5e92be17c98f9a0f85ca9d5f595db2012f7cc3571945c123");
	}

	default HashInfo assetHashes() {
		HashInfo hashes = new HashInfo();
		hashes.setSHA512(sha512sum());
		hashes.setSHA256(SHA256.fromString("f2ca1bb6c7e907d06dafe4687e579fce76b37e4e93b7605022da52e6ccc26fd2"));
		hashes.setMD5(MD5.fromString("d8e8fca2dc0f896fd7cb4cb0031ba249"));
		return hashes;
	}

	default GeoLocationInfo assetGeoLocation() {
		GeoLocationInfo location = new GeoLocationInfo();
		location.setLat(52.156);
		location.setLon(32.56);
		return location;
	}

	default TaskResponse task() {
		TaskResponse task = new TaskResponse();
		task.setUuid(uuidC());
		task.setTitle("Fix text offset here");
		task.setDescription("The text is not aligned with the title");
		task.getComments().add(comment());
		return task;
	}

	private static SocialInfo social() {
		SocialInfo social = new SocialInfo();
		social.getRating().setDownVotes(0).setUpVotes(42).setStars(10);
		social.getReactions().put(ReactionType.SATISFIED, 10L);
		return social;
	}

	default CommentResponse comment() {
		CommentResponse comment = new CommentResponse();
		comment.setUuid(uuidB());
		comment.setTitle("Great work!");
		comment.setSocial(social());
		comment.setText("What a great choice of colors!");
		return comment;
	}

	default List<AnnotationResponse> assetAnnotations() {
		List<AnnotationResponse> list = new ArrayList<>();

		AnnotationResponse first = new AnnotationResponse();
		first.setTitle("Intro Feedback");
		first.setArea(new AreaInfo().setFrom(0L).setTo(10L));
		first.setDescription("The very nice intro");
		first.setThumbnail("???");
		first.setMeta(meta());
		first.getTasks().add(task());
		first.getComments().add(comment());
		first.getTags().add(tagReferenceB());
		list.add(first);

		// Annotation second = new Annotation();
		// second.setTitle("Main Feedback");
		// second.setArea(new Area().setFrom(11).setTo(200));
		// second.setDescription("The main part of the movie");
		// second.setThumbnail("???");
		// second.setTags(tagReferenceList());
		// second.getTasks().add(task());
		// second.getComments().add(comment());
		// second.setMeta(meta());
		// list.add(second);
		return list;
	}

}
