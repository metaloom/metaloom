package io.metaloom.loom.rest.model.assertj;

import io.metaloom.loom.rest.model.annotation.AnnotationResponse;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.location.AssetLocationResponse;
import io.metaloom.loom.rest.model.attachment.AttachmentResponse;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.comment.CommentResponse;
import io.metaloom.loom.rest.model.common.AbstractListResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.group.GroupResponse;
import io.metaloom.loom.rest.model.library.LibraryResponse;
import io.metaloom.loom.rest.model.project.ProjectResponse;
import io.metaloom.loom.rest.model.role.RoleResponse;
import io.metaloom.loom.rest.model.tag.TagResponse;
import io.metaloom.loom.rest.model.task.TaskResponse;
import io.metaloom.loom.rest.model.user.UserResponse;

public class Assertions extends org.assertj.core.api.Assertions {

	public static AnnotationModelAssert assertThat(AnnotationResponse model) {
		return new AnnotationModelAssert(model);
	}

	public static AssetModelAssert assertThat(AssetResponse model) {
		return new AssetModelAssert(model);
	}

	public static AssetLocationModelAssert assertThat(AssetLocationResponse model) {
		return new AssetLocationModelAssert(model);
	}

	public static AttachmentModelAssert assertThat(AttachmentResponse model) {
		return new AttachmentModelAssert(model);
	}

	public static CommentModelAssert assertThat(CommentResponse model) {
		return new CommentModelAssert(model);
	}

	public static ClusterModelAssert assertThat(ClusterResponse model) {
		return new ClusterModelAssert(model);
	}


	public static EmbeddingModelAssert assertThat(EmbeddingResponse model) {
		return new EmbeddingModelAssert(model);
	}

	
	public static TaskModelAssert assertThat(TaskResponse model) {
		return new TaskModelAssert(model);
	}

	public static ListResponseModelAssert assertThat(AbstractListResponse<?, ?> model) {
		return new ListResponseModelAssert(model);
	}

	public static UserModelAssert assertThat(UserResponse model) {
		return new UserModelAssert(model);
	}

	public static GroupModelAssert assertThat(GroupResponse model) {
		return new GroupModelAssert(model);
	}

	public static RoleModelAssert assertThat(RoleResponse model) {
		return new RoleModelAssert(model);
	}

	public static TagModelAssert assertThat(TagResponse model) {
		return new TagModelAssert(model);
	}

	public static LibraryModelAssert assertThat(LibraryResponse model) {
		return new LibraryModelAssert(model);
	}

	public static ProjectModelAssert assertThat(ProjectResponse model) {
		return new ProjectModelAssert(model);
	}

}