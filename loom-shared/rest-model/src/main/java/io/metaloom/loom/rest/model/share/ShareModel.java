package io.metaloom.loom.rest.model.share;

import java.time.Instant;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

/**
 * The fields a share link carries on the owner-facing API, shared by the response and the update request.
 *
 * <p>
 * The password is <b>not</b> here. It travels one way in ({@code ShareCreateRequest#getPassword()}) and comes back exactly once, on the response to
 * the request that set it - only the bcrypt hash is stored, so there is nothing to return afterwards.
 * </p>
 */
public interface ShareModel<T extends ShareModel<T>> extends MetaModel<T>, RestModel {

	/** When the link stops working. Null means never. */
	Instant getExpiresAt();

	T setExpiresAt(Instant expiresAt);

	/** Whether the visitor may fetch the original file, rather than only viewing or streaming it. */
	Boolean getAllowDownload();

	T setAllowDownload(Boolean allowDownload);

	/** Whether the visitor sees title, description, size, duration and dimensions. */
	Boolean getShowMetadata();

	T setShowMetadata(Boolean showMetadata);

	Boolean getAllowComments();

	T setAllowComments(Boolean allowComments);

	Boolean getAllowReactions();

	T setAllowReactions(Boolean allowReactions);

	Boolean getAllowAnnotations();

	T setAllowAnnotations(Boolean allowAnnotations);
}
