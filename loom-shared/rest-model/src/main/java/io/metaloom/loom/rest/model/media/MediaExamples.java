package io.metaloom.loom.rest.model.media;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface MediaExamples extends ExampleValues {

	default Example mediaTokenResponseExample() {
		return new ExampleImpl(mediaTokenResponse(), "The media token response", HttpResponseStatus.OK);
	}

	default MediaTokenResponse mediaTokenResponse() {
		// Deliberately not a real-looking JWT: an example credential that reads like one invites
		// somebody to try it.
		return new MediaTokenResponse().setToken("<signed media token>").setExpiresIn(600);
	}

	default Example mediaInfoResponseExample() {
		return new ExampleImpl(mediaInfoResponse(), "What the decoder reports about a video", HttpResponseStatus.OK);
	}

	default MediaInfoResponse mediaInfoResponse() {
		return new MediaInfoResponse()
			.setDuration(2580.4d)
			.setFrameRate(23.976d)
			.setWidth(1920)
			.setHeight(1080)
			.setVideoCodec("h264")
			.setAudioCodec("ac3")
			.setStreamable(true);
	}

	default Example streamStartResponseExample() {
		return new ExampleImpl(streamStartResponse(), "Where a stream asked to start at that offset will really begin", HttpResponseStatus.OK);
	}

	default StreamStartResponse streamStartResponse() {
		// Four seconds apart, which is an ordinary keyframe interval for a broadcast rip and about
		// as wrong as a player's clock can silently be.
		return new StreamStartResponse()
			.setRequested(604.5d)
			.setStart(599.599d);
	}
}
