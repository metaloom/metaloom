package io.metaloom.loom.rest.model.example;

import static io.metaloom.loom.rest.model.example.ContentExamples.tagReferenceList;

import java.util.Arrays;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import deprecated.content.field.asset.AssetContentField;
import deprecated.content.field.asset.AssetListContentField;
import deprecated.content.field.date.DateContentField;
import deprecated.content.field.date.DateListContentField;
import deprecated.content.field.json.JsonContentField;
import deprecated.content.field.json.JsonListContentField;
import deprecated.content.field.nested.NestedContentField;
import deprecated.content.field.nested.NestedListContentField;
import deprecated.content.field.number.NumberContentField;
import deprecated.content.field.number.NumberListContentField;
import deprecated.content.field.reference.ReferenceContentField;
import deprecated.content.field.reference.ReferenceListContentField;
import deprecated.content.field.text.TextContentField;
import deprecated.content.field.text.TextListContentField;
import io.metaloom.loom.rest.model.asset.AssetS3Meta;

public class ContentFieldExamples extends AbstractExamples {

	public static AssetContentField assetField() {
		return assetField("asset", "bigbuckbunny-4k.mp4");
	}

	public static AssetContentField assetField(String name, String filename) {
		AssetContentField asset = new AssetContentField();
		asset.setName(name)
			.setFilename(filename)
			.setDominantColor("#FFFF00")
			.setSize(2005225)
			.setWidth(4000)
			.setHeight(2250)
			.setHashes(assetHashes())
			.setLocalPath("/opt/movies/bigbuckbunny-4k.mp4")
			.setS3(new AssetS3Meta().setBucket("big_bucket").setKey("themovie"))
			.setMeta(meta())
			.setMimeType("video/mp4")
			.setLocation(assetGeoLocation())
			.setTags(tagReferenceList())
			.setTimeline(assetAnnotations());
		return asset;
	}

	public static AssetListContentField assetListField() {
		AssetListContentField field = new AssetListContentField();
		field.setName("assetList");
		field.addField(assetField());
		return field;
	}

	public static TextContentField textField(String name, String value) {
		TextContentField field = new TextContentField();
		field.setName(name);
		field.setValue(value);
		return field;
	}

	public static TextListContentField textListField() {
		TextListContentField field = new TextListContentField();
		field.setName("textList");
		return field;
	}

	public static DateContentField dateField() {
		DateContentField field = new DateContentField();
		field.setName("date");
		field.setValue("2021-02-12T19:13:06.024Z");
		return field;
	}

	public static DateListContentField dateListField() {
		DateListContentField field = new DateListContentField();
		field.setName("dateList");
		field.addDate("2021-02-12T19:13:06.024Z");
		field.addDate("2021-01-12T16:11:04.024Z");
		return field;
	}

	public static NumberContentField numberField() {
		NumberContentField field = new NumberContentField();
		field.setName("number");
		field.setValue(Long.MAX_VALUE);
		return field;
	}

	public static NumberListContentField numberListField() {
		NumberListContentField field = new NumberListContentField();
		field.setName("numberList");
		field.addNumber(Long.MAX_VALUE);
		field.addNumber(Double.MAX_VALUE);
		return field;
	}
	
	
	public static ReferenceContentField referenceField() {
		ReferenceContentField field = new ReferenceContentField();
		field.setName("reference");
		field.setUuid(uuidA());
		return field;
	}

	public static ReferenceListContentField referenceListField() {
		ReferenceListContentField field = new ReferenceListContentField();
		field.setName("referenceList");
		field.addReference(uuidA());
		field.addReference(uuidB());
		return field;
	}
	

	public static NestedContentField nestedField() {
		NestedContentField innerField = new NestedContentField();
		innerField.setName("inner-content");
		innerField.setFields(Arrays.asList(textField("text", "Inner value")));

		NestedContentField field = new NestedContentField();
		field.setName("nested");
		field.setFields(Arrays.asList(textField("text", "ValueA"), innerField));
		return field;
	}

	public static NestedListContentField nestedListField() {
		NestedListContentField field = new NestedListContentField();
		field.setName("nestedList");
		field.addFields(Arrays.asList(nestedField()));
		field.addFields(Arrays.asList(nestedField()));
		return field;
	}

	public static JsonContentField jsonField() {
		JsonContentField field = new JsonContentField();
		field.setName("json");
		try {
			JsonNode json = new ObjectMapper().readTree("{ \"a\": \"b\" }");
			field.setJson(json);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
		return field;
	}

	public static JsonListContentField jsonListField() {
		JsonListContentField field = new JsonListContentField();
		field.setName("json");
		try {
			JsonNode json = new ObjectMapper().readTree("{ \"a\": \"b\" }");
			field.addItem(json);
			field.addItem(json);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
		return field;
	}
}
