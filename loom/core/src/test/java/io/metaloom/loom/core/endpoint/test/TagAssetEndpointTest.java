package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.model.tag.AssetTagBulkRequest;
import io.metaloom.loom.rest.model.tag.AssetTagBulkResponse;
import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.metaloom.loom.rest.model.tag.TagReference;
import io.metaloom.loom.rest.model.tag.TagResponse;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

public class TagAssetEndpointTest extends AbstractEndpointTest {

	@Test
	public void testTagAsset() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TagCreateRequest request = new TagCreateRequest();
			request.setName("red2");
			request.setCollection("colors");
			request.setMeta(new JsonObject().put("hello", "world"));
			TagResponse tag = client.tagAsset(ASSET_UUID, request).sync().body();
			assertThat(tag).isValid();

			AssetResponse asset = client.loadAsset(ASSET_UUID).sync().body();
			assertThat(asset).isValid();

			client.untagAsset(ASSET_UUID, tag.getUuid()).sync().body();

			AssetResponse asset2 = client.loadAsset(ASSET_UUID).sync().body();
			assertThat(asset2).isValid();

			TagResponse tag2 = client.loadTag(tag.getUuid()).sync().body();
			assertThat(tag2).isValid();
		}
	}

	/**
	 * Tags are global: <code>tag</code> is <code>UNIQUE (name, collection)</code>. Tagging a second asset with a name that is already in use must
	 * therefore attach the tag that exists rather than insert a second row - without this, no caller can tag two assets alike, which is the ordinary
	 * case for a human tagging a selection and the entire premise of an auto-tagging node.
	 */
	@Test
	public void testTagTwoAssetsWithOneTagName() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TagCreateRequest request = new TagCreateRequest();
			request.setName("shared-tag");
			request.setCollection("quality");

			TagResponse first = client.tagAsset(ASSET_UUID, request).sync().body();
			assertThat(first).isValid();

			UUID secondAsset = createAsset(client, "b1", "tag-b1.png");
			TagResponse second = client.tagAsset(secondAsset, request).sync().body();
			assertThat(second).isValid();

			org.assertj.core.api.Assertions.assertThat(second.getUuid())
				.as("Both assets must carry the same tag row").isEqualTo(first.getUuid());

			// Both assets list it, and the tag itself is still a single object.
			assertTagged(client, ASSET_UUID, first.getUuid());
			assertTagged(client, secondAsset, first.getUuid());
			org.assertj.core.api.Assertions.assertThat(client.listTags().sync().body().getData().stream()
				.filter(t -> "shared-tag".equals(t.getName()) && "quality".equals(t.getCollection()))
				.count())
				.as("Only one tag may exist for one (name, collection)").isEqualTo(1);
		}
	}

	/**
	 * The join is keyed <code>(tag_uuid, asset_uuid)</code>, so re-tagging an asset with the same tag - a pipeline running a second time - must be a
	 * no-op rather than a constraint violation.
	 */
	@Test
	public void testTagSameAssetTwice() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TagCreateRequest request = new TagCreateRequest();
			request.setName("repeat-tag");
			request.setCollection("quality");

			TagResponse first = client.tagAsset(ASSET_UUID, request).sync().body();
			TagResponse again = client.tagAsset(ASSET_UUID, request).sync().body();
			org.assertj.core.api.Assertions.assertThat(again.getUuid()).isEqualTo(first.getUuid());

			AssetResponse asset = client.loadAsset(ASSET_UUID).sync().body();
			org.assertj.core.api.Assertions.assertThat(asset.getTags().stream()
				.filter(t -> t.getUuid().equals(first.getUuid()))
				.count())
				.as("The tag must be attached exactly once").isEqualTo(1);
		}
	}

	/**
	 * Resolving an existing tag must not rewrite it. A bare tagging call - a name and a collection, which is all a worker sends - keeps the meta a
	 * human curated on the shared tag.
	 */
	@Test
	public void testTaggingDoesNotOverwriteAnExistingTag() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TagCreateRequest curated = new TagCreateRequest();
			curated.setName("curated-tag");
			curated.setCollection("quality");
			curated.setMeta(new JsonObject().put("note", "hand written"));
			TagResponse created = client.tagAsset(ASSET_UUID, curated).sync().body();

			TagCreateRequest bare = new TagCreateRequest();
			bare.setName("curated-tag");
			bare.setCollection("quality");
			UUID secondAsset = createAsset(client, "b3", "tag-b3.png");
			TagResponse resolved = client.tagAsset(secondAsset, bare).sync().body();

			org.assertj.core.api.Assertions.assertThat(resolved.getUuid()).isEqualTo(created.getUuid());
			org.assertj.core.api.Assertions.assertThat(resolved.getMeta())
				.as("The response must report the persisted tag").isNotNull();
			org.assertj.core.api.Assertions.assertThat(resolved.getMeta().getString("note")).isEqualTo("hand written");

			TagResponse reloaded = client.loadTag(created.getUuid()).sync().body();
			org.assertj.core.api.Assertions.assertThat(reloaded.getMeta().getString("note"))
				.as("The curated meta must survive a bare tagging call").isEqualTo("hand written");
		}
	}

	/**
	 * The scale route: a whole set of tags in one request, one transaction. Tagging one asset per call is what a library cannot afford - five tags
	 * over a hundred thousand assets is half a million requests.
	 */
	@Test
	public void testBulkTagAsset() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			AssetTagBulkRequest request = new AssetTagBulkRequest()
				.setCollection("quality")
				.add(new TagCreateRequest().setName("bulk-blurry"))
				.add(new TagCreateRequest().setName("bulk-dark"))
				// An entry may override the request-level collection.
				.add(new TagCreateRequest().setName("bulk-amber").setCollection("colors"));

			AssetTagBulkResponse response = client.bulkTagAsset(ASSET_UUID, request).sync().body();

			org.assertj.core.api.Assertions.assertThat(response.getTotal()).isEqualTo(3);
			org.assertj.core.api.Assertions.assertThat(response.getApplied()).isEqualTo(3);
			org.assertj.core.api.Assertions.assertThat(response.getWithdrawn()).isZero();
			org.assertj.core.api.Assertions.assertThat(response.getTags())
				.as("The response must report the persisted tags, uuids included").hasSize(3);
			response.getTags().forEach(tag -> org.assertj.core.api.Assertions.assertThat(tag.getUuid()).isNotNull());

			// The request-level collection is the default, and an entry may still name its own.
			org.assertj.core.api.Assertions.assertThat(response.getTags().stream()
				.filter(t -> "bulk-amber".equals(t.getName()))
				.findFirst().orElseThrow().getCollection()).isEqualTo("colors");

			AssetResponse asset = client.loadAsset(ASSET_UUID).sync().body();
			for (TagResponse tag : response.getTags()) {
				assertTagged(client, ASSET_UUID, tag.getUuid());
			}
			assertThat(asset).isValid();

			// Re-sending the same set is a no-op, which is what a pipeline running twice does.
			AssetTagBulkResponse again = client.bulkTagAsset(ASSET_UUID, request).sync().body();
			org.assertj.core.api.Assertions.assertThat(again.getApplied()).isEqualTo(3);
			org.assertj.core.api.Assertions.assertThat(client.loadAsset(ASSET_UUID).sync().body().getTags().stream()
				.filter(t -> t.getName().startsWith("bulk-"))
				.count())
				.as("The second call must not duplicate the attachments").isEqualTo(3);
		}
	}

	/**
	 * Withdrawal removes exactly the attachments the caller names - never "everything not in the set". Until {@code tag_asset} records who wrote a
	 * row, the server cannot tell a worker's tag from a person's, and a desired-set delete would destroy human curation.
	 */
	@Test
	public void testBulkTagAssetWithdrawsOnlyWhatItNames() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TagResponse curated = client.tagAsset(ASSET_UUID,
				new TagCreateRequest().setName("hand-typed").setCollection("curated")).sync().body();
			AssetTagBulkResponse first = client.bulkTagAsset(ASSET_UUID, new AssetTagBulkRequest()
				.setCollection("quality")
				.add(new TagCreateRequest().setName("withdraw-me"))
				.add(new TagCreateRequest().setName("keep-me"))).sync().body();

			UUID doomed = first.getTags().stream()
				.filter(t -> "withdraw-me".equals(t.getName())).findFirst().orElseThrow().getUuid();

			AssetTagBulkResponse second = client.bulkTagAsset(ASSET_UUID, new AssetTagBulkRequest()
				.setCollection("quality")
				.add(new TagCreateRequest().setName("keep-me"))
				.withdraw(doomed)).sync().body();

			org.assertj.core.api.Assertions.assertThat(second.getWithdrawn()).isEqualTo(1);

			AssetResponse asset = client.loadAsset(ASSET_UUID).sync().body();
			org.assertj.core.api.Assertions.assertThat(asset.getTags().stream().anyMatch(t -> t.getUuid().equals(doomed)))
				.as("The named tag must be detached").isFalse();
			assertTagged(client, ASSET_UUID, curated.getUuid());

			org.assertj.core.api.Assertions.assertThat(client.loadTag(doomed).sync().body())
				.as("Withdrawing detaches the tag; the tag itself must survive").isNotNull();
		}
	}

	/**
	 * 🔴 The reason for V2.71: one tag, two faces, one photo. Until the join row had its own identity a
	 * tag could be placed on an asset exactly once, which made this - the ordinary output of face
	 * detection and clustering - impossible to express.
	 */
	@Test
	public void testTagTwoFacesOfOneAssetWithOneTag() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TagResponse left = client.tagAsset(ASSET_UUID, new TagCreateRequest()
				.setName("anna").setCollection("people")
				.setArea(new AreaInfo().setStartX(10).setStartY(10).setWidth(50).setHeight(50))).sync().body();
			TagResponse right = client.tagAsset(ASSET_UUID, new TagCreateRequest()
				.setName("anna").setCollection("people")
				.setArea(new AreaInfo().setStartX(400).setStartY(120).setWidth(60).setHeight(60))).sync().body();

			org.assertj.core.api.Assertions.assertThat(right.getUuid())
				.as("Two placements of one name are still one tag").isEqualTo(left.getUuid());

			List<TagReference> placements = client.loadAsset(ASSET_UUID).sync().body().getTags().stream()
				.filter(t -> "anna".equals(t.getName()))
				.toList();
			org.assertj.core.api.Assertions.assertThat(placements).as("Both faces must be tagged").hasSize(2);
			org.assertj.core.api.Assertions.assertThat(placements.stream().map(TagReference::getPlacementUuid).distinct().count())
				.as("Each placement must have its own identity").isEqualTo(2);
			org.assertj.core.api.Assertions.assertThat(placements.stream().map(t -> t.getArea().getStartX()).toList())
				.containsExactlyInAnyOrder(10, 400);

			// Removing one face leaves the other tagged - the operation the old primary key made impossible.
			UUID doomed = placements.get(0).getPlacementUuid();
			client.removeTagPlacement(ASSET_UUID, doomed).sync();

			List<TagReference> left2 = client.loadAsset(ASSET_UUID).sync().body().getTags().stream()
				.filter(t -> "anna".equals(t.getName()))
				.toList();
			org.assertj.core.api.Assertions.assertThat(left2).hasSize(1);
			org.assertj.core.api.Assertions.assertThat(left2.get(0).getPlacementUuid()).isNotEqualTo(doomed);
			org.assertj.core.api.Assertions.assertThat(client.loadTag(left.getUuid()).sync().body())
				.as("Removing a placement must not delete the tag").isNotNull();
		}
	}

	/**
	 * What B3 was for: the asset response says who attached each tag, so a client can show machine tags
	 * differently, filter them out, or leave them alone.
	 */
	@Test
	public void testTheAssetSaysWhoAttachedEachTag() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			client.tagAsset(ASSET_UUID, new TagCreateRequest().setName("typed-by-hand").setCollection("quality")).sync().body();
			client.bulkTagAsset(ASSET_UUID, new AssetTagBulkRequest()
				.setCollection("quality")
				.setNodeKind("tag")
				.setNodeId("tag:quality-tags")
				.setProducerVersion("tag/1:abc")
				.add(new TagCreateRequest().setName("found-by-machine").setConfidence(0.75f))).sync().body();

			List<TagReference> tags = client.loadAsset(ASSET_UUID).sync().body().getTags();

			TagReference human = tags.stream().filter(t -> "typed-by-hand".equals(t.getName())).findFirst().orElseThrow();
			org.assertj.core.api.Assertions.assertThat(human.getNodeKind())
				.as("A tag nobody claimed is a person's").isEqualTo("manual");
			org.assertj.core.api.Assertions.assertThat(human.getNodeId()).isNull();
			org.assertj.core.api.Assertions.assertThat(human.getAttached()).as("...and it is stamped").isNotNull();

			TagReference machine = tags.stream().filter(t -> "found-by-machine".equals(t.getName())).findFirst().orElseThrow();
			org.assertj.core.api.Assertions.assertThat(machine.getNodeKind()).isEqualTo("tag");
			org.assertj.core.api.Assertions.assertThat(machine.getNodeId()).isEqualTo("tag:quality-tags");
			org.assertj.core.api.Assertions.assertThat(machine.getConfidence()).isEqualTo(0.75f);
		}
	}

	/**
	 * 🔴 A node withdrawing its own work must not take a person's tag of the same name with it. Two
	 * placements of one name can now coexist, so withdrawal is scoped by the node id the caller declares.
	 */
	@Test
	public void testWithdrawalLeavesAHumanPlacementOfTheSameTag() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// A person places the tag on a face...
			client.tagAsset(ASSET_UUID, new TagCreateRequest()
				.setName("contested").setCollection("quality")
				.setArea(new AreaInfo().setStartX(5).setStartY(5).setWidth(20).setHeight(20))).sync().body();

			// ...and a node places the same name on the asset as a whole, then takes it back.
			AssetTagBulkResponse applied = client.bulkTagAsset(ASSET_UUID, new AssetTagBulkRequest()
				.setCollection("quality")
				.setNodeKind("tag")
				.setNodeId("tag:quality-tags")
				.add(new TagCreateRequest().setName("contested"))).sync().body();
			UUID tagUuid = applied.getTags().get(0).getUuid();

			AssetTagBulkResponse withdrawn = client.bulkTagAsset(ASSET_UUID, new AssetTagBulkRequest()
				.setCollection("quality")
				.setNodeKind("tag")
				.setNodeId("tag:quality-tags")
				.withdraw(tagUuid)).sync().body();

			org.assertj.core.api.Assertions.assertThat(withdrawn.getWithdrawn())
				.as("Only the node's own placement comes off").isEqualTo(1);

			List<TagReference> left = client.loadAsset(ASSET_UUID).sync().body().getTags().stream()
				.filter(t -> "contested".equals(t.getName()))
				.toList();
			org.assertj.core.api.Assertions.assertThat(left).as("The person's placement must survive").hasSize(1);
			org.assertj.core.api.Assertions.assertThat(left.get(0).getNodeKind()).isEqualTo("manual");
			org.assertj.core.api.Assertions.assertThat(left.get(0).getArea().getStartX()).isEqualTo(5);
		}
	}

	/** Removing a placement needs the same permission as removing a tag. */
	@Test
	public void testRemoveTagPlacementRequiresPermission() throws Exception {
		UUID placement;
		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			admin.tagAsset(ASSET_UUID, new TagCreateRequest().setName("guarded").setCollection("quality")).sync().body();
			placement = admin.loadAsset(ASSET_UUID).sync().body().getTags().stream()
				.filter(t -> "guarded".equals(t.getName()))
				.findFirst().orElseThrow().getPlacementUuid();
		}
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.removeTagPlacement(ASSET_UUID, placement));
		}
	}

	/** A placement uuid from another asset must not be reachable through this one. */
	@Test
	public void testRemoveTagPlacementIsScopedByAsset() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			client.tagAsset(ASSET_UUID, new TagCreateRequest().setName("elsewhere").setCollection("quality")).sync().body();
			UUID placement = client.loadAsset(ASSET_UUID).sync().body().getTags().stream()
				.filter(t -> "elsewhere".equals(t.getName()))
				.findFirst().orElseThrow().getPlacementUuid();

			UUID otherAsset = createAsset(client, "c1", "tag-c1.png");
			expect(404, "Not Found", client.removeTagPlacement(otherAsset, placement));

			org.assertj.core.api.Assertions.assertThat(client.loadAsset(ASSET_UUID).sync().body().getTags().stream()
				.anyMatch(t -> "elsewhere".equals(t.getName())))
				.as("The placement must survive the attempt").isTrue();
		}
	}

	/**
	 * Deleting a tagged asset works, and takes only the assignment with it (V2.72).
	 *
	 * <p>
	 * This answered 500 until the join row learned to cascade: the foreign key rejected the delete, and a caller who had tagged an asset could never
	 * remove it. The tag survives - it is a global object other assets carry.
	 * </p>
	 */
	@Test
	public void testDeletingATaggedAssetKeepsTheTag() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			UUID doomedAsset = createAsset(client, "d1", "tag-d1.png");
			TagResponse sharedTag = client.tagAsset(doomedAsset,
				new TagCreateRequest().setName("survives-its-asset").setCollection("quality")).sync().body();
			// A tag that only the doomed asset carries: its last placement disappears with the asset, but the tag must not.
			TagResponse loneTag = client.tagAsset(doomedAsset,
				new TagCreateRequest().setName("only-on-the-doomed-asset").setCollection("quality")).sync().body();

			// The same tag on a second asset, so "the tag survives" is not just "nothing was deleted".
			UUID keptAsset = createAsset(client, "d2", "tag-d2.png");
			client.tagAsset(keptAsset, new TagCreateRequest().setName("survives-its-asset").setCollection("quality")).sync().body();
			TagResponse keptOwnTag = client.tagAsset(keptAsset,
				new TagCreateRequest().setName("only-on-the-kept-asset").setCollection("quality")).sync().body();

			client.deleteAsset(doomedAsset).sync();

			expect(404, "Not Found", client.loadAsset(doomedAsset));
			org.assertj.core.api.Assertions.assertThat(client.loadTag(sharedTag.getUuid()).sync().body())
				.as("The tag must survive the asset").isNotNull();
			org.assertj.core.api.Assertions.assertThat(client.loadTag(loneTag.getUuid()).sync().body())
				.as("A tag whose last placement went with the asset is an empty tag, not a deleted one").isNotNull();

			// ...and nothing of the other asset moved.
			AssetResponse kept = client.loadAsset(keptAsset).sync().body();
			assertThat(kept).isValid();
			org.assertj.core.api.Assertions.assertThat(kept.getTags()).as("The other asset keeps exactly its own two tags").hasSize(2);
			assertTagged(client, keptAsset, sharedTag.getUuid());
			assertTagged(client, keptAsset, keptOwnTag.getUuid());
		}
	}

	/** ...and the mirror image: deleting a tag detaches it everywhere and leaves the assets, and their other tags, alone. */
	@Test
	public void testDeletingATagKeepsTheAsset() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TagResponse doomedTag = client.tagAsset(ASSET_UUID,
				new TagCreateRequest().setName("doomed-over-rest").setCollection("quality")).sync().body();
			TagResponse keptTag = client.tagAsset(ASSET_UUID,
				new TagCreateRequest().setName("kept-over-rest").setCollection("quality")).sync().body();
			// A second asset carrying the doomed tag: its placement must go too, but the asset must not.
			UUID otherAsset = createAsset(client, "d3", "tag-d3.png");
			client.tagAsset(otherAsset, new TagCreateRequest().setName("doomed-over-rest").setCollection("quality")).sync().body();

			client.deleteTag(doomedTag.getUuid()).sync();

			expect(404, "Not Found", client.loadTag(doomedTag.getUuid()));

			AssetResponse asset = client.loadAsset(ASSET_UUID).sync().body();
			assertThat(asset).isValid();
			org.assertj.core.api.Assertions.assertThat(asset.getTags().stream().anyMatch(t -> t.getUuid().equals(doomedTag.getUuid())))
				.as("The assignment must be gone with the tag").isFalse();
			assertTagged(client, ASSET_UUID, keptTag.getUuid());
			org.assertj.core.api.Assertions.assertThat(client.loadTag(keptTag.getUuid()).sync().body())
				.as("Only the named tag is deleted").isNotNull();

			AssetResponse other = client.loadAsset(otherAsset).sync().body();
			assertThat(other).isValid();
			org.assertj.core.api.Assertions.assertThat(other.getTags()).as("The second asset loses only that one assignment").isEmpty();
		}
	}

	/** No permissions at all: the route must be refused before it touches anything. */
	@Test
	public void testBulkTagAssetRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.bulkTagAsset(ASSET_UUID, new AssetTagBulkRequest()
				.setCollection("quality")
				.add(new TagCreateRequest().setName("denied"))));
		}
	}

	/**
	 * 🔴 The permission set of this route depends on the request: withdrawing needs {@code UNTAG_ASSET} on top of {@code TAG_ASSET}. A caller holding
	 * only the first must be refused <em>the whole call</em> - not served the attachments and denied the removals, which would leave the asset in a
	 * state neither side asked for.
	 */
	@Test
	public void testBulkWithdrawRequiresTheUntagPermission() throws Exception {
		UUID attached;
		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			attached = admin.tagAsset(ASSET_UUID,
				new TagCreateRequest().setName("only-admin-may-remove").setCollection("quality")).sync().body().getUuid();
		}

		try (LoomHttpClient client = loginClientWith("tagger", Permission.TAG_ASSET)) {
			// Attaching alone is allowed for this caller.
			client.bulkTagAsset(ASSET_UUID, new AssetTagBulkRequest()
				.setCollection("quality")
				.add(new TagCreateRequest().setName("tagger-may-add"))).sync().body();

			// The same call with a withdrawal is not.
			expect(403, "Forbidden", client.bulkTagAsset(ASSET_UUID, new AssetTagBulkRequest()
				.setCollection("quality")
				.add(new TagCreateRequest().setName("tagger-may-add"))
				.withdraw(attached)));
		}

		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			assertTagged(admin, ASSET_UUID, attached);
		}
	}

	/** {@code TAG_ASSET} on the single-tag route, which carried no 403 case until now. */
	@Test
	public void testTagAssetRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.tagAsset(ASSET_UUID,
				new TagCreateRequest().setName("denied").setCollection("quality")));
		}
	}

	/** {@code UNTAG_ASSET} likewise. */
	@Test
	public void testUntagAssetRequiresPermission() throws Exception {
		UUID tagUuid;
		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			tagUuid = admin.tagAsset(ASSET_UUID,
				new TagCreateRequest().setName("undeletable").setCollection("quality")).sync().body().getUuid();
		}
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.untagAsset(ASSET_UUID, tagUuid));
		}
	}

	private void assertTagged(LoomHttpClient client, UUID assetUuid, UUID tagUuid) throws LoomClientException {
		AssetResponse asset = client.loadAsset(assetUuid).sync().body();
		org.assertj.core.api.Assertions.assertThat(asset.getTags().stream().anyMatch(t -> t.getUuid().equals(tagUuid)))
			.as("Asset " + assetUuid + " must carry the tag").isTrue();
	}

	/** A second asset to tag. The fixture ships one, and one asset cannot show that a tag is shared. */
	private UUID createAsset(LoomHttpClient client, String seed, String filename) throws LoomClientException {
		SHA512 sha = SHA512.fromString(SHA512SUM.toString().substring(0, 124) + String.format("%4s", seed).replace(' ', '0'));
		AssetCreateRequest request = new AssetCreateRequest();
		request.setFile(new FileInfo().setMimeType(IMAGE_MIMETYPE).setFilename(filename).setSize(512L).setOrigin(INITIAL_ORIGIN));
		request.setHashes(new HashInfo().setSHA512(sha));
		return client.createAsset(request).sync().body().getUuid();
	}

	@Test
	public void testTagAssetWithRegion() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// Region tag carrying both a spatial box and a temporal range.
			AreaInfo area = new AreaInfo()
				.setStartX(10)
				.setStartY(20)
				.setWidth(100)
				.setHeight(50)
				.setFrom(1500L)
				.setTo(4200L);

			TagCreateRequest request = new TagCreateRequest();
			request.setName("region");
			request.setCollection("regions");
			request.setArea(area);
			TagResponse tag = client.tagAsset(ASSET_UUID, request).sync().body();
			assertThat(tag).isValid();

			// The region must round-trip through the response of the create call...
			assertRegion(tag.getArea());

			// ...and be persisted on the tag<->asset relationship, so it comes back on the asset's tag list.
			AssetResponse asset = client.loadAsset(ASSET_UUID).sync().body();
			assertThat(asset).isValid();
			TagReference reference = asset.getTags().stream()
				.filter(t -> t.getUuid().equals(tag.getUuid()))
				.findFirst()
				.orElse(null);
			org.assertj.core.api.Assertions.assertThat(reference).as("Region tag must be listed on the asset").isNotNull();
			assertRegion(reference.getArea());

			client.untagAsset(ASSET_UUID, tag.getUuid()).sync().body();

			// After untagging the region tag must no longer be present on the asset.
			AssetResponse untagged = client.loadAsset(ASSET_UUID).sync().body();
			org.assertj.core.api.Assertions.assertThat(untagged.getTags().stream().anyMatch(t -> t.getUuid().equals(tag.getUuid())))
				.as("Region tag must be removed after untagging").isFalse();
		}
	}

	private void assertRegion(AreaInfo area) {
		org.assertj.core.api.Assertions.assertThat(area).as("Tag area must be present").isNotNull();
		org.assertj.core.api.Assertions.assertThat(area.getStartX()).as("startX").isEqualTo(10);
		org.assertj.core.api.Assertions.assertThat(area.getStartY()).as("startY").isEqualTo(20);
		org.assertj.core.api.Assertions.assertThat(area.getWidth()).as("width").isEqualTo(100);
		org.assertj.core.api.Assertions.assertThat(area.getHeight()).as("height").isEqualTo(50);
		org.assertj.core.api.Assertions.assertThat(area.getFrom()).as("from").isEqualTo(1500L);
		org.assertj.core.api.Assertions.assertThat(area.getTo()).as("to").isEqualTo(4200L);
	}

}
