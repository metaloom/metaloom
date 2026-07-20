package io.metaloom.loom.rest.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class PipelineMatcherTest {

	private PipelineVersion version(boolean enabled, int priority, JsonObject meta) {
		PipelineVersion v = mock(PipelineVersion.class);
		when(v.isEnabled()).thenReturn(enabled);
		when(v.getPriority()).thenReturn(priority);
		when(v.getMeta()).thenReturn(meta);
		when(v.getPipelineUuid()).thenReturn(UUID.randomUUID());
		return v;
	}

	private JsonObject trigger(boolean auto, String... mimeTypes) {
		return new JsonObject().put("trigger", new JsonObject()
			.put("auto", auto)
			.put("mimeTypes", new JsonArray(List.of(mimeTypes))));
	}

	@Test
	void matchesExactMimeType() {
		PipelineVersion v = version(true, 0, trigger(true, "image/png"));
		assertThat(PipelineMatcher.selectForMimeType(List.of(v), "image/png")).contains(v);
	}

	@Test
	void matchesPrefixWildcard() {
		PipelineVersion v = version(true, 0, trigger(true, "image/*"));
		assertThat(PipelineMatcher.selectForMimeType(List.of(v), "image/jpeg")).contains(v);
	}

	@Test
	void matchesGlobalWildcard() {
		PipelineVersion v = version(true, 0, trigger(true, "*"));
		assertThat(PipelineMatcher.selectForMimeType(List.of(v), "video/mp4")).contains(v);
	}

	@Test
	void doesNotMatchWhenDisabled() {
		PipelineVersion v = version(false, 0, trigger(true, "image/*"));
		assertThat(PipelineMatcher.selectForMimeType(List.of(v), "image/png")).isEmpty();
	}

	@Test
	void doesNotMatchWhenAutoFalse() {
		PipelineVersion v = version(true, 0, trigger(false, "image/*"));
		assertThat(PipelineMatcher.selectForMimeType(List.of(v), "image/png")).isEmpty();
	}

	@Test
	void doesNotMatchDifferentType() {
		PipelineVersion v = version(true, 0, trigger(true, "image/*"));
		assertThat(PipelineMatcher.selectForMimeType(List.of(v), "video/mp4")).isEmpty();
	}

	@Test
	void ignoresVersionWithoutTriggerMeta() {
		PipelineVersion v = version(true, 0, new JsonObject());
		assertThat(PipelineMatcher.selectForMimeType(List.of(v), "image/png")).isEmpty();
		PipelineVersion noMeta = version(true, 0, null);
		assertThat(PipelineMatcher.selectForMimeType(List.of(noMeta), "image/png")).isEmpty();
	}

	@Test
	void picksHighestPriorityAmongMatches() {
		PipelineVersion low = version(true, 1, trigger(true, "image/*"));
		PipelineVersion high = version(true, 5, trigger(true, "image/png"));
		Optional<PipelineVersion> result = PipelineMatcher.selectForMimeType(List.of(low, high), "image/png");
		assertThat(result).contains(high);
	}

	@Test
	void nullMimeTypeMatchesNothing() {
		PipelineVersion v = version(true, 0, trigger(true, "*"));
		assertThat(PipelineMatcher.selectForMimeType(List.of(v), null)).isEmpty();
	}
}
