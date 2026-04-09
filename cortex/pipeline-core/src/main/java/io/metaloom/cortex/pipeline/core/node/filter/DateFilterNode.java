package io.metaloom.cortex.pipeline.core.node.filter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeResult;

/**
 * Filters media based on file modification or creation time.
 * Useful for incremental processing ("only files newer than X")
 * or archival workflows ("only files older than Y").
 */
public class DateFilterNode extends AbstractFilterNode {

	private static final Logger log = LoggerFactory.getLogger(DateFilterNode.class);

	/**
	 * Which timestamp to evaluate.
	 */
	public enum DateField {
		MODIFIED,
		CREATED
	}

	private final Instant minDate;
	private final Instant maxDate;
	private final DateField dateField;

	private DateFilterNode(Builder builder) {
		super(builder.id, builder.name);
		this.minDate = builder.minDate;
		this.maxDate = builder.maxDate;
		this.dateField = builder.dateField;
	}

	@Override
	protected boolean evaluate(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		try {
			Instant timestamp = resolveTimestamp(media);
			if (timestamp == null) {
				return true; // cannot determine — pass
			}
			if (minDate != null && timestamp.isBefore(minDate)) {
				return false;
			}
			if (maxDate != null && timestamp.isAfter(maxDate)) {
				return false;
			}
			return true;
		} catch (IOException e) {
			log.warn("Could not read file attributes for {}: {}", media.absolutePath(), e.getMessage());
			return false;
		}
	}

	private Instant resolveTimestamp(LoomMedia media) throws IOException {
		File file = media.file();
		if (file == null || !file.exists()) {
			return null;
		}
		BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
		switch (dateField) {
			case CREATED:
				return attrs.creationTime().toInstant();
			case MODIFIED:
			default:
				return attrs.lastModifiedTime().toInstant();
		}
	}

	@Override
	protected String rejectReason(LoomMedia media, Map<String, NodeResult> upstreamResults) {
		return "file date out of range (" + dateField + ")";
	}

	public static Builder builder(String id) {
		return new Builder(id);
	}

	public static class Builder {
		private final String id;
		private String name;
		private Set<String> dependencies = Set.of();
		private Instant minDate;
		private Instant maxDate;
		private DateField dateField = DateField.MODIFIED;

		private Builder(String id) {
			this.id = id;
			this.name = id;
		}

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder dependencies(Set<String> dependencies) {
			this.dependencies = dependencies;
			return this;
		}

		/** Reject files with a timestamp before this instant. */
		public Builder minDate(Instant minDate) {
			this.minDate = minDate;
			return this;
		}

		/** Reject files with a timestamp after this instant. */
		public Builder maxDate(Instant maxDate) {
			this.maxDate = maxDate;
			return this;
		}

		/** Which file timestamp to check (default: MODIFIED). */
		public Builder dateField(DateField dateField) {
			this.dateField = dateField;
			return this;
		}

		public DateFilterNode build() {
			if (minDate == null && maxDate == null) {
				throw new IllegalStateException("At least one of minDate or maxDate must be set");
			}
			return new DateFilterNode(this);
		}
	}
}
