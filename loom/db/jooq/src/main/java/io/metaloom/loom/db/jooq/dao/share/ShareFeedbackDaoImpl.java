package io.metaloom.loom.db.jooq.dao.share;

import static io.metaloom.loom.db.jooq.tables.JooqShareAnnotation.SHARE_ANNOTATION;
import static io.metaloom.loom.db.jooq.tables.JooqShareComment.SHARE_COMMENT;
import static io.metaloom.loom.db.jooq.tables.JooqShareReaction.SHARE_REACTION;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.TableRecord;

import io.metaloom.loom.db.model.share.ShareAnnotation;
import io.metaloom.loom.db.model.share.ShareAnnotationKind;
import io.metaloom.loom.db.model.share.ShareComment;
import io.metaloom.loom.db.model.share.ShareFeedbackDao;
import io.metaloom.loom.db.model.share.ShareReaction;
import io.metaloom.loom.db.model.share.ShareReactionType;

/**
 * jOOQ implementation of {@link ShareFeedbackDao}.
 *
 * <p>
 * Every read is scoped by {@code share_uuid}, including the ones that also take a row uuid. That is not defensive duplication: the guest endpoints
 * address rows by uuid from a request authenticated only by a share slug, so "load this comment" without "...and check it is on my share" would let
 * anyone holding one link read and delete another link's feedback.
 * </p>
 */
@Singleton
public class ShareFeedbackDaoImpl implements ShareFeedbackDao {

	private final DSLContext ctx;

	@Inject
	public ShareFeedbackDaoImpl(DSLContext ctx) {
		this.ctx = ctx;
	}

	@Override
	public String getTypeName() {
		return "ShareFeedback";
	}

	@Override
	public void clear() {
		// Reactions first: they reference comments and annotations, and the cascade would handle it, but relying on a
		// cascade to order a truncation is how a future FK change turns clear() into a constraint violation.
		ctx.deleteFrom(SHARE_REACTION).execute();
		ctx.deleteFrom(SHARE_COMMENT).execute();
		ctx.deleteFrom(SHARE_ANNOTATION).execute();
	}

	@Override
	public long count() {
		return ctx.fetchCount(SHARE_COMMENT) + ctx.fetchCount(SHARE_ANNOTATION) + ctx.fetchCount(SHARE_REACTION);
	}

	// --- Comments ---

	@Override
	public ShareComment createComment(UUID shareUuid, UUID assetUuid, String authorName, String text) {
		Instant now = Instant.now();
		return new ShareCommentImpl()
			.setShareUuid(shareUuid)
			.setAssetUuid(assetUuid)
			.setAuthorName(authorName)
			.setText(text)
			.setCreated(now)
			.setEdited(now);
	}

	@Override
	public void storeComment(ShareComment comment) {
		insert(comment, SHARE_COMMENT);
	}

	@Override
	public ShareComment updateComment(ShareComment comment) {
		comment.setEdited(Instant.now());
		ctx.update(SHARE_COMMENT)
			.set(SHARE_COMMENT.TEXT, comment.getText())
			.set(SHARE_COMMENT.EDITED, java.time.LocalDateTime.ofInstant(comment.getEdited(), java.time.ZoneOffset.UTC))
			.where(SHARE_COMMENT.UUID.eq(comment.getUuid()))
			.execute();
		return comment;
	}

	@Override
	public ShareComment loadComment(UUID shareUuid, UUID commentUuid) {
		return ctx.select(SHARE_COMMENT.fields())
			.from(SHARE_COMMENT)
			.where(SHARE_COMMENT.UUID.eq(commentUuid).and(SHARE_COMMENT.SHARE_UUID.eq(shareUuid)))
			.fetchOneInto(ShareCommentImpl.class);
	}

	@Override
	public List<ShareComment> listComments(UUID shareUuid, UUID assetUuid) {
		Condition where = SHARE_COMMENT.SHARE_UUID.eq(shareUuid);
		if (assetUuid != null) {
			where = where.and(SHARE_COMMENT.ASSET_UUID.eq(assetUuid));
		}
		return ctx.select(SHARE_COMMENT.fields())
			.from(SHARE_COMMENT)
			.where(where)
			// Oldest first: a review thread reads as a conversation, and a reply must never render above the comment
			// it answers. UUID as the tiebreak keeps the order stable when two rows share a timestamp.
			.orderBy(SHARE_COMMENT.CREATED.asc(), SHARE_COMMENT.UUID.asc())
			.fetchInto(ShareCommentImpl.class)
			.stream().map(c -> (ShareComment) c).toList();
	}

	@Override
	public void deleteComment(UUID commentUuid) {
		ctx.deleteFrom(SHARE_COMMENT).where(SHARE_COMMENT.UUID.eq(commentUuid)).execute();
	}

	@Override
	public long countComments(UUID shareUuid) {
		return ctx.fetchCount(SHARE_COMMENT, SHARE_COMMENT.SHARE_UUID.eq(shareUuid));
	}

	// --- Annotations ---

	@Override
	public ShareAnnotation createAnnotation(UUID shareUuid, UUID assetUuid, ShareAnnotationKind kind, String authorName) {
		Instant now = Instant.now();
		return new ShareAnnotationImpl()
			.setShareUuid(shareUuid)
			.setAssetUuid(assetUuid)
			.setKind(kind)
			.setAuthorName(authorName)
			.setCreated(now)
			.setEdited(now);
	}

	@Override
	public void storeAnnotation(ShareAnnotation annotation) {
		insert(annotation, SHARE_ANNOTATION);
	}

	@Override
	public ShareAnnotation updateAnnotation(ShareAnnotation annotation) {
		annotation.setEdited(Instant.now());
		ctx.update(SHARE_ANNOTATION)
			.set(SHARE_ANNOTATION.TEXT, annotation.getText())
			.set(SHARE_ANNOTATION.TIME_FROM, annotation.getTimeFrom())
			.set(SHARE_ANNOTATION.TIME_TO, annotation.getTimeTo())
			.set(SHARE_ANNOTATION.AREA_X, annotation.getAreaX())
			.set(SHARE_ANNOTATION.AREA_Y, annotation.getAreaY())
			.set(SHARE_ANNOTATION.AREA_WIDTH, annotation.getAreaWidth())
			.set(SHARE_ANNOTATION.AREA_HEIGHT, annotation.getAreaHeight())
			.set(SHARE_ANNOTATION.EDITED, java.time.LocalDateTime.ofInstant(annotation.getEdited(), java.time.ZoneOffset.UTC))
			.where(SHARE_ANNOTATION.UUID.eq(annotation.getUuid()))
			.execute();
		return annotation;
	}

	@Override
	public ShareAnnotation loadAnnotation(UUID shareUuid, UUID annotationUuid) {
		return ctx.select(SHARE_ANNOTATION.fields())
			.from(SHARE_ANNOTATION)
			.where(SHARE_ANNOTATION.UUID.eq(annotationUuid).and(SHARE_ANNOTATION.SHARE_UUID.eq(shareUuid)))
			.fetchOneInto(ShareAnnotationImpl.class);
	}

	@Override
	public List<ShareAnnotation> listAnnotations(UUID shareUuid, UUID assetUuid) {
		Condition where = SHARE_ANNOTATION.SHARE_UUID.eq(shareUuid);
		if (assetUuid != null) {
			where = where.and(SHARE_ANNOTATION.ASSET_UUID.eq(assetUuid));
		}
		return ctx.select(SHARE_ANNOTATION.fields())
			.from(SHARE_ANNOTATION)
			.where(where)
			// By timecode, not by creation: the marks belong to the media's own order, which is what the timeline
			// under the player draws. Annotations with no time (a plain region) sort first and keep insertion order.
			.orderBy(SHARE_ANNOTATION.TIME_FROM.asc().nullsFirst(), SHARE_ANNOTATION.CREATED.asc())
			.fetchInto(ShareAnnotationImpl.class)
			.stream().map(a -> (ShareAnnotation) a).toList();
	}

	@Override
	public void deleteAnnotation(UUID annotationUuid) {
		ctx.deleteFrom(SHARE_ANNOTATION).where(SHARE_ANNOTATION.UUID.eq(annotationUuid)).execute();
	}

	@Override
	public long countAnnotations(UUID shareUuid) {
		return ctx.fetchCount(SHARE_ANNOTATION, SHARE_ANNOTATION.SHARE_UUID.eq(shareUuid));
	}

	// --- Reactions ---

	@Override
	public ShareReaction createReaction(UUID shareUuid, ShareReactionType type, String authorName) {
		return new ShareReactionImpl()
			.setShareUuid(shareUuid)
			.setType(type)
			.setAuthorName(authorName)
			.setCreated(Instant.now());
	}

	@Override
	public void storeReaction(ShareReaction reaction) {
		TableRecord<?> reco = ctx.newRecord(SHARE_REACTION, reaction);
		if (reaction.getUuid() == null) {
			reco.reset("uuid");
		}
		// onConflictDoNothing rather than a plain insert: the three partial unique indexes in V2.99 make a second
		// identical reaction a duplicate-key error, and a double-clicked thumbs-up is a normal thing for a person to
		// do, not a 500. The existing row already says what this one would have.
		UUID uuid = ctx.insertInto(SHARE_REACTION)
			.set(reco)
			.onConflictDoNothing()
			.returning(SHARE_REACTION.UUID)
			.fetchOne(SHARE_REACTION.UUID);
		if (uuid != null) {
			reaction.setUuid(uuid);
		}
	}

	@Override
	public ShareReaction loadReaction(UUID shareUuid, UUID reactionUuid) {
		return ctx.select(SHARE_REACTION.fields())
			.from(SHARE_REACTION)
			.where(SHARE_REACTION.UUID.eq(reactionUuid).and(SHARE_REACTION.SHARE_UUID.eq(shareUuid)))
			.fetchOneInto(ShareReactionImpl.class);
	}

	@Override
	public List<ShareReaction> listReactions(UUID shareUuid, UUID assetUuid) {
		Condition where = SHARE_REACTION.SHARE_UUID.eq(shareUuid);
		if (assetUuid != null) {
			where = where.and(SHARE_REACTION.ASSET_UUID.eq(assetUuid));
		}
		return ctx.select(SHARE_REACTION.fields())
			.from(SHARE_REACTION)
			.where(where)
			.orderBy(SHARE_REACTION.CREATED.asc(), SHARE_REACTION.UUID.asc())
			.fetchInto(ShareReactionImpl.class)
			.stream().map(r -> (ShareReaction) r).toList();
	}

	@Override
	public void deleteReaction(UUID reactionUuid) {
		ctx.deleteFrom(SHARE_REACTION).where(SHARE_REACTION.UUID.eq(reactionUuid)).execute();
	}

	@Override
	public long countReactions(UUID shareUuid) {
		return ctx.fetchCount(SHARE_REACTION, SHARE_REACTION.SHARE_UUID.eq(shareUuid));
	}

	/**
	 * Insert a row and write the generated uuid back onto the element.
	 *
	 * <p>
	 * The same shape as {@code AbstractJooqDao#store}, which this DAO cannot inherit because it manages three element types rather than one.
	 * </p>
	 */
	private void insert(io.metaloom.loom.db.Element<?> element, org.jooq.Table<? extends TableRecord<?>> table) {
		TableRecord<?> reco = ctx.newRecord(table, element);
		if (element.getUuid() == null) {
			reco.reset("uuid");
		}
		UUID uuid = ctx.insertInto(table)
			.set(reco)
			.returning(table.field("uuid", UUID.class))
			.fetchOne("uuid", UUID.class);
		if (uuid == null) {
			throw new IllegalStateException("Insert into " + table.getName() + " returned no uuid");
		}
		element.setUuid(uuid);
	}
}
