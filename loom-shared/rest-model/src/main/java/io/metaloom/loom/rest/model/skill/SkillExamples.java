package io.metaloom.loom.rest.model.skill;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface SkillExamples extends ExampleValues {

	default Example skillCreateRequestExample() {
		return new ExampleImpl(skillCreateRequest(), "The skill create request", HttpResponseStatus.CREATED);
	}

	default Example skillUpdateRequestExample() {
		return new ExampleImpl(skillUpdateRequest(), "The skill update request", HttpResponseStatus.OK);
	}

	default Example skillResponseExample() {
		return new ExampleImpl(skillResponse(), "The skill response", HttpResponseStatus.OK);
	}

	default Example skillListResponseExample() {
		return new ExampleImpl(skillListResponse(), "The skill list response", HttpResponseStatus.OK);
	}

	default Example skillVersionListResponseExample() {
		return new ExampleImpl(skillVersionListResponse(), "The skill version list response", HttpResponseStatus.OK);
	}

	default SkillResponse skillResponse() {
		SkillResponse model = new SkillResponse();
		model.setUuid(uuidC());
		model.setName("transcript-summarizer");
		model.setDescription("Summarize video transcripts into concise bullet lists");
		model.setContent("# Transcript Summarizer\nSummarize the transcript of the referenced asset into short bullet points.");
		model.setEnabled(true);
		model.setPublished(false);
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default SkillListResponse skillListResponse() {
		SkillListResponse model = new SkillListResponse();
		model.setMetainfo(pagingInfo());
		model.add(skillResponse());
		model.add(skillResponse());
		return model;
	}

	default SkillResponse skillVersionResponse() {
		SkillResponse model = skillResponse();
		model.setVersionUuid(uuidB());
		model.setVersionNumber(1);
		return model;
	}

	default SkillVersionListResponse skillVersionListResponse() {
		SkillVersionListResponse model = new SkillVersionListResponse();
		model.setMetainfo(pagingInfo());
		model.add(skillVersionResponse());
		model.add(skillVersionResponse());
		return model;
	}

	default SkillCreateRequest skillCreateRequest() {
		SkillCreateRequest model = new SkillCreateRequest();
		model.setName("transcript-summarizer");
		model.setDescription("Summarize video transcripts into concise bullet lists");
		model.setContent("# Transcript Summarizer\nSummarize the transcript of the referenced asset into short bullet points.");
		model.setMeta(meta());
		return model;
	}

	default SkillUpdateRequest skillUpdateRequest() {
		SkillUpdateRequest model = new SkillUpdateRequest();
		model.setName("transcript-summarizer");
		model.setDescription("Updated description");
		model.setContent("# Transcript Summarizer\nUpdated instructions.");
		model.setEnabled(false);
		model.setPublished(true);
		model.setMeta(meta());
		return model;
	}

}
