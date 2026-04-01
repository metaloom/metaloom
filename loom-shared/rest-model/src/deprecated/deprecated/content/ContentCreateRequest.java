package deprecated.content;

import java.util.List;

import deprecated.model.model.ModelReference;
import io.metaloom.loom.rest.model.RestModel;
import io.metaloom.loom.rest.model.tag.TagReference;

public class ContentCreateRequest implements RestModel {

	private ModelReference model;

	private List<ContentField> fields;

	private List<TagReference> tags;

	public ContentCreateRequest() {
	}

	public ModelReference getModel() {
		return model;
	}

	public ContentCreateRequest setModel(ModelReference model) {
		this.model = model;
		return this;
	}

	public List<ContentField> getFields() {
		return fields;
	}

	public ContentCreateRequest setFields(List<ContentField> fields) {
		this.fields = fields;
		return this;
	}

	public List<TagReference> getTags() {
		return tags;
	}

	public ContentCreateRequest setTags(List<TagReference> tags) {
		this.tags = tags;
		return this;
	}
}
