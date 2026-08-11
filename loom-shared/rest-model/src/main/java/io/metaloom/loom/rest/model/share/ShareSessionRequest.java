package io.metaloom.loom.rest.model.share;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * A visitor opening a share link: the password, if the link has one, and the name they would like to be known by.
 */
public class ShareSessionRequest implements RestRequestModel {

	@JsonPropertyDescription("The link password. Required when the challenge reported passwordRequired.")
	private String password;

	@JsonPropertyDescription("How the visitor wishes to be identified. Applied only on the first visit; later visits keep the stored name. "
		+ "Send the localised equivalent of \"Anonymous\" when the visitor skips the question.")
	private String visitorName;

	public String getPassword() {
		return password;
	}

	public ShareSessionRequest setPassword(String password) {
		this.password = password;
		return this;
	}

	public String getVisitorName() {
		return visitorName;
	}

	public ShareSessionRequest setVisitorName(String visitorName) {
		this.visitorName = visitorName;
		return this;
	}
}
