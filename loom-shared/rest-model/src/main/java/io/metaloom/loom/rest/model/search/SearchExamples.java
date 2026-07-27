package io.metaloom.loom.rest.model.search;

import java.util.List;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * OpenAPI examples for the search routes.
 */
public interface SearchExamples extends ExampleValues {

	default Example searchResultResponseExample() {
		return new ExampleImpl(searchResultResponse(), "The search result response", HttpResponseStatus.OK);
	}

	default Example searchSuggestionListResponseExample() {
		return new ExampleImpl(searchSuggestionListResponse(), "The search suggestion response", HttpResponseStatus.OK);
	}

	default Example searchStatusResponseExample() {
		return new ExampleImpl(searchStatusResponse(), "The search status response", HttpResponseStatus.OK);
	}

	default SearchResultResponse searchResultResponse() {
		SearchResultResponse model = new SearchResultResponse();
		model.add(new SearchHitResponse()
			.setType("asset")
			.setUuid(uuidA())
			.setAssetUuid(uuidA())
			.setScore(0.87d)
			.setTitle("aurora_timelapse.mp4")
			.setSubtitle("/mnt/media/2026/aurora_timelapse.mp4")
			.setMatchedIn("title")
			.setMimeType("video/mp4")
			.setSize(184_320_000L)
			.setHighlights(List.of("<b>aurora</b> timelapse")));
		model.add(new SearchHitResponse()
			.setType("transcript")
			.setUuid(uuidB())
			.setAssetUuid(uuidA())
			.setScore(0.41d)
			.setTitle("aurora_timelapse.mp4")
			.setSubtitle("en whisper-large-v3")
			.setMatchedIn("body")
			.setTimeFromMs(0L)
			.setHighlights(List.of("the northern lights above <b>aurora</b> bay")));
		model.setMetainfo(new SearchMetaInfo()
			.setTotalHits(2)
			.setTotalExact(true)
			.setPerPage(25)
			.setOffset(0)
			.setTookMs(12)
			.setProvider("postgres")
			.setCapabilities(List.of("LEXICAL", "PHRASE", "FUZZY", "HIGHLIGHT", "FACETS", "EXACT_TOTAL", "SUGGEST")));
		return model;
	}

	default SearchSuggestionListResponse searchSuggestionListResponse() {
		SearchSuggestionListResponse model = new SearchSuggestionListResponse();
		model.setMetainfo(pagingInfo());
		model.add(new SearchSuggestionResponse().setText("aurora_timelapse.mp4").setType("asset").setUuid(uuidA()).setScore(0.62d));
		model.add(new SearchSuggestionResponse().setText("aurora").setType("tag").setUuid(uuidB()).setScore(0.55d));
		return model;
	}

	default SearchStatusResponse searchStatusResponse() {
		return new SearchStatusResponse()
			.setProvider("postgres")
			.setAvailable(true)
			.setCapabilities(List.of("LEXICAL", "PHRASE", "FUZZY", "HIGHLIGHT", "FACETS", "EXACT_TOTAL", "SUGGEST"))
			.setDocumentCount(12_483L)
			.setDirtyCount(0L);
	}
}
