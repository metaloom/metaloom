package deprecated.content;

import java.util.List;

import io.metaloom.loom.rest.model.RestModel;
import io.metaloom.loom.rest.model.tag.TagReference;

public class ContentUpdateRequest implements RestModel {

	private List<ContentField> fields;

	private List<TagReference> tags;

	public ContentUpdateRequest() {
	}

	public List<ContentField> getFields() {
		return fields;
	}

	public ContentUpdateRequest setFields(List<ContentField> fields) {
		this.fields = fields;
		return this;
	}

	public List<TagReference> getTags() {
		return tags;
	}

	public ContentUpdateRequest setTags(List<TagReference> tags) {
		this.tags = tags;
		return this;
	}

}
