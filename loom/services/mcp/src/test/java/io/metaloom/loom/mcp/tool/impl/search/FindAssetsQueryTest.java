package io.metaloom.loom.mcp.tool.impl.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.api.search.SearchMode;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchSortMode;
import io.metaloom.loom.mcp.tool.impl.search.FindAssetsQuery.Plan;
import io.metaloom.loom.mcp.tool.impl.search.SearchVocabulary.Match;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The bounded filter object.
 *
 * <p>
 * Two properties carry the whole design and are pinned hardest here. An <b>unknown key is refused</b>, because the alternative - accepting it and
 * ignoring it - produces a search the caller believes was narrowed and was not; the tree already shipped that bug once in {@code search_assets}. And an
 * <b>unresolvable name is refused</b>, because running the query without that clause answers a different question with total confidence.
 * </p>
 */
public class FindAssetsQueryTest {

	private static final UUID PETE = UUID.fromString("11111111-0000-0000-0000-000000000001");
	private static final UUID SPACE = UUID.fromString("22222222-0000-0000-0000-000000000001");
	private static final Instant NOW = Instant.parse("2026-08-19T10:15:00Z");

	private SearchVocabulary vocabulary;

	private SearchOptions options;

	@BeforeEach
	public void setup() {
		vocabulary = mock(SearchVocabulary.class);
		options = new SearchOptions();
	}

	private Plan build(JsonObject args) {
		return FindAssetsQuery.build(args, vocabulary, options, NOW);
	}

	// --- the worked example -----------------------------------------------------------------------

	@Test
	public void testTheWorkedExample() {
		when(vocabulary.resolveUser("pete")).thenReturn(Match.of(PETE, "Pete Miller (pete)"));
		when(vocabulary.resolveSpace("project xyz")).thenReturn(Match.of(SPACE, "Project XYZ"));

		Plan plan = build(new JsonObject()
			.put("creator", "pete")
			.put("when", "today or yesterday")
			.put("space", "project xyz")
			.put("timezone", "Europe/Vienna"));

		assertFalse(plan.isError(), plan.error());
		SearchRequest request = plan.request();
		assertEquals(PETE, request.getCreatorUuid());
		assertEquals(SPACE, request.getSpaceUuid());
		assertEquals(Instant.parse("2026-08-17T22:00:00Z"), request.getCreatedFrom());
		// No search term at all - the sentence has none, and demanding one would force the caller to
		// invent a word. Ordering falls back to newest, because there is nothing to rank against.
		assertNull(request.getQuery());
		assertEquals(SearchSortMode.NEWEST, request.getSort());
		assertTrue(plan.applied().stream().anyMatch(entry -> entry.contains("Pete Miller")),
			"The report must name what was resolved, not echo what was asked: " + plan.applied());
	}

	// --- the closed key set -----------------------------------------------------------------------

	@Test
	public void testUnknownKeyIsRefusedAndNamesTheAlternatives() {
		Plan plan = build(new JsonObject().put("text", "harbour").put("uploader", "pete"));
		assertTrue(plan.isError());
		assertTrue(plan.error().contains("uploader"), plan.error());
		assertTrue(plan.error().contains("creator"), "The refusal has to say what to use instead: " + plan.error());
		assertNull(plan.request());
	}

	@Test
	public void testUnknownKeysAreAllListedAtOnce() {
		Plan plan = build(new JsonObject().put("text", "x").put("author", "a").put("since", "b"));
		assertTrue(plan.error().contains("author"));
		assertTrue(plan.error().contains("since"), "One round trip should be enough to fix every mistake: " + plan.error());
	}

	// --- resolution failures are refusals, not silent widenings -----------------------------------

	@Test
	public void testUnknownCreatorRefusesRatherThanSearchingEveryone() {
		when(vocabulary.resolveUser("pete")).thenReturn(Match.none());
		Plan plan = build(new JsonObject().put("creator", "pete"));
		assertTrue(plan.isError());
		assertTrue(plan.error().contains("pete"));
		assertTrue(plan.error().toLowerCase().contains("not run"), plan.error());
	}

	@Test
	public void testAmbiguousCreatorReportsTheCandidates() {
		when(vocabulary.resolveUser("pete")).thenReturn(Match.ambiguous(List.of("pete.miller", "pete.novak")));
		Plan plan = build(new JsonObject().put("creator", "pete"));
		assertTrue(plan.isError());
		assertTrue(plan.error().contains("pete.miller"));
		assertTrue(plan.error().contains("pete.novak"), "Both have to be offered or the model cannot ask a useful question");
	}

	@Test
	public void testUnknownTagRefuses() {
		when(vocabulary.resolveTag("approvd")).thenReturn(Match.none());
		Plan plan = build(new JsonObject().put("tags", new JsonArray().add("approvd")));
		assertTrue(plan.isError());
		assertTrue(plan.error().contains("approvd"));
	}

	@Test
	public void testTagsAreCanonicalisedToTheStoredName() {
		when(vocabulary.resolveTag("Approved")).thenReturn(Match.of(UUID.randomUUID(), "approved"));
		Plan plan = build(new JsonObject().put("tags", new JsonArray().add("Approved")));
		assertFalse(plan.isError(), plan.error());
		assertEquals(List.of("approved"), plan.request().getTags());
	}

	@Test
	public void testASingleTagStringIsAcceptedForTheArray() {
		when(vocabulary.resolveTag("approved")).thenReturn(Match.of(UUID.randomUUID(), "approved"));
		Plan plan = build(new JsonObject().put("tags", "approved"));
		assertFalse(plan.isError(), plan.error());
		assertEquals(List.of("approved"), plan.request().getTags());
	}

	// --- dates ------------------------------------------------------------------------------------

	@Test
	public void testUnreadableDateIsRefusedWithTheVocabulary() {
		Plan plan = build(new JsonObject().put("when", "around easter"));
		assertTrue(plan.isError());
		assertTrue(plan.error().contains("today"), "The refusal must list what is accepted: " + plan.error());
	}

	@Test
	public void testWhenAndTheBoundsAreMutuallyExclusive() {
		Plan plan = build(new JsonObject().put("when", "today").put("createdFrom", "yesterday"));
		assertTrue(plan.isError());
	}

	@Test
	public void testBoundsTakeTheOuterEdgeOfEachPeriod() {
		Plan plan = build(new JsonObject().put("createdFrom", "2026-08-01").put("createdTo", "2026-08-18").put("timezone", "UTC"));
		assertFalse(plan.isError(), plan.error());
		assertEquals(Instant.parse("2026-08-01T00:00:00Z"), plan.request().getCreatedFrom());
		// Inclusive of the 18th: the caller means the whole day, not its first instant.
		assertEquals(Instant.parse("2026-08-18T23:59:59.999Z"), plan.request().getCreatedTo());
	}

	@Test
	public void testInvertedRangeIsRefused() {
		Plan plan = build(new JsonObject().put("createdFrom", "2026-08-18").put("createdTo", "2026-08-01"));
		assertTrue(plan.isError());
		assertTrue(plan.error().contains("inverted"));
	}

	@Test
	public void testUnknownTimezoneIsRefused() {
		Plan plan = build(new JsonObject().put("when", "today").put("timezone", "Mars/Olympus"));
		assertTrue(plan.isError());
		assertTrue(plan.error().contains("Mars/Olympus"));
	}

	// --- mime, types, sort, mode ------------------------------------------------------------------

	@Test
	public void testMimeWildcardIsNormalisedRatherThanMatchingNothing() {
		Plan plan = build(new JsonObject().put("mimeType", "video/*"));
		assertFalse(plan.isError(), plan.error());
		assertEquals("video/", plan.request().getMimeTypePrefix());
	}

	@Test
	public void testTypeDefaultsToAssetOnly() {
		Plan plan = build(new JsonObject().put("text", "harbour"));
		assertEquals(java.util.Set.of(SearchEntityType.ASSET), plan.request().getTypes());
	}

	@Test
	public void testTranscriptTypeIsAccepted() {
		Plan plan = build(new JsonObject().put("text", "harbour").put("types", new JsonArray().add("transcript")));
		assertFalse(plan.isError(), plan.error());
		assertTrue(plan.request().getTypes().contains(SearchEntityType.TRANSCRIPT));
	}

	@Test
	public void testOtherEntityTypesAreRefusedWithAnExplanation() {
		Plan plan = build(new JsonObject().put("text", "x").put("types", new JsonArray().add("person")));
		assertTrue(plan.isError());
		assertTrue(plan.error().contains("person"));
	}

	@Test
	public void testUnknownSortIsRefused() {
		Plan plan = build(new JsonObject().put("text", "x").put("sort", "BEST"));
		assertTrue(plan.isError());
		assertTrue(plan.error().contains("NEWEST"));
	}

	@Test
	public void testRelevanceSortSurvivesWhenThereIsATerm() {
		Plan plan = build(new JsonObject().put("text", "harbour"));
		assertEquals(SearchSortMode.RELEVANCE, plan.request().getSort());
	}

	@Test
	public void testSemanticModeWithoutATextIsRefused() {
		// Nothing to embed. Refusing here beats a 400 from the provider, which the model reads as a fault.
		Plan plan = build(new JsonObject().put("mimeType", "image/").put("mode", "SEMANTIC"));
		assertTrue(plan.isError());
		assertTrue(plan.error().contains("text"));
	}

	@Test
	public void testHybridModeWithATextIsAccepted() {
		Plan plan = build(new JsonObject().put("text", "harbour").put("mode", "hybrid"));
		assertFalse(plan.isError(), plan.error());
		assertEquals(SearchMode.HYBRID, plan.request().getMode());
	}

	// --- paging -----------------------------------------------------------------------------------

	@Test
	public void testLimitIsClampedToTheConfiguredMaximum() {
		options.setMaxLimit(50);
		Plan plan = build(new JsonObject().put("text", "x").put("limit", 5000));
		assertEquals(50, plan.request().getLimit());
	}

	@Test
	public void testDeepPagingIsRefusedBeforeTheProviderSeesIt() {
		options.setMaxOffset(1000);
		Plan plan = build(new JsonObject().put("text", "x").put("offset", 5000));
		assertTrue(plan.isError());
		assertTrue(plan.error().contains("1000"));
	}

	@Test
	public void testNegativeLimitIsRefused() {
		assertTrue(build(new JsonObject().put("text", "x").put("limit", 0)).isError());
		assertTrue(build(new JsonObject().put("text", "x").put("offset", -1)).isError());
	}

	// --- the empty call ---------------------------------------------------------------------------

	@Test
	public void testAnEmptyCallIsRefusedAndNothingIsResolved() {
		Plan plan = build(new JsonObject());
		assertTrue(plan.isError());
		assertTrue(plan.error().contains("creator"), "The refusal doubles as the hint: " + plan.error());
		verify(vocabulary, never()).resolveUser(anyString());
	}

	@Test
	public void testTypesSortAndModeDoNotCountAsNarrowing() {
		// These select or order; they do not restrict. Treating them as a narrowing would let a termless
		// call through and page the entire catalogue - which is exactly what a first cut of this did,
		// because SearchEndpointService populates types on every REST call with everything the caller
		// may read.
		assertTrue(build(new JsonObject().put("types", new JsonArray().add("asset"))).isError());
		assertTrue(build(new JsonObject().put("sort", "NEWEST")).isError());
		assertTrue(build(new JsonObject().put("types", new JsonArray().add("asset")).put("sort", "NEWEST")).isError());
	}

	@Test
	public void testASingleRealFilterIsEnoughWithoutAnyText() {
		Plan plan = build(new JsonObject().put("mimeType", "image/"));
		assertFalse(plan.isError(), plan.error());
		assertNull(plan.request().getQuery());
	}

	@Test
	public void testAnOverlongTextIsRefused() {
		Plan plan = build(new JsonObject().put("text", "x".repeat(SearchRequest.MAX_QUERY_LENGTH + 1)));
		assertTrue(plan.isError());
	}

}
