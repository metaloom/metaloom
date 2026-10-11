package io.metaloom.loom.rest.model.person;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface PersonExamples extends ExampleValues {

	/** A speaker cluster, distinct from the three shared example uuids so the provenance reads unambiguously. */
	String SPEAKER_CLUSTER_UUID = "5d1c0f2e-8a4b-4c7d-9e3f-2b6a1d0c9e84";

	default Example personUpdateRequestExample() {
		return new ExampleImpl(personUpdateRequest(), "The person update request", HttpResponseStatus.OK);
	}

	default Example personCreateRequestExample() {
		return new ExampleImpl(personCreateRequest(), "The person create request", HttpResponseStatus.CREATED);
	}

	default Example personResponseExample() {
		return new ExampleImpl(personResponse(), "The person response", HttpResponseStatus.OK);
	}

	default Example personListResponseExample() {
		return new ExampleImpl(personListResponse(), "The person list response", HttpResponseStatus.OK);
	}

	default Example personImageResponseExample() {
		return new ExampleImpl(personImageResponse(), "The person image response", HttpResponseStatus.CREATED);
	}

	default Example personImageListResponseExample() {
		return new ExampleImpl(personImageListResponse(), "The person image list response", HttpResponseStatus.OK);
	}

	default Example personImageImportRequestExample() {
		return new ExampleImpl(personImageImportRequest(), "The person image import request", HttpResponseStatus.CREATED);
	}

	default Example personAvatarRequestExample() {
		return new ExampleImpl(personAvatarRequest(), "The person avatar request", HttpResponseStatus.OK);
	}

	default PersonResponse personResponse() {
		PersonResponse model = new PersonResponse();
		model.setUuid(uuidC());
		model.setAlias("jdoe");
		model.setFirstname("John");
		model.setLastname("Doe");
		model.setAvatarUrl("/api/v1/persons/" + uuidC() + "/images/" + uuidA() + "/data");
		model.setHeroVoiceUrl("/api/v1/persons/" + uuidC() + "/voice-samples/" + uuidB() + "/data");
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default PersonImageResponse personImageResponse() {
		PersonImageResponse model = new PersonImageResponse();
		model.setUuid(uuidA());
		model.setFilename("john-doe.jpg");
		model.setMimeType("image/jpeg");
		model.setSize(48213L);
		model.setUrl("/api/v1/persons/" + uuidC() + "/images/" + uuidA() + "/data");
		model.setAvatar(true);
		setCreatorEditor(model);
		return model;
	}

	default PersonImageListResponse personImageListResponse() {
		PersonImageListResponse model = new PersonImageListResponse();
		model.setMetainfo(pagingInfo());
		model.add(personImageResponse());
		return model;
	}

	default PersonImageImportRequest personImageImportRequest() {
		PersonImageImportRequest model = new PersonImageImportRequest();
		model.setDetectionUuid(uuidB().toString());
		return model;
	}

	default PersonAvatarRequest personAvatarRequest() {
		PersonAvatarRequest model = new PersonAvatarRequest();
		model.setImageUuid(uuidA().toString());
		return model;
	}

	default PersonUpdateRequest personUpdateRequest() {
		PersonUpdateRequest model = new PersonUpdateRequest();
		model.setAlias("jdoe_updated");
		model.setFirstname("Jane");
		model.setLastname("Doe");
		model.setMeta(meta());
		return model;
	}

	default PersonCreateRequest personCreateRequest() {
		PersonCreateRequest model = new PersonCreateRequest();
		model.setAlias("jdoe");
		model.setFirstname("John");
		model.setLastname("Doe");
		model.setMeta(meta());
		return model;
	}

	default PersonListResponse personListResponse() {
		PersonListResponse model = new PersonListResponse();
		model.setMetainfo(pagingInfo());
		model.add(personResponse());
		model.add(personResponse());
		return model;
	}

	default Example personVoiceSampleResponseExample() {
		return new ExampleImpl(personVoiceSampleResponse(), "The person voice sample response", HttpResponseStatus.CREATED);
	}

	default Example personVoiceSampleListResponseExample() {
		return new ExampleImpl(personVoiceSampleListResponse(), "The person voice sample list response", HttpResponseStatus.OK);
	}

	default Example personVoiceSampleImportRequestExample() {
		return new ExampleImpl(personVoiceSampleImportRequest(), "Cut a speaker's longest turn into a voice sample", HttpResponseStatus.CREATED);
	}

	default Example personHeroVoiceRequestExample() {
		return new ExampleImpl(personHeroVoiceRequest(), "The person hero voice request", HttpResponseStatus.OK);
	}

	default Example personPrintCreateRequestExample() {
		return new ExampleImpl(personPrintCreateRequest(), "A reference print computed by a voice model", HttpResponseStatus.CREATED);
	}

	default Example personPrintResponseExample() {
		return new ExampleImpl(personPrintResponse(), "The stored print, without its vector", HttpResponseStatus.CREATED);
	}

	default Example personMatchRequestExample() {
		return new ExampleImpl(personMatchRequest(), "Match two speakers against the known persons", HttpResponseStatus.OK);
	}

	default Example personMatchResponseExample() {
		return new ExampleImpl(personMatchResponse(), "The person match response", HttpResponseStatus.OK);
	}

	default PersonPrintResponse personPrintResponse() {
		return new PersonPrintResponse()
			.setUuid(uuidA().toString())
			.setSampleUuid(uuidB().toString())
			.setType("speaker")
			.setModel("redimnet2-b6-vox2")
			.setDimensions(192)
			.setUsable(true)
			.setEdited("2026-10-11T09:30:00Z");
	}

	default PersonVoiceSampleResponse personVoiceSampleResponse() {
		PersonVoiceSampleResponse model = new PersonVoiceSampleResponse();
		model.setUuid(uuidB());
		model.setFilename("voice-press-conference.wav");
		model.setMimeType("audio/wav");
		model.setSize(960_044L);
		model.setUrl("/api/v1/persons/" + uuidC() + "/voice-samples/" + uuidB() + "/data");
		model.setHero(true);
		model.setDurationMs(30_000L);
		model.setSourceAssetUuid(uuidA().toString());
		model.setSourceClusterUuid(SPEAKER_CLUSTER_UUID);
		model.setSourceTimeFrom(61_500L);
		model.setSourceTimeTo(91_500L);
		model.getPrints().add(personPrintResponse());
		setCreatorEditor(model);
		return model;
	}

	default PersonVoiceSampleListResponse personVoiceSampleListResponse() {
		PersonVoiceSampleListResponse model = new PersonVoiceSampleListResponse();
		model.setMetainfo(pagingInfo());
		model.add(personVoiceSampleResponse());
		return model;
	}

	default PersonVoiceSampleImportRequest personVoiceSampleImportRequest() {
		return new PersonVoiceSampleImportRequest().setClusterUuid(SPEAKER_CLUSTER_UUID);
	}

	default PersonHeroVoiceRequest personHeroVoiceRequest() {
		return new PersonHeroVoiceRequest().setSampleUuid(uuidB().toString());
	}

	default PersonPrintCreateRequest personPrintCreateRequest() {
		return new PersonPrintCreateRequest()
			.setType("speaker")
			.setModel("redimnet2-b6-vox2")
			.setVector(new Float[] { 0.12f, -0.03f, 0.41f });
	}

	default PersonMatchRequest personMatchRequest() {
		PersonMatchRequest model = new PersonMatchRequest()
			.setType("speaker")
			.setModel("redimnet2-b6-vox2")
			.setThreshold(0.43f)
			.setLimit(3);
		model.getVectors().add(new Float[] { 0.12f, -0.03f, 0.41f });
		model.getVectors().add(new Float[] { -0.2f, 0.33f, 0.05f });
		return model;
	}

	default PersonMatchResponse personMatchResponse() {
		PersonMatchResponse model = new PersonMatchResponse().setPersonCount(42).setReferenceCount(117);
		PersonMatchResult first = new PersonMatchResult().setIndex(0);
		first.getCandidates().add(new PersonMatchCandidate().setPersonUuid(uuidC().toString()).setName("jdoe").setScore(0.71f).setReferences(4));
		model.getResults().add(first);
		model.getResults().add(new PersonMatchResult().setIndex(1));
		model.getPendingSamples().add(new PersonPendingSample()
			.setPersonUuid(uuidC().toString())
			.setSampleUuid(uuidB().toString())
			.setMimeType("audio/mpeg")
			.setUrl("/api/v1/persons/" + uuidC() + "/voice-samples/" + uuidB() + "/data"));
		return model;
	}

}
