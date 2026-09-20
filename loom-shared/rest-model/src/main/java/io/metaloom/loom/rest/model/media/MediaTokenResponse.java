package io.metaloom.loom.rest.model.media;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * A short-lived token authorising the media routes of one asset.
 *
 * <p>
 * It exists because an {@code <img>} or {@code <video>} element cannot send an {@code Authorization} header: the client appends this as
 * {@code ?mt=...} on a poster or stream URL instead. It is scoped to a single asset and expires in minutes, because a URL is a place where
 * credentials get logged, pasted and shared.
 * </p>
 */
public class MediaTokenResponse implements RestResponseModel<MediaTokenResponse> {

	@JsonPropertyDescription("The signed media token, to be appended as the mt query parameter on poster and stream URLs.")
	private String token;

	@JsonPropertyDescription("Lifetime of the token in seconds. Mint a new one rather than caching this past its expiry.")
	private int expiresIn;

	public String getToken() {
		return token;
	}

	public MediaTokenResponse setToken(String token) {
		this.token = token;
		return this;
	}

	public int getExpiresIn() {
		return expiresIn;
	}

	public MediaTokenResponse setExpiresIn(int expiresIn) {
		this.expiresIn = expiresIn;
		return this;
	}

	@Override
	public MediaTokenResponse self() {
		return this;
	}
}
