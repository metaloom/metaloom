package io.metaloom.loom.graphql;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import graphql.schema.DataFetcher;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.TypeRuntimeWiring;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetAudioComp;
import io.metaloom.loom.db.model.asset.AssetComponent;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.asset.AssetImageComp;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.model.asset.AssetBinaryDao;
import io.metaloom.loom.db.model.asset.AssetVideoComp;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.utils.hash.SHA512;

/**
 * Wiring for assets, asset locations and the typed asset components.
 */
public class AssetWiring extends AbstractDomainWiring {

	private final AssetDao assetDao;
	private final AssetBinaryDao locationDao;
	private final AssetComponentDao componentDao;

	public AssetWiring(DaoCollection daos) {
		this.assetDao = daos.assetDao();
		this.locationDao = daos.assetBinaryDao();
		this.componentDao = daos.assetComponentDao();
	}

	@Override
	public void wire(RuntimeWiring.Builder builder) {

		// Query root
		DataFetcher<Asset> assetFetcher = env -> {
			requirePermission(env, Permission.READ_ASSET);
			return assetDao.load(uuidArg(env, "uuid"));
		};

		DataFetcher<Asset> assetBySha512Fetcher = env -> {
			requirePermission(env, Permission.READ_ASSET);
			String sha512 = env.getArgument("sha512");
			return assetDao.loadBySHA512(SHA512.fromString(sha512));
		};

		DataFetcher<List<? extends Asset>> assetsFetcher = env -> {
			requirePermission(env, Permission.READ_ASSET);
			return assetDao.findAll().collect(Collectors.toList());
		};

		DataFetcher<AssetBinary> assetLocationFetcher = env -> {
			requirePermission(env, Permission.READ_ASSET_LOCATION);
			return locationDao.load(uuidArg(env, "uuid"));
		};

		DataFetcher<List<? extends AssetBinary>> assetLocationsFetcher = env -> {
			requirePermission(env, Permission.READ_ASSET_LOCATION);
			UUID assetUuid = uuidArg(env, "assetUuid");
			if (assetUuid != null) {
				return orEmpty(locationDao.loadAllByAssetUuid(assetUuid));
			}
			return locationDao.findAll().collect(Collectors.toList());
		};

		// Asset field resolvers
		DataFetcher<List<? extends AssetBinary>> locationsFetcher = env -> {
			requirePermission(env, Permission.READ_ASSET_LOCATION);
			Asset asset = env.getSource();
			return orEmpty(locationDao.loadAllByAssetUuid(asset.getUuid()));
		};

		DataFetcher<List<AssetImageComp>> imageCompsFetcher = env -> {
			requirePermission(env, Permission.READ_ASSET);
			Asset asset = env.getSource();
			return orEmpty(componentDao.loadImageComps(asset.getUuid()));
		};

		DataFetcher<List<AssetVideoComp>> videoCompsFetcher = env -> {
			requirePermission(env, Permission.READ_ASSET);
			Asset asset = env.getSource();
			return orEmpty(componentDao.loadVideoComps(asset.getUuid()));
		};

		DataFetcher<List<AssetAudioComp>> audioCompsFetcher = env -> {
			requirePermission(env, Permission.READ_ASSET);
			Asset asset = env.getSource();
			return orEmpty(componentDao.loadAudioComps(asset.getUuid()));
		};

		// SHA / hash fetchers
		DataFetcher<String> sha512Fetcher = env -> {
			Asset asset = env.getSource();
			return asset.getSHA512() != null ? asset.getSHA512().toString() : null;
		};
		DataFetcher<String> sha256Fetcher = env -> {
			Asset asset = env.getSource();
			return asset.getSHA256() != null ? asset.getSHA256().toString() : null;
		};
		DataFetcher<String> md5Fetcher = env -> {
			Asset asset = env.getSource();
			return asset.getMD5() != null ? asset.getMD5().toString() : null;
		};

		// AssetLocation field resolvers (the GraphQL type name; the row is an AssetBinary)
		DataFetcher<Asset> locationAssetFetcher = env -> {
			requirePermission(env, Permission.READ_ASSET);
			AssetBinary location = env.getSource();
			return location.getAssetUuid() == null ? null : assetDao.load(location.getAssetUuid());
		};

		// Component field resolvers.
		// The GraphQL "source" field keeps its name but is now backed by the component's
		// producing node kind - the DB column was split into node_kind/producer_version.
		DataFetcher<String> compSourceFetcher = env -> {
			AssetComponent<?> comp = env.getSource();
			return comp.getNodeKind();
		};
		DataFetcher<String> dominantColorFetcher = env -> {
			AssetImageComp comp = env.getSource();
			return comp.getImageDominantColor();
		};
		DataFetcher<Integer> widthFetcher = env -> {
			AssetImageComp comp = env.getSource();
			return comp.getMediaWidth();
		};
		DataFetcher<Integer> heightFetcher = env -> {
			AssetImageComp comp = env.getSource();
			return comp.getMediaHeight();
		};

		builder
			.type(TypeRuntimeWiring.newTypeWiring("Query")
				.dataFetcher("asset", assetFetcher)
				.dataFetcher("assetBySha512", assetBySha512Fetcher)
				.dataFetcher("assets", assetsFetcher)
				.dataFetcher("assetLocation", assetLocationFetcher)
				.dataFetcher("assetLocations", assetLocationsFetcher))
			.type(TypeRuntimeWiring.newTypeWiring("Asset")
				.dataFetcher("locations", locationsFetcher)
				.dataFetcher("imageComponents", imageCompsFetcher)
				.dataFetcher("videoComponents", videoCompsFetcher)
				.dataFetcher("audioComponents", audioCompsFetcher)
				.dataFetcher("sha512", sha512Fetcher)
				.dataFetcher("sha256", sha256Fetcher)
				.dataFetcher("md5", md5Fetcher))
			.type(TypeRuntimeWiring.newTypeWiring("AssetLocation")
				.dataFetcher("asset", locationAssetFetcher))
			.type(TypeRuntimeWiring.newTypeWiring("ImageComponent")
				.dataFetcher("source", compSourceFetcher)
				.dataFetcher("dominantColor", dominantColorFetcher)
				.dataFetcher("width", widthFetcher)
				.dataFetcher("height", heightFetcher))
			.type(TypeRuntimeWiring.newTypeWiring("VideoComponent")
				.dataFetcher("source", compSourceFetcher))
			.type(TypeRuntimeWiring.newTypeWiring("AudioComponent")
				.dataFetcher("source", compSourceFetcher));
	}

}
