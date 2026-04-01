package io.metaloom.loom.rest.model.example;

import static io.metaloom.loom.rest.model.example.ContentFieldExamples.assetField;
import static io.metaloom.loom.rest.model.example.ContentFieldExamples.textField;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import deprecated.content.ContentCreateRequest;
import deprecated.content.ContentField;
import deprecated.content.ContentListResponse;
import deprecated.content.ContentReference;
import deprecated.content.ContentResponse;
import deprecated.content.ContentUpdateRequest;
import io.metaloom.loom.rest.model.tag.TagReference;

public class ContentExamples extends AbstractExamples {

	public static ContentResponse contentResponse() {
		ContentResponse model = new ContentResponse();
		model.setUuid(uuidC());
		model.setVersion("1.0");
		model.setParent(parentContentReference());
		model.setModel(modelReference("BlogPost"));
		model.setTags(tagReferenceList());
		Map<String, List<ContentField>> fieldsMap = new HashMap<>();
		fieldsMap.put("default", contentDefaultFieldList());
		fieldsMap.put("en", contentEnglishFieldList());
		fieldsMap.put("de", contentGermanFieldList());

		model.setFields(fieldsMap);
		setCreatorEditor(model);
		return model;
	}

	public static List<ContentField> contentEnglishFieldList() {
		List<ContentField> fields = new ArrayList<>();
		fields.add(textField("text", "The quick brown fox jumps over the lazy dog"));
		return fields;
	}

	public static List<ContentField> contentGermanFieldList() {
		List<ContentField> fields = new ArrayList<>();
		fields.add(textField("text", "Der schnelle braune Fuchs springt über den faulen Hund"));
		return fields;
	}

	public static List<ContentField> contentDefaultFieldList() {
		List<ContentField> fields = new ArrayList<>();
		fields.add(assetField("image", "dog.jpg"));
		return fields;
	}

	public static List<TagReference> tagReferenceList() {
		return Arrays.asList(tagReferenceA(), tagReferenceB());
	}

	public static ContentCreateRequest contentCreateRequest() {
		ContentCreateRequest model = new ContentCreateRequest();
		model.setModel(modelReference("BlogPost"));
		model.setTags(tagReferenceList());
		return model;
	}

	public static ContentUpdateRequest contentUpdateRequest() {
		ContentUpdateRequest model = new ContentUpdateRequest();
		model.setTags(tagReferenceList());
		return model;
	}

	public static ContentListResponse contentListResponse() {
		ContentListResponse model = new ContentListResponse();
		model.setMetainfo(pagingInfo());
		model.add(contentResponse());
		model.add(contentResponse());
		return model;
	}

	public static ContentReference contentReference() {
		ContentReference model = new ContentReference();
		model.setUuid(uuidA());
		model.setName("MyPost");
		return model;
	}


}
