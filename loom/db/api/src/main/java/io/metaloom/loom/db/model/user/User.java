package io.metaloom.loom.db.model.user;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;

public interface User extends CUDElement<User>, MetaElement<User> {

	/**
	 * Return the username.
	 * 
	 * @return
	 */
	String getUsername();

	/**
	 * Set the username.
	 * 
	 * @param username
	 * @return Fluent API
	 */
	User setUsername(String username);

	/**
	 * Return the stored password hash for the user.
	 * 
	 * @return
	 */
	// @Column(name = "password_hash")
	String getPasswordHash();

	/**
	 * Set the password hash.
	 * 
	 * @param hash
	 * @return Fluent API
	 */
	User setPasswordHash(String hash);

	/**
	 * Return the SSO flag for the user.
	 * 
	 * @return
	 */
	boolean isSSO();

	/**
	 * Set the SSO flag of the user.
	 * 
	 * @param flag
	 * @return
	 */
	User setSSO(boolean flag);

	/**
	 * Check whether the user is enabled.
	 * 
	 * @return
	 */
	boolean isEnabled();

	/**
	 * Set the enabled flag for the user. This flag can be controlled via regular update requests and the state may switched by users with the required
	 * permission.
	 * 
	 * @param flag
	 * @return Fluent API
	 */
	User setEnabled(boolean flag);

	/**
	 * Check whether the user has been deleted.
	 * 
	 * @return
	 */
	boolean isDeleted();

	/**
	 * Set the deletion flag for the user. This will indicate that the user deletion has occurred. The user record will not be removed in order to pass FK
	 * constraints.
	 * 
	 * @param flag
	 * @return Fluent API
	 */
	User setDeleted(boolean flag);

	/**
	 * Delete mark the user as deleted and purge all DSGVO related fields.
	 * 
	 * @return
	 */
	default User markDeleted() {
		setDeleted(true);
		setUsername(null);
		setFirstname(null);
		setLastname(null);
		setMeta(null);
		// A picture of a face is personal data in exactly the sense the rest of this method exists to purge.
		// Only the pointer can be dropped from here - this interface has no DAO - so the attachment row and its
		// bytes are removed by UserEndpointService.delete before it calls this.
		setAvatarAttachmentUuid(null);
		return this;
	}

	/**
	 * Enable the user. This will enable login.
	 * 
	 * @return
	 */
	default User enable() {
		setEnabled(true);
		return this;
	}

	/**
	 * Disable the user. This will prevent login.
	 * 
	 * @return
	 */
	default User disable() {
		setEnabled(false);
		return this;
	}

	/**
	 * Return the email of the user.
	 * 
	 * @return
	 */
	String getEmail();

	/**
	 * Set the email of the user
	 * 
	 * @param email
	 * @return Fluent API
	 */
	User setEmail(String email);

	/**
	 * Return the firstname of the user.
	 * 
	 * @return
	 */
	String getFirstname();

	/**
	 * Set the firstname of the user.
	 * 
	 * @param firstname
	 * @return Fluent API
	 */
	User setFirstname(String firstname);

	/**
	 * Return the lastname of the user.
	 * 
	 * @return
	 */
	String getLastname();

	/**
	 * Set the lastname of the user.
	 * 
	 * @param lastname
	 * @return Fluent API.
	 */
	User setLastname(String lastname);

	/**
	 * The account's avatar picture, or null.
	 *
	 * <p>
	 * Points at an {@code attachment} of type {@link io.metaloom.loom.api.attachment.AttachmentType#USER_AVATAR} owned by this user. At most one
	 * exists - a partial unique index enforces it (V2.93) - so uploading a new picture replaces the old one rather than adding to a gallery, which is
	 * where a user differs from a person.
	 * </p>
	 *
	 * @return the attachment uuid, or null when the account has no picture
	 */
	UUID getAvatarAttachmentUuid();

	User setAvatarAttachmentUuid(UUID avatarAttachmentUuid);
}
