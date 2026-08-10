package io.metaloom.loom.db.storage;

/**
 * The buckets the storage report groups stored bytes into.
 *
 * <p>
 * Mostly one per {@code AttachmentType}, with two deliberate exceptions:
 * </p>
 *
 * <ul>
 * <li>{@link #PERSON_AVATAR} is not a type. It is a {@code PERSON_IMAGE} that the person's {@code avatar_attachment_uuid} points at, split out
 * because "how much of this is gallery and how much is the one picture we actually render" is a question an operator asks.</li>
 * <li>{@link #ASSET_BINARY} is not an attachment at all - it is {@code asset_location}, the original uploaded media. It belongs here because the
 * report exists to answer "where did my disk go", and leaving out the largest consumer would make every other number look like the whole story.</li>
 * </ul>
 *
 * <p>
 * Carried over the wire as a plain string rather than an enum, so a client written against today's vocabulary sees a future category as an unknown
 * row rather than failing to deserialise.
 * </p>
 */
public enum StorageCategory {

	ASSET_BINARY,

	ASSET_THUMBNAIL,

	EMBEDDING_ATTACHMENT,

	FACE_CROP,

	PERSON_IMAGE,

	PERSON_AVATAR,

	USER_AVATAR;

}
