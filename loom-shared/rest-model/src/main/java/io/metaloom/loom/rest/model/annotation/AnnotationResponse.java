package io.metaloom.loom.rest.model.annotation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.rest.model.comment.CommentResponse;
import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;
import io.metaloom.loom.rest.model.tag.TagReference;
import io.metaloom.loom.rest.model.task.TaskResponse;

public class AnnotationResponse extends AbstractCreatorEditorRestResponse<AnnotationResponse> implements AnnotationModel<AnnotationResponse> {

	@JsonPropertyDescription("The type of the annotation.")
	private AnnotationType type;

	@JsonPropertyDescription("Title of the annotation")
	private String title;

	@JsonPropertyDescription("Spatial or temporal area the annotation references in the asset.")
	private AreaInfo area;

	@JsonPropertyDescription("Description of the annotation")
	private String description;

	@JsonPropertyDescription("Tasks assigned to the annotation")
	private List<TaskResponse> tasks = new ArrayList<>();

	@JsonPropertyDescription("Comments for the annotation")
	private List<CommentResponse> comments = new ArrayList<>();

	// TODO spec this
	@JsonPropertyDescription("Thumbnail reference information")
	private String thumbnail;

	@JsonPropertyDescription("List of tags for the entry")
	private List<TagReference> tags = new ArrayList<>();

	private UUID assetUuid;

	@Override
	public AnnotationType getType() {
		return type;
	}

	@Override
	public AnnotationResponse setType(AnnotationType type) {
		this.type = type;
		return this;
	}

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public AnnotationResponse setTitle(String title) {
		this.title = title;
		return this;
	}

	public List<TagReference> getTags() {
		return tags;
	}

	public AnnotationResponse setTags(List<TagReference> tags) {
		this.tags = tags;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public AnnotationResponse setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public AreaInfo getArea() {
		return area;
	}

	@Override
	public AnnotationResponse setArea(AreaInfo area) {
		this.area = area;
		return this;
	}

	public List<CommentResponse> getComments() {
		return comments;
	}

	public AnnotationResponse setComments(List<CommentResponse> comments) {
		this.comments = comments;
		return this;
	}

	public List<TaskResponse> getTasks() {
		return tasks;
	}

	public AnnotationResponse setTasks(List<TaskResponse> tasks) {
		this.tasks = tasks;
		return this;
	}

	public String getThumbnail() {
		return thumbnail;
	}

	public AnnotationResponse setThumbnail(String thumbnail) {
		this.thumbnail = thumbnail;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public AnnotationResponse setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public AnnotationResponse self() {
		return this;
	}

}
