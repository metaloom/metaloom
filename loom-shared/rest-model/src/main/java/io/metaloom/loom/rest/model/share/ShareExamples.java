package io.metaloom.loom.rest.model.share;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface ShareExamples extends ExampleValues {

	String EXAMPLE_SLUG = "k3Rm2pQwXbN7vTsLd9aYc1";

	default Example shareCreateRequestExample() {
		return new ExampleImpl(shareCreateRequest(), "The share link create request", HttpResponseStatus.CREATED);
	}

	default Example shareUpdateRequestExample() {
		return new ExampleImpl(shareUpdateRequest(), "The share link update request", HttpResponseStatus.OK);
	}

	default Example shareResponseExample() {
		return new ExampleImpl(shareResponse(), "The share link response", HttpResponseStatus.OK);
	}

	default Example shareListResponseExample() {
		return new ExampleImpl(shareListResponse(), "The share link list response", HttpResponseStatus.OK);
	}

	default Example shareChallengeResponseExample() {
		return new ExampleImpl(shareChallengeResponse(), "What an unopened share link tells a visitor", HttpResponseStatus.OK);
	}

	default Example shareSessionRequestExample() {
		return new ExampleImpl(shareSessionRequest(), "A visitor opening a share link", HttpResponseStatus.OK);
	}

	default Example shareSessionResponseExample() {
		return new ExampleImpl(shareSessionResponse(), "The redeemed share session", HttpResponseStatus.OK);
	}

	default Example sharedAssetResponseExample() {
		return new ExampleImpl(sharedAssetResponse(), "One asset as a share visitor sees it", HttpResponseStatus.OK);
	}

	default Example sharedAssetListResponseExample() {
		return new ExampleImpl(sharedAssetListResponse(), "The assets behind a share link", HttpResponseStatus.OK);
	}

	default Example shareCommentRequestExample() {
		return new ExampleImpl(shareCommentRequest(), "A comment left through a share link", HttpResponseStatus.CREATED);
	}

	default Example shareCommentResponseExample() {
		return new ExampleImpl(shareCommentResponse(), "A guest comment", HttpResponseStatus.OK);
	}

	default Example shareCommentListResponseExample() {
		return new ExampleImpl(shareCommentListResponse(), "The guest comments on a share", HttpResponseStatus.OK);
	}

	default Example shareAnnotationRequestExample() {
		return new ExampleImpl(shareAnnotationRequest(), "A mark drawn through a share link", HttpResponseStatus.CREATED);
	}

	default Example shareAnnotationResponseExample() {
		return new ExampleImpl(shareAnnotationResponse(), "A guest annotation", HttpResponseStatus.OK);
	}

	default Example shareAnnotationListResponseExample() {
		return new ExampleImpl(shareAnnotationListResponse(), "The guest annotations on a share", HttpResponseStatus.OK);
	}

	default Example shareReactionRequestExample() {
		return new ExampleImpl(shareReactionRequest(), "A reaction left through a share link", HttpResponseStatus.CREATED);
	}

	default Example shareReactionResponseExample() {
		return new ExampleImpl(shareReactionResponse(), "A guest reaction", HttpResponseStatus.OK);
	}

	default Example shareReactionListResponseExample() {
		return new ExampleImpl(shareReactionListResponse(), "The guest reactions on a share", HttpResponseStatus.OK);
	}

	default Example shareFeedbackResponseExample() {
		return new ExampleImpl(shareFeedbackResponse(), "Everything the visitor said, for the owner", HttpResponseStatus.OK);
	}

	default ShareResponse shareResponse() {
		ShareResponse model = new ShareResponse();
		model.setUuid(uuidC());
		model.setSlug(EXAMPLE_SLUG);
		model.setUrl("https://loom.example.com/ui/share/" + EXAMPLE_SLUG);
		model.setTargetType("COLLECTION");
		model.setTargetUuid(uuidA());
		model.setTargetName("Autumn campaign - rough cuts");
		model.setPasswordProtected(true);
		model.setExpired(false);
		model.setExpiresAt(DATE_NEW);
		model.setAllowDownload(true);
		model.setShowMetadata(true);
		model.setAllowComments(true);
		model.setAllowReactions(true);
		model.setAllowAnnotations(true);
		model.setVisitorName("Maria from Acme");
		model.setFirstVisitedAt(DATE_OLD);
		model.setLastViewedAt(DATE_NEW);
		model.setViewCount(4);
		model.setFeedbackCount(7);
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default ShareListResponse shareListResponse() {
		ShareListResponse model = new ShareListResponse();
		model.setMetainfo(pagingInfo());
		model.add(shareResponse());
		model.add(shareResponse());
		return model;
	}

	default ShareCreateRequest shareCreateRequest() {
		ShareCreateRequest model = new ShareCreateRequest();
		model.setTargetType("COLLECTION");
		model.setTargetUuid(uuidA());
		model.setPassword("wander-lamp-42");
		model.setExpiresAt(DATE_NEW);
		model.setAllowDownload(true);
		model.setShowMetadata(true);
		model.setAllowComments(true);
		model.setAllowReactions(true);
		model.setAllowAnnotations(true);
		model.setMeta(meta());
		return model;
	}

	default ShareUpdateRequest shareUpdateRequest() {
		ShareUpdateRequest model = new ShareUpdateRequest();
		model.setExpiresAt(DATE_NEW);
		model.setAllowDownload(false);
		return model;
	}

	default ShareChallengeResponse shareChallengeResponse() {
		ShareChallengeResponse model = new ShareChallengeResponse();
		model.setTargetType("COLLECTION");
		model.setPasswordRequired(true);
		model.setVisitorNameKnown(false);
		return model;
	}

	default ShareSessionRequest shareSessionRequest() {
		ShareSessionRequest model = new ShareSessionRequest();
		model.setPassword("wander-lamp-42");
		model.setVisitorName("Maria from Acme");
		return model;
	}

	default ShareSessionResponse shareSessionResponse() {
		ShareSessionResponse model = new ShareSessionResponse();
		model.setSessionToken("c2x1Zy1leGFtcGxlfDE3NjQ1MDAwMDA.9mZ0aXNub3RhcmVhbHNpZ25hdHVyZQ");
		model.setSessionExpiresAt(DATE_NEW);
		model.setVisitorName("Maria from Acme");
		model.setTargetType("COLLECTION");
		model.setTargetName("Autumn campaign - rough cuts");
		model.setTargetDescription("Three cuts for sign-off before the grade.");
		model.setAllowDownload(true);
		model.setShowMetadata(true);
		model.setAllowComments(true);
		model.setAllowReactions(true);
		model.setAllowAnnotations(true);
		return model;
	}

	default SharedAssetResponse sharedAssetResponse() {
		SharedAssetResponse model = new SharedAssetResponse();
		model.setUuid(uuidB());
		model.setFilename("autumn-cut-02.mp4");
		model.setMimeType("video/mp4");
		model.setSize(184_320_512L);
		model.setDuration(92.5);
		model.setWidth(1920);
		model.setHeight(1080);
		model.setTitle("Autumn campaign, cut 2");
		model.setDescription("Second assembly, no grade yet.");
		model.setCreated(DATE_OLD);
		return model;
	}

	default SharedAssetListResponse sharedAssetListResponse() {
		SharedAssetListResponse model = new SharedAssetListResponse();
		model.setMetainfo(pagingInfo());
		model.add(sharedAssetResponse());
		return model;
	}

	default ShareCommentRequest shareCommentRequest() {
		ShareCommentRequest model = new ShareCommentRequest();
		model.setAssetUuid(uuidB());
		model.setText("The second cut runs long - can we lose the establishing shot?");
		return model;
	}

	default ShareCommentResponse shareCommentResponse() {
		ShareCommentResponse model = new ShareCommentResponse();
		model.setUuid(uuidA());
		model.setAssetUuid(uuidB());
		model.setText("The second cut runs long - can we lose the establishing shot?");
		model.setAuthorName("Maria from Acme");
		model.setCreated(DATE_OLD);
		model.setEdited(DATE_OLD);
		return model;
	}

	default ShareCommentListResponse shareCommentListResponse() {
		ShareCommentListResponse model = new ShareCommentListResponse();
		model.setMetainfo(pagingInfo());
		model.add(shareCommentResponse());
		return model;
	}

	default ShareAnnotationRequest shareAnnotationRequest() {
		ShareAnnotationRequest model = new ShareAnnotationRequest();
		model.setAssetUuid(uuidB());
		model.setKind("SPATIOTEMPORAL");
		model.setTimeFrom(14.25);
		model.setTimeTo(19.5);
		model.setAreaX(0.42);
		model.setAreaY(0.18);
		model.setAreaWidth(0.16);
		model.setAreaHeight(0.22);
		model.setText("The logo is clipped on the right here.");
		return model;
	}

	default ShareAnnotationResponse shareAnnotationResponse() {
		ShareAnnotationResponse model = new ShareAnnotationResponse();
		model.setUuid(uuidC());
		model.setAssetUuid(uuidB());
		model.setKind("SPATIOTEMPORAL");
		model.setTimeFrom(14.25);
		model.setTimeTo(19.5);
		model.setAreaX(0.42);
		model.setAreaY(0.18);
		model.setAreaWidth(0.16);
		model.setAreaHeight(0.22);
		model.setText("The logo is clipped on the right here.");
		model.setAuthorName("Maria from Acme");
		model.setCreated(DATE_OLD);
		model.setEdited(DATE_OLD);
		return model;
	}

	default ShareAnnotationListResponse shareAnnotationListResponse() {
		ShareAnnotationListResponse model = new ShareAnnotationListResponse();
		model.setMetainfo(pagingInfo());
		model.add(shareAnnotationResponse());
		return model;
	}

	default ShareReactionRequest shareReactionRequest() {
		ShareReactionRequest model = new ShareReactionRequest();
		model.setType("APPROVE");
		model.setAssetUuid(uuidB());
		return model;
	}

	default ShareReactionResponse shareReactionResponse() {
		ShareReactionResponse model = new ShareReactionResponse();
		model.setUuid(uuidA());
		model.setType("APPROVE");
		model.setAssetUuid(uuidB());
		model.setAuthorName("Maria from Acme");
		model.setCreated(DATE_NEW);
		return model;
	}

	default ShareReactionListResponse shareReactionListResponse() {
		ShareReactionListResponse model = new ShareReactionListResponse();
		model.setMetainfo(pagingInfo());
		model.add(shareReactionResponse());
		return model;
	}

	default ShareFeedbackResponse shareFeedbackResponse() {
		ShareFeedbackResponse model = new ShareFeedbackResponse();
		model.setUuid(uuidC());
		model.setVisitorName("Maria from Acme");
		model.getComments().add(shareCommentResponse());
		model.getAnnotations().add(shareAnnotationResponse());
		model.getReactions().add(shareReactionResponse());
		return model;
	}

	/**
	 * A stable "seven days from now" for documentation. Not {@code Instant.now()}-derived at call time anywhere it matters, because the generated
	 * OpenAPI document has to be reproducible - {@code LoomOpenAPITest} fails on output that changes between runs.
	 */
	default Instant exampleExpiry() {
		return DATE_NEW.plus(7, ChronoUnit.DAYS);
	}
}
