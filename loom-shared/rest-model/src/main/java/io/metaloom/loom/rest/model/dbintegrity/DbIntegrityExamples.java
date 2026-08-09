package io.metaloom.loom.rest.model.dbintegrity;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * OpenAPI examples for the database integrity routes.
 *
 * <p>
 * The codes and descriptions here are real registered checks, not invented ones - an example naming
 * a check that does not exist would send a reader looking for it.
 * </p>
 */
public interface DbIntegrityExamples extends ExampleValues {

	default Example dbIntegrityReportExample() {
		return new ExampleImpl(dbIntegrityReport(), "A database integrity report", HttpResponseStatus.OK);
	}

	default Example dbIntegrityCheckListExample() {
		return new ExampleImpl(dbIntegrityCheckList(), "The integrity check catalogue", HttpResponseStatus.OK);
	}

	default DbIntegrityReportResponse dbIntegrityReport() {
		DbIntegrityCheckResultModel dangling = new DbIntegrityCheckResultModel()
			.setCheck(danglingSearchDocument())
			.setCount(2)
			.setDurationMs(4);
		dangling.getSamples().add("6c1f7b1e-0d0a-4b3a-9f7c-2f1d3c4b5a60 (entity_type=asset)");
		dangling.getSamples().add("8a2e9c4d-77b1-42aa-9d31-5b6c7d8e9f01 (entity_type=asset)");

		return new DbIntegrityReportResponse()
			.setTimestamp("2026-08-09T11:24:07Z")
			.setDurationMs(312)
			.setChecksRun(29)
			.setFindingCount(2)
			.setClean(false)
			.add(dangling)
			.add(new DbIntegrityCheckResultModel()
				.setCheck(editedBeforeCreated())
				.setCount(0)
				.setDurationMs(7));
	}

	default DbIntegrityCheckListResponse dbIntegrityCheckList() {
		return new DbIntegrityCheckListResponse()
			.add(danglingSearchDocument())
			.add(editedBeforeCreated());
	}

	private static DbIntegrityCheckModel danglingSearchDocument() {
		return new DbIntegrityCheckModel()
			.setCode("DANGLING_SEARCH_DOCUMENT")
			.setCategory("DANGLING")
			.setSeverity("ERROR")
			.setTable("search_document")
			.setColumn("entity_uuid")
			.setDescription("A search document points at an entity that no longer exists, so a search"
				+ " can return a hit that resolves to nothing.");
	}

	private static DbIntegrityCheckModel editedBeforeCreated() {
		return new DbIntegrityCheckModel()
			.setCode("TIMESTAMP_EDITED_BEFORE_CREATED")
			.setCategory("TIMESTAMP")
			.setSeverity("ERROR")
			.setTable("(every audited table)")
			.setColumn("edited")
			.setDescription("A row was edited before it was created.");
	}
}
