package io.metaloom.loom.rest.model.example;

import java.util.Arrays;

import deprecated.model.model.ModelCreateRequest;
import deprecated.model.model.ModelListResponse;
import deprecated.model.model.ModelReference;
import deprecated.model.model.ModelResponse;
import deprecated.model.model.ModelUpdateRequest;
import deprecated.model.model.field.impl.asset.AssetModelField;
import deprecated.model.model.field.impl.text.TextMarkup;
import deprecated.model.model.field.impl.text.TextModelField;

public class ModelExamples extends AbstractExamples {

	public static ModelResponse modelResponse() {
		ModelResponse model = new ModelResponse();
		model.setUuid(uuidC());
		model.setName("BlogPost");
		model.setSearchable(true);
		model.setSegmentField("name");
		model.setFields(Arrays.asList(textModelField(), assetModelField()));
		model.setExtension("Post");
		setCreatorEditor(model);
		return model;
	}

	public static AssetModelField assetModelField() {
		AssetModelField field = new AssetModelField();
		field.setName("image");
		field.setI18N(false);
		field.setIndexing(false);
		field.setRequired(true);
		return field;
	}

	public static TextModelField textModelField() {
		TextModelField field = new TextModelField();
		field.setName("name");
		field.setI18N(false);
		field.setMarkup(TextMarkup.PLAIN);
		field.setIndexing(true);
		field.setRequired(true);
		return field;
	}

	public static ModelUpdateRequest modelUpdateRequest() {
		ModelUpdateRequest model = new ModelUpdateRequest();
		model.setName("NewBlogPost");
		model.setSearchable(true);
		model.setSegmentField("name");
		model.setFields(Arrays.asList(textModelField(), assetModelField()));
		model.setExtension("Post");
		return model;
	}

	public static ModelReference modelReference() {
		ModelReference model = new ModelReference();
		model.setUuid(uuidC());
		model.setName("BlogPost");
		return model;
	}

	public static ModelCreateRequest modelCreateRequest() {
		ModelCreateRequest model = new ModelCreateRequest();
		model.setName("BlogPost");
		model.setSearchable(true);
		model.setSegmentField("name");
		model.setFields(Arrays.asList(textModelField(), assetModelField()));
		model.setExtension("Post");
		return model;
	}

	public static ModelListResponse modelListResponse() {
		ModelListResponse model = new ModelListResponse();
		model.setMetainfo(pagingInfo());
		model.add(modelResponse());
		model.add(modelResponse());
		return model;
	}

}
