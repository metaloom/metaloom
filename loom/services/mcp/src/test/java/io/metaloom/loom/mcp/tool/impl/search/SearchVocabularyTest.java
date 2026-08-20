package io.metaloom.loom.mcp.tool.impl.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.collection.CollectionDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.model.user.UserDao;
import io.metaloom.loom.mcp.tool.impl.search.SearchVocabulary.Match;

/**
 * Name to uuid, which is the half of natural-language search a model cannot do.
 *
 * <p>
 * The tiering is what these tests are really about. "pete" must resolve when "peterson" also exists, or every common first name becomes ambiguous and
 * the feature is useless; and two genuine Petes must <em>not</em> resolve, or the agent silently answers about the wrong person.
 * </p>
 */
public class SearchVocabularyTest {

	private static final UUID PETE = UUID.fromString("11111111-0000-0000-0000-000000000001");
	private static final UUID PETERSON = UUID.fromString("11111111-0000-0000-0000-000000000002");

	private DaoCollection daos;

	private UserDao userDao;

	private CollectionDao collectionDao;

	private SearchVocabulary vocabulary;

	@BeforeEach
	public void setup() {
		daos = mock(DaoCollection.class);
		userDao = mock(UserDao.class);
		collectionDao = mock(CollectionDao.class);
		when(daos.userDao()).thenReturn(userDao);
		when(daos.collectionDao()).thenReturn(collectionDao);
		vocabulary = new SearchVocabulary(daos);
	}

	private static User user(UUID uuid, String username, String first, String last) {
		User user = mock(User.class);
		when(user.getUuid()).thenReturn(uuid);
		when(user.getUsername()).thenReturn(username);
		when(user.getFirstname()).thenReturn(first);
		when(user.getLastname()).thenReturn(last);
		return user;
	}

	private static Collection collection(UUID uuid, String name) {
		Collection collection = mock(Collection.class);
		when(collection.getUuid()).thenReturn(uuid);
		when(collection.getName()).thenReturn(name);
		return collection;
	}

	private void users(User... users) {
		when(userDao.loadByUsername(anyString())).thenReturn(null);
		when(userDao.findAll()).thenAnswer(invocation -> Stream.of(users));
	}

	@Test
	public void testExactUsernameShortCircuitsTheScan() {
		User pete = user(PETE, "pete", "Pete", "Miller");
		when(userDao.loadByUsername("pete")).thenReturn(pete);
		Match match = vocabulary.resolveUser("pete");
		assertEquals(PETE, match.uuid());
		assertTrue(match.label().contains("Pete Miller"));
	}

	@Test
	public void testFirstNameResolvesEvenWhenALongerNameContainsIt() {
		// An exact hit on any alias beats a prefix hit on another row. Without this tiering, "pete"
		// would be ambiguous in every organisation that also employs a Peterson.
		users(user(PETE, "pmiller", "Pete", "Miller"), user(PETERSON, "peterson", "Ann", "Peterson"));
		Match match = vocabulary.resolveUser("pete");
		assertEquals(PETE, match.uuid());
	}

	@Test
	public void testTwoRealMatchesAreAmbiguousRatherThanTheFirstOne() {
		users(user(PETE, "pete.miller", "Pete", "Miller"), user(PETERSON, "pete.novak", "Pete", "Novak"));
		Match match = vocabulary.resolveUser("pete");
		assertFalse(match.isResolved(), "Picking one would be indistinguishable from being right");
		assertTrue(match.isAmbiguous());
		assertEquals(2, match.candidates().size());
	}

	@Test
	public void testFullNameResolves() {
		users(user(PETE, "pmiller", "Pete", "Miller"), user(PETERSON, "apeterson", "Ann", "Peterson"));
		assertEquals(PETE, vocabulary.resolveUser("Pete Miller").uuid());
	}

	@Test
	public void testAUuidIsVerifiedRatherThanTrusted() {
		User pete = user(PETE, "pete", "Pete", "Miller");
		when(userDao.load(PETE)).thenReturn(pete);
		assertEquals(PETE, vocabulary.resolveUser(PETE.toString()).uuid());

		UUID missing = UUID.randomUUID();
		when(userDao.load(missing)).thenReturn(null);
		// A uuid that names nothing is a miss, not a filter that silently matches no rows.
		assertFalse(vocabulary.resolveUser(missing.toString()).isResolved());
	}

	@Test
	public void testNoMatchIsReportedAsSuch() {
		users(user(PETE, "pete", "Pete", "Miller"));
		assertFalse(vocabulary.resolveUser("quentin").isResolved());
		assertFalse(vocabulary.resolveUser("quentin").isAmbiguous());
	}

	@Test
	public void testCollectionMatchingIsCaseInsensitiveAndReturnsTheStoredName() {
		when(collectionDao.findAll()).thenAnswer(invocation -> Stream.of(collection(PETE, "Project XYZ")));
		Match match = vocabulary.resolveCollection("project xyz");
		assertEquals(PETE, match.uuid());
		assertEquals("Project XYZ", match.label(), "The canonical name goes into the report, not the caller's casing");
	}

	@Test
	public void testSubstringIsTheLastResort() {
		when(collectionDao.findAll()).thenAnswer(invocation -> Stream.of(
			collection(PETE, "Summer campaign 2026"),
			collection(PETERSON, "Winter campaign 2026")));
		// "campaign" is contained in both, so it is ambiguous rather than the first row.
		assertTrue(vocabulary.resolveCollection("campaign").isAmbiguous());
		// "summer" is contained in exactly one.
		assertEquals(PETE, vocabulary.resolveCollection("summer").uuid());
	}

	@Test
	public void testAFailingLookupIsAMissRatherThanAnException() {
		// The tool turns a miss into a readable refusal; an exception would surface as a JSON-RPC fault
		// the model cannot act on.
		when(userDao.loadByUsername(anyString())).thenThrow(new IllegalStateException("db down"));
		when(userDao.findAll()).thenThrow(new IllegalStateException("db down"));
		assertFalse(vocabulary.resolveUser("pete").isResolved());
	}

	@Test
	public void testCandidatesAreCapped() {
		User[] many = new User[20];
		for (int i = 0; i < many.length; i++) {
			many[i] = user(UUID.randomUUID(), "pete" + i, "Pete", "Number" + i);
		}
		users(many);
		Match match = vocabulary.resolveUser("pete");
		assertTrue(match.isAmbiguous());
		assertEquals(SearchVocabulary.MAX_CANDIDATES, match.candidates().size(), "A prompt is not a place for twenty alternatives");
	}

	@Test
	public void testEmptyInputResolvesToNothing() {
		users(user(PETE, "pete", "Pete", "Miller"));
		assertFalse(vocabulary.resolveUser("   ").isResolved());
	}

	@Test
	public void testStreamsAreClosed() {
		// findAll() is a jOOQ cursor; leaking one holds a connection out of the pool for the life of the
		// process, and the symptom surfaces somewhere else entirely.
		java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
		when(userDao.loadByUsername(anyString())).thenReturn(null);
		when(userDao.findAll()).thenAnswer(invocation -> Stream.<User>of(user(PETE, "pete", "Pete", "Miller"))
			.onClose(() -> closed.set(true)));
		vocabulary.resolveUser("quentin");
		assertTrue(closed.get(), "The scan must close the stream it opened");
	}

}
