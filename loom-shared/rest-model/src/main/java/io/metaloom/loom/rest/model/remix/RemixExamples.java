package io.metaloom.loom.rest.model.remix;

import java.time.Instant;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface RemixExamples extends ExampleValues {

	default Example remixResponseExample() {
		return new ExampleImpl(remixResponse(), "The remix response", HttpResponseStatus.OK);
	}

	default Example remixListResponseExample() {
		return new ExampleImpl(remixListResponse(), "The remix list response", HttpResponseStatus.OK);
	}

	default Example remixCreateRequestExample() {
		return new ExampleImpl(remixCreateRequest(), "The remix create request", HttpResponseStatus.CREATED);
	}

	default Example remixUpdateRequestExample() {
		return new ExampleImpl(remixUpdateRequest(), "The remix update request", HttpResponseStatus.OK);
	}

	default Example remixMemberRequestExample() {
		return new ExampleImpl(remixMemberRequest(), "The remix membership request", HttpResponseStatus.OK);
	}

	default Example remixMemberListResponseExample() {
		return new ExampleImpl(remixMemberListResponse(), "The remix member list response", HttpResponseStatus.OK);
	}

	default RemixResponse remixResponse() {
		RemixResponse model = new RemixResponse();
		model.setUuid(uuidC());
		model.setName("Beach clip and its cuts");
		model.setDescription("The original beach clip plus the two edits cut from it.");
		model.setSourceAssetUuid(uuidA());
		model.setMemberCount(3);
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default RemixListResponse remixListResponse() {
		RemixListResponse model = new RemixListResponse();
		model.setMetainfo(pagingInfo());
		model.add(remixResponse());
		model.add(remixResponse());
		return model;
	}

	default RemixCreateRequest remixCreateRequest() {
		RemixCreateRequest model = new RemixCreateRequest();
		model.setName("Beach clip and its cuts");
		model.setDescription("The original beach clip plus the two edits cut from it.");
		model.add(uuidA());
		model.add(uuidB());
		model.setSourceAssetUuid(uuidA());
		model.setMeta(meta());
		return model;
	}

	default RemixUpdateRequest remixUpdateRequest() {
		RemixUpdateRequest model = new RemixUpdateRequest();
		model.setName("Beach clip and its cuts");
		model.setDescription("The original beach clip plus the two edits cut from it.");
		model.setSourceAssetUuid(uuidA());
		model.setMeta(meta());
		return model;
	}

	default RemixMemberRequest remixMemberRequest() {
		RemixMemberRequest model = new RemixMemberRequest();
		model.add(uuidB());
		return model;
	}

	default RemixMemberResponse remixMemberResponse() {
		RemixMemberResponse model = new RemixMemberResponse();
		model.setUuid(uuidC());
		model.setAssetUuid(uuidA());
		model.setRole(RemixMemberResponse.ROLE_SOURCE);
		model.setOrdinal(0);
		model.setFilename("beach.mp4");
		model.setMimeType("video/mp4");
		model.setSha512sum(sha512sum().toString());
		model.setSize(4711L);
		model.setAdded(Instant.parse("2026-08-12T10:15:30.00Z"));
		model.setAddedBy(uuidB());
		return model;
	}

	default RemixMemberListResponse remixMemberListResponse() {
		RemixMemberListResponse model = new RemixMemberListResponse();
		model.setMetainfo(pagingInfo());
		model.add(remixMemberResponse());
		return model;
	}

}
