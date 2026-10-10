package io.metaloom.cortex.node.relocate;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.collection.CollectionCreateRequest;
import io.metaloom.loom.rest.model.collection.CollectionListResponse;
import io.metaloom.loom.rest.model.collection.CollectionResponse;

/**
 * Membership in a collection.
 */
@Singleton
public class CollectionAssignment implements AssignDestination {

	@Inject
	public CollectionAssignment() {
	}

	@Override
	public AssignTarget target() {
		return AssignTarget.COLLECTION;
	}

	@Override
	public List<String> validate(AssignNodeOptions options) {
		boolean hasUuid = !PoolDestination.isBlank(options.getCollectionUuid());
		boolean hasName = !PoolDestination.isBlank(options.getCollectionName());
		if (hasUuid == hasName) {
			// Both or neither. Accepting both would mean silently preferring one, and a pipeline whose two
			// fields disagree is a mistake worth surfacing at configure time.
			return List.of("the COLLECTION target needs exactly one of 'collectionUuid' or 'collectionName'");
		}
		if (hasUuid) {
			try {
				UUID.fromString(options.getCollectionUuid().trim());
			} catch (IllegalArgumentException e) {
				return List.of("collectionUuid is not a valid uuid: " + options.getCollectionUuid());
			}
		}
		return List.of();
	}

	@Override
	public UUID resolve(LoomClient client, AssignNodeOptions options) throws Exception {
		if (!PoolDestination.isBlank(options.getCollectionUuid())) {
			UUID uuid = UUID.fromString(options.getCollectionUuid().trim());
			CollectionResponse collection = client.loadCollection(uuid).sync().body();
			return collection == null ? null : collection.getUuid();
		}

		String name = options.getCollectionName().trim();
		UUID existing = findByName(client, name);
		if (existing != null) {
			return existing;
		}
		if (OnMissing.parse(options.getOnMissing()) != OnMissing.CREATE) {
			return null;
		}
		CollectionResponse created = client.createCollection(new CollectionCreateRequest().setName(name)).sync().body();
		return created == null ? null : created.getUuid();
	}

	/**
	 * Resolve by name, the way the tag node resolves a tag.
	 *
	 * <p>
	 * {@code collection.name} is UNIQUE, so a name identifies at most one collection and scanning the list is a correct - if unsubtle - lookup.
	 * There is no name filter on the list route yet, and {@code listCollections()} takes no paging parameters either, so only the first page can be
	 * searched.
	 * </p>
	 *
	 * <p>
	 * 🔴 A miss on a <b>truncated</b> listing is therefore not evidence that the collection does not exist, and it must not be reported as one: with
	 * {@code onMissing: CREATE} that answer makes the node try to create a name that already exists, and with {@code FAIL} it blames the pipeline for a
	 * limitation of this lookup. Saying which it is costs one comparison against the total the server reported.
	 * </p>
	 */
	private UUID findByName(LoomClient client, String name) throws Exception {
		CollectionListResponse page = client.listCollections().sync().body();
		if (page == null || page.getData() == null) {
			return null;
		}
		for (CollectionResponse collection : page.getData()) {
			if (name.equals(collection.getName())) {
				return collection.getUuid();
			}
		}
		long total = page.getMetainfo() == null ? 0 : page.getMetainfo().getTotalCount();
		if (total > page.getData().size()) {
			throw new IllegalStateException("Collection '" + name + "' was not on the first page of " + page.getData().size() + " of " + total
				+ " collections, and the list route cannot be paged. Configure 'collectionUuid' instead of 'collectionName'.");
		}
		return null;
	}

	@Override
	public boolean isMember(LoomClient client, UUID targetUuid, UUID assetUuid) throws Exception {
		CollectionListResponse page = client.listAssetCollections(assetUuid).sync().body();
		if (page == null || page.getData() == null) {
			return false;
		}
		return page.getData().stream().anyMatch(c -> targetUuid.equals(c.getUuid()));
	}

	@Override
	public void link(LoomClient client, UUID targetUuid, UUID assetUuid) throws Exception {
		client.addCollectionAsset(targetUuid, assetUuid).sync();
	}

	@Override
	public String table() {
		return "collection";
	}

	@Override
	public String describe(AssignNodeOptions options) {
		return PoolDestination.isBlank(options.getCollectionName())
			? "collection " + options.getCollectionUuid()
			: "collection '" + options.getCollectionName() + "'";
	}
}
