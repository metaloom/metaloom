package io.metaloom.loom.rest.model.example;

import static deprecated.model.model.field.impl.text.TextMarkup.PLAIN;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import deprecated.model.model.field.impl.text.TextModelField;
import io.metaloom.loom.api.json.JsonUtil;
import io.metaloom.loom.rest.model.example.AbstractExamples;

public class ModelFieldModelTest extends AbstractExamples {

	@Test
	public void testModelFields() {
		TextModelField text = new TextModelField()
			.setName("text")
			.setRequired(false)
			.setIndexing(true)
			.setMarkup(PLAIN)
			.setI18N(true);
		String json = JsonUtil.toJson(Arrays.asList(text));
		System.out.println(json);
	}

}
