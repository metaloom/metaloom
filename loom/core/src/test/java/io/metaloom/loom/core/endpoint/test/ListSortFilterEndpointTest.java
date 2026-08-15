package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.filter.LoomFilterKey;
import io.metaloom.loom.api.sort.LoomSortKey;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.error.LoomHttpClientException;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.collection.CollectionCreateRequest;
import io.metaloom.loom.rest.model.collection.CollectionListResponse;
import io.metaloom.loom.rest.model.collection.CollectionResponse;
import io.metaloom.loom.rest.model.user.UserCreateRequest;
import io.metaloom.loom.rest.model.user.UserListResponse;
import io.metaloom.loom.rest.model.user.UserResponse;

/**
 * {@code ?sort=} and {@code ?filter=} over the wire, on the list routes.
 *
 * <p>
 * Both were half-wired, and in ways that only a request could show. The DAO tests in {@code SortAndFilterPagingTest} cover the ordering itself; what
 * this adds is the two layers above it:
 * </p>
 *
 * <ul>
 * <li>{@code LoomLHSFilterParser} has to know a key before the query string can be parsed at all. {@code name}, {@code collection} and {@code uuid}
 * were implemented in the DAOs but never registered, so the branches handling them were unreachable from a request while looking perfectly live in
 * the code.</li>
 * <li>Paging a sorted listing needs {@code ?sort=} and {@code ?from=} to work <em>together</em>. They did not, and no test combined them.</li>
 * </ul>
 */
public class ListSortFilterEndpointTest extends AbstractEndpointTest {

	// ── Sorting ───────────────────────────────────────────────────────────

	/**
	 * Sort by name and walk every page.
	 *
	 * <p>
	 * The second request is the one that used to fail: with a cursor present the sort column became a {@code WHERE} predicate, and it had been
	 * coerced to {@code Field<UUID>}, so Postgres was asked to compare a name against a uuid. That is a 500, and a test that only ever fetched page
	 * one never saw it.
	 * </p>
	 */
	@Test
	public void testSortByNamePagesWithoutError() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			String prefix = uniquePrefix("sorted");
			List<String> expected = new ArrayList<>();
			for (int i = 0; i < 30; i++) {
				// Created in an order that is neither alphabetical nor its reverse, so insertion
				// order cannot pass for sorted output.
				String name = prefix + String.format("%03d", (i * 17) % 30);
				createCollection(client, name);
				expected.add(name);
			}
			Collections.sort(expected);

			List<String> seen = new ArrayList<>();
			UUID cursor = null;
			for (int page = 0; page < 20; page++) {
				CollectionListResponse response = cursor == null
					? client.listCollections().addLimit(10).sortBy(LoomSortKey.NAME)
						.sortDirection(SortDirection.ASCENDING).sync().body()
					: client.listCollections().addLimit(10).addFrom(cursor).sortBy(LoomSortKey.NAME)
						.sortDirection(SortDirection.ASCENDING).sync().body();
				// `data` is omitted rather than empty when a page has nothing on it.
				List<CollectionResponse> data = response.getData();
				if (data == null || data.isEmpty()) {
					break;
				}
				for (CollectionResponse collection : data) {
					if (collection.getName() != null && collection.getName().startsWith(prefix)) {
						seen.add(collection.getName());
					}
				}
				cursor = data.get(data.size() - 1).getUuid();
			}

			assertThat(seen)
				.as("a name-sorted listing, read page by page over HTTP")
				.containsExactlyElementsOf(expected);
		}
	}

	/**
	 * {@code sort=lastname} used to sort by <em>firstname</em>.
	 *
	 * <p>
	 * {@code LoomSortKey.LASTNAME} was declared as {@code LASTNAME("firstname")} — a copy-paste in the enum. Nothing failed: the listing came back
	 * sorted, just by the wrong column, and the API answered exactly as if the request had been honoured.
	 * </p>
	 */
	@Test
	public void testSortByLastnameSortsByLastname() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			String prefix = uniquePrefix("name");
			// firstname and lastname order the users oppositely, so sorting by the wrong column
			// produces the exact reverse rather than something merely different.
			String[][] people = {
				{ "alpha", "zulu" },
				{ "bravo", "yankee" },
				{ "charlie", "xray" },
				{ "delta", "whiskey" },
			};
			for (String[] person : people) {
				UserCreateRequest request = new UserCreateRequest();
				request.setUsername(prefix + person[0]);
				request.setFirstname(person[0]);
				request.setLastname(person[1]);
				client.createUser(request).sync().body();
			}

			UserListResponse response = client.listUsers()
				.addLimit(100)
				.sortBy(LoomSortKey.LASTNAME)
				.sortDirection(SortDirection.ASCENDING)
				.sync().body();

			List<String> lastnames = new ArrayList<>();
			for (UserResponse user : response.getData()) {
				if (user.getUsername() != null && user.getUsername().startsWith(prefix)) {
					lastnames.add(user.getLastname());
				}
			}

			assertThat(lastnames)
				.as("sorted by lastname, not by firstname")
				.containsExactly("whiskey", "xray", "yankee", "zulu");
		}
	}

	/** A sort column the type does not have stays a 400 that names both the field and the type. */
	@Test
	public void testUnknownSortFieldIsRejected() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			LoomHttpClientException ex = Assertions.assertThrows(LoomHttpClientException.class,
				() -> client.listCollections().addLimit(10).sortBy(LoomSortKey.SHA512).sync().body());
			assertEquals(400, ex.getStatusCode());
			assertEquals("Unknown sort field sha512 for Collections", ex.getResponse().getMessage());
		}
	}

	// ── Filtering ─────────────────────────────────────────────────────────

	/**
	 * Filter a listing by the user that created it.
	 *
	 * <p>
	 * Reaching the DAO at all requires {@code creator} to be registered with the filter parser; before that it failed while parsing the query string,
	 * which is why this is an endpoint test and not only a DAO one.
	 * </p>
	 */
	@Test
	public void testFilterCollectionsByCreator() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			String prefix = uniquePrefix("creator");
			// Created by admin over HTTP...
			List<String> mine = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				String name = prefix + "admin-" + i;
				createCollection(client, name);
				mine.add(name);
			}
			// ...and by a second user, directly through the DAO, which is enough to give the rows
			// a different creator_uuid.
			UUID otherUuid = daos().userDao().load(io.metaloom.loom.test.data.TestValues.USER_UUID).getUuid();
			for (int i = 0; i < 4; i++) {
				daos().collectionDao().store(daos().collectionDao().createCollection(otherUuid, prefix + "other-" + i));
			}

			CollectionListResponse response = client.listCollections()
				.addLimit(100)
				.addEquals(LoomFilterKey.CREATOR, adminUuid().toString())
				.sync().body();

			List<String> seen = new ArrayList<>();
			for (CollectionResponse collection : response.getData()) {
				if (collection.getName() != null && collection.getName().startsWith(prefix)) {
					seen.add(collection.getName());
				}
			}
			assertThat(seen)
				.as("only the collections created by the admin")
				.containsExactlyInAnyOrderElementsOf(mine);
		}
	}

	/**
	 * The name filter, which was implemented in three DAOs and unreachable from all of them.
	 *
	 * <p>
	 * Exact match, not a search: the filter grammar has no {@code contains}.
	 * </p>
	 */
	@Test
	public void testFilterCollectionsByName() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			String prefix = uniquePrefix("byname");
			createCollection(client, prefix + "wanted");
			createCollection(client, prefix + "unwanted-1");
			createCollection(client, prefix + "unwanted-2");

			CollectionListResponse response = client.listCollections()
				.addLimit(100)
				.addEquals(LoomFilterKey.NAME, prefix + "wanted")
				.sync().body();

			assertThat(response.getData())
				.singleElement()
				.extracting(CollectionResponse::getName)
				.isEqualTo(prefix + "wanted");
		}
	}

	/** Filtering and sorting are independent clauses and the UI sets both at once, so the combination gets its own case. */
	@Test
	public void testFilterAndSortCombined() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			String prefix = uniquePrefix("combined");
			for (String suffix : List.of("delta", "alpha", "charlie", "bravo")) {
				createCollection(client, prefix + suffix);
			}

			CollectionListResponse response = client.listCollections()
				.addLimit(100)
				.addEquals(LoomFilterKey.CREATOR, adminUuid().toString())
				.sortBy(LoomSortKey.NAME)
				.sortDirection(SortDirection.ASCENDING)
				.sync().body();

			List<String> seen = new ArrayList<>();
			for (CollectionResponse collection : response.getData()) {
				if (collection.getName() != null && collection.getName().startsWith(prefix)) {
					seen.add(collection.getName());
				}
			}
			assertThat(seen).containsExactly(prefix + "alpha", prefix + "bravo", prefix + "charlie", prefix + "delta");
		}
	}

	/** A filter value that has to be a uuid and is not: the caller's mistake, so 400 rather than the 500 an unguarded parse gave. */
	@Test
	public void testMalformedCreatorFilterIsRejected() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			LoomHttpClientException ex = Assertions.assertThrows(LoomHttpClientException.class,
				() -> client.listCollections().addEquals(LoomFilterKey.CREATOR, "not-a-uuid").sync().body());
			assertEquals(400, ex.getStatusCode());
			assertThat(ex.getResponse().getMessage()).contains("expects a uuid");
		}
	}

	// ── Permissions ───────────────────────────────────────────────────────

	/**
	 * Sorting and filtering must not become a way around the permission check on the route.
	 *
	 * <p>
	 * Worth its own case because both are read <em>before</em> the DAO runs; a parameter that threw early enough could plausibly have short-circuited
	 * the {@code checkPerm} wrapper and turned a 403 into a 400.
	 * </p>
	 */
	@Test
	public void testSortAndFilterStillRequireReadPermission() throws LoomClientException {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.listCollections().addLimit(10).sortBy(LoomSortKey.NAME));
			expect(403, "Forbidden", client.listCollections().addLimit(10)
				.addEquals(LoomFilterKey.CREATOR, UUID.randomUUID().toString()));
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────

	/** The pooled database is pre-populated, so every fixture is namespaced and every assertion filtered to it. */
	private String uniquePrefix(String label) {
		return label + "_" + UUID.randomUUID().toString().substring(0, 8) + "_";
	}

	private void createCollection(LoomHttpClient client, String name) throws LoomClientException {
		CollectionCreateRequest request = new CollectionCreateRequest();
		request.setName(name);
		client.createCollection(request).sync().body();
	}
}
