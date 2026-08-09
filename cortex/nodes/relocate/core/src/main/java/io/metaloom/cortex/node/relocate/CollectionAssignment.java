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
	 * {@code collection.name} is UNIQUE, so a name identifies at most one collection and paging through the list is a correct - if unsubtle - lookup.
	 * There is no name filter on the list route yet; when there is, this becomes one call.
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
