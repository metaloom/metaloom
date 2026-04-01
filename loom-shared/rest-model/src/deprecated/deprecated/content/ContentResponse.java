package deprecated.content;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import deprecated.model.model.ModelReference;
import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;
import io.metaloom.loom.rest.model.tag.TagReference;

public class ContentResponse extends AbstractCreatorEditorRestResponse {

	@JsonPropertyDescription("The version of the content.")
	private String version;

	@JsonPropertyDescription("A reference to the content model. This value is immutable.")
	private ModelReference model;

	@JsonPropertyDescription("A reference to the parent content.")
	private ContentReference parent;

	@JsonPropertyDescription("A list of tags that were used to tag the content.")
	private List<TagReference> tags;

	@JsonPropertyDescription("A map of translations and their corresponding content fields.")
	private Map<String, List<ContentField>> fields;

	public ContentResponse() {

	}

	public String getVersion() {
		return version;
	}

	public ContentResponse setVersion(String version) {
		this.version = version;
		return this;
	}

	public ModelReference getModel() {
		return model;
	}

	public ContentResponse setModel(ModelReference model) {
		this.model = model;
		return this;
	}

	public ContentReference getParent() {
		return parent;
	}

	public ContentResponse setParent(ContentReference parent) {
		this.parent = parent;
		return this;
	}

	public List<TagReference> getTags() {
		return tags;
	}

	public ContentResponse setTags(List<TagReference> tags) {
		this.tags = tags;
		return this;
	}

	public Map<String, List<ContentField>> getFields() {
		return fields;
	}

	public ContentResponse setFields(Map<String, List<ContentField>> fields) {
		this.fields = fields;
		return this;
	}

}
