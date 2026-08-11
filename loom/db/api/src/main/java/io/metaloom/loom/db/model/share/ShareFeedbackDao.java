package io.metaloom.loom.db.model.share;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.Dao;

/**
 * One facade over the three tables a share visitor writes: {@code share_comment}, {@code share_annotation} and {@code share_reaction}.
 *
 * <p>
 * <b>Not a {@code CRUDDao}</b>, and therefore not covered by {@code CRUDDaoTestcases} - it manages three element types rather than one, following the
 * precedent of {@code AssetComponentDao} (nine component tables) and {@code DedupGroupDao} (group plus members). The three tables are always read
 * together: the reviewer's panel and the owner's feedback tab both want everything one visitor said in one pass, and splitting them across three DAOs
 * would buy a generic contract nothing calls in exchange for three more constructor parameters in {@code DaoCollectionImpl}. {@code ShareFeedbackDaoTest}
 * covers it directly instead.
 * </p>
 *
 * <p>
 * Every method is scoped by share. There is deliberately no "load any comment by uuid" - a guest request always arrives with a slug, and resolving a
 * comment without checking it belongs to that share is exactly the mistake that would turn one link into a window onto everybody else's feedback.
 * </p>
 */
public interface ShareFeedbackDao extends Dao {

	// --- Comments ---

	/**
	 * Build an unsaved comment. {@code assetUuid} may be null for a comment about a shared collection as a whole.
	 */
	ShareComment createComment(UUID shareUuid, UUID assetUuid, String authorName, String text);

	void storeComment(ShareComment comment);

	ShareComment updateComment(ShareComment comment);

	/**
	 * Load a comment, but only if it belongs to the given share. Returns null otherwise - a guest holding one link must not be able to address another
	 * link's rows by uuid.
	 */
	ShareComment loadComment(UUID shareUuid, UUID commentUuid);

	/**
	 * Every comment on a share, oldest first, optionally narrowed to one asset.
	 *
	 * @param shareUuid
	 *            the share
	 * @param assetUuid
	 *            narrow to one asset, or null for all of them including collection-level comments
	 */
	List<ShareComment> listComments(UUID shareUuid, UUID assetUuid);

	void deleteComment(UUID commentUuid);

	long countComments(UUID shareUuid);

	// --- Annotations ---

	ShareAnnotation createAnnotation(UUID shareUuid, UUID assetUuid, ShareAnnotationKind kind, String authorName);

	void storeAnnotation(ShareAnnotation annotation);

	ShareAnnotation updateAnnotation(ShareAnnotation annotation);

	/** Load an annotation, but only if it belongs to the given share. */
	ShareAnnotation loadAnnotation(UUID shareUuid, UUID annotationUuid);

	List<ShareAnnotation> listAnnotations(UUID shareUuid, UUID assetUuid);

	void deleteAnnotation(UUID annotationUuid);

	long countAnnotations(UUID shareUuid);

	// --- Reactions ---

	ShareReaction createReaction(UUID shareUuid, ShareReactionType type, String authorName);

	/**
	 * Insert the reaction, or do nothing when this share has already reacted that way to that subject.
	 *
	 * <p>
	 * Idempotent by construction rather than by a caller's check: the three partial unique indexes in V2.99 are what make a double-click harmless, and
	 * a plain insert would surface the second one as a 500.
	 * </p>
	 */
	void storeReaction(ShareReaction reaction);

	/** Load a reaction, but only if it belongs to the given share. */
	ShareReaction loadReaction(UUID shareUuid, UUID reactionUuid);

	List<ShareReaction> listReactions(UUID shareUuid, UUID assetUuid);

	void deleteReaction(UUID reactionUuid);

	long countReactions(UUID shareUuid);
}
