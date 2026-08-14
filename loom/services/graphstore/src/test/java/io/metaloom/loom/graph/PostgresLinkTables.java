package io.metaloom.loom.graph;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.metaloom.loom.api.graph.GraphEdge;
import io.metaloom.loom.api.graph.GraphNodeRef;

/**
 * The link tables, in a real Postgres, plus the query the index is competing with.
 *
 * <p>
 * This is the other half of the differential check, and it is deliberately not a mock. The question Phase 10 asks is
 * whether a graph traversal returns the same answers as the SQL that MetaLoom would otherwise write, and the only way
 * to answer that is to write the SQL and run it. The DDL mirrors {@code tag_asset}, {@code collection_asset},
 * {@code remix_member} and the asset-to-person path through {@code detection}, reduced to the columns the query
 * touches.
 * </p>
 *
 * <p>
 * Everything lives in a throwaway schema that is dropped afterwards, so this never touches a developer's data.
 * </p>
 */
public final class PostgresLinkTables implements AutoCloseable {

	/**
	 * One schema per instance.
	 * <p>
	 * A fixed name looks tidier and is a trap: two of these running at once - the differential check and the
	 * benchmark, say - would drop each other's tables mid-run, and the failure surfaces as "relation does not exist"
	 * a long way from its cause.
	 */
	private final String schema;

	private final Connection connection;

	private PostgresLinkTables(Connection connection) {
		this.connection = connection;
		this.schema = "assetgraph_difftest_" + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong() >>> 1);
	}

	/** Opens the schema, or returns null when no Postgres is reachable — the caller then skips. */
	public static PostgresLinkTables openOrNull(String url, String user, String password) {
		try {
			Connection connection = DriverManager.getConnection(url, user, password);
			PostgresLinkTables tables = new PostgresLinkTables(connection);
			tables.createSchema();
			return tables;
		} catch (SQLException e) {
			return null;
		}
	}

	private void createSchema() throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
			statement.execute("CREATE SCHEMA " + schema);
			statement.execute("SET search_path TO " + schema);
			statement.execute("""
				CREATE TABLE tag_asset (
				  tag_uuid uuid NOT NULL,
				  asset_uuid uuid NOT NULL,
				  PRIMARY KEY (tag_uuid, asset_uuid))
				""");
			statement.execute("""
				CREATE TABLE collection_asset (
				  collection_uuid uuid NOT NULL,
				  asset_uuid uuid NOT NULL,
				  PRIMARY KEY (collection_uuid, asset_uuid))
				""");
			statement.execute("""
				CREATE TABLE remix_member (
				  remix_uuid uuid NOT NULL,
				  asset_uuid uuid NOT NULL,
				  role varchar NOT NULL DEFAULT 'DERIVED',
				  PRIMARY KEY (remix_uuid, asset_uuid))
				""");
			// The asset-to-person relation is a join through detection in the real schema; the projection collapses
			// it, so the comparison does too.
			statement.execute("""
				CREATE TABLE asset_person (
				  person_uuid uuid NOT NULL,
				  asset_uuid uuid NOT NULL,
				  PRIMARY KEY (person_uuid, asset_uuid))
				""");
			// The real schema indexes both columns of every link table. Leaving them off would make the comparison
			// flattering to the graph index and worthless as a decision input.
			for (String index : new String[] {
				"CREATE INDEX ON tag_asset (asset_uuid)",
				"CREATE INDEX ON collection_asset (asset_uuid)",
				"CREATE INDEX ON remix_member (asset_uuid)",
				"CREATE INDEX ON asset_person (asset_uuid)" }) {
				statement.execute(index);
			}
		}
	}

	/** Inserts many edges, one JDBC batch per link table. Row-by-row inserts dominate the benchmark otherwise. */
	public void insertAll(List<GraphEdge> edges) throws SQLException {
		java.util.Map<String, List<GraphEdge>> byType = new java.util.LinkedHashMap<>();
		for (GraphEdge edge : edges) {
			byType.computeIfAbsent(edge.type(), k -> new ArrayList<>()).add(edge);
		}
		boolean autoCommit = connection.getAutoCommit();
		connection.setAutoCommit(false);
		try {
			for (var entry : byType.entrySet()) {
				try (PreparedStatement statement = connection.prepareStatement(insertSql(entry.getKey()))) {
					for (GraphEdge edge : entry.getValue()) {
						statement.setObject(1, edge.from().uuid());
						statement.setObject(2, edge.to().uuid());
						statement.addBatch();
					}
					statement.executeBatch();
				}
			}
			connection.commit();
		} finally {
			connection.setAutoCommit(autoCommit);
		}
	}

	private static String insertSql(String type) {
		return switch (type) {
			case GraphEdge.TYPE_TAGGED -> "INSERT INTO tag_asset (tag_uuid, asset_uuid) VALUES (?, ?) ON CONFLICT DO NOTHING";
			case GraphEdge.TYPE_IN_COLLECTION ->
				"INSERT INTO collection_asset (collection_uuid, asset_uuid) VALUES (?, ?) ON CONFLICT DO NOTHING";
			case GraphEdge.TYPE_IN_REMIX -> "INSERT INTO remix_member (remix_uuid, asset_uuid) VALUES (?, ?) ON CONFLICT DO NOTHING";
			case GraphEdge.TYPE_DEPICTS -> "INSERT INTO asset_person (person_uuid, asset_uuid) VALUES (?, ?) ON CONFLICT DO NOTHING";
			default -> throw new IllegalArgumentException("No link table for edge type " + type);
		};
	}

	/** Inserts an edge into whichever link table it belongs to. */
	public void insert(GraphEdge edge) throws SQLException {
		String sql = switch (edge.type()) {
			case GraphEdge.TYPE_TAGGED -> "INSERT INTO tag_asset (tag_uuid, asset_uuid) VALUES (?, ?) ON CONFLICT DO NOTHING";
			case GraphEdge.TYPE_IN_COLLECTION ->
				"INSERT INTO collection_asset (collection_uuid, asset_uuid) VALUES (?, ?) ON CONFLICT DO NOTHING";
			case GraphEdge.TYPE_IN_REMIX -> "INSERT INTO remix_member (remix_uuid, asset_uuid) VALUES (?, ?) ON CONFLICT DO NOTHING";
			case GraphEdge.TYPE_DEPICTS -> "INSERT INTO asset_person (person_uuid, asset_uuid) VALUES (?, ?) ON CONFLICT DO NOTHING";
			default -> throw new IllegalArgumentException("No link table for edge type " + edge.type());
		};
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, edge.from().uuid());
			statement.setObject(2, edge.to().uuid());
			statement.executeUpdate();
		}
	}

	public void delete(GraphEdge edge) throws SQLException {
		String sql = switch (edge.type()) {
			case GraphEdge.TYPE_TAGGED -> "DELETE FROM tag_asset WHERE tag_uuid = ? AND asset_uuid = ?";
			case GraphEdge.TYPE_IN_COLLECTION -> "DELETE FROM collection_asset WHERE collection_uuid = ? AND asset_uuid = ?";
			case GraphEdge.TYPE_IN_REMIX -> "DELETE FROM remix_member WHERE remix_uuid = ? AND asset_uuid = ?";
			case GraphEdge.TYPE_DEPICTS -> "DELETE FROM asset_person WHERE person_uuid = ? AND asset_uuid = ?";
			default -> throw new IllegalArgumentException("No link table for edge type " + edge.type());
		};
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, edge.from().uuid());
			statement.setObject(2, edge.to().uuid());
			statement.executeUpdate();
		}
	}

	/** Removes an asset the way the SQL cascades do when the row is deleted. */
	public void deleteAsset(UUID assetUuid) throws SQLException {
		for (String table : new String[] { "tag_asset", "collection_asset", "remix_member", "asset_person" }) {
			try (PreparedStatement statement = connection.prepareStatement(
				"DELETE FROM " + table + " WHERE asset_uuid = ?")) {
				statement.setObject(1, assetUuid);
				statement.executeUpdate();
			}
		}
	}

	/**
	 * The query the index exists to replace: every asset sharing at least one tag, collection, remix or person with
	 * the given one, ranked by how many they share.
	 *
	 * <p>
	 * Note what it costs. The {@code links} CTE is a four-way union over every link row in the system, self-joined.
	 * Every relation added to the schema adds a branch, and every branch scans another table. That growth, not the
	 * absolute time, is the argument for the index.
	 * </p>
	 */
	public List<SqlRelatedAsset> relatedAssets(UUID assetUuid, Set<String> viaTypes, int limit) throws SQLException {
		String typeFilter = viaTypes == null ? "" : " AND l.type = ANY(?) AND o.type = ANY(?)";
		String sql = """
			WITH links AS (
			  SELECT tag_uuid AS via_uuid, 'tag' AS via_kind, 'TAGGED' AS type, asset_uuid FROM tag_asset
			  UNION ALL
			  SELECT collection_uuid, 'collection', 'IN_COLLECTION', asset_uuid FROM collection_asset
			  UNION ALL
			  SELECT remix_uuid, 'remix', 'IN_REMIX', asset_uuid FROM remix_member
			  UNION ALL
			  SELECT person_uuid, 'person', 'DEPICTS', asset_uuid FROM asset_person
			)
			SELECT o.asset_uuid, count(DISTINCT (l.via_kind, l.via_uuid)) AS shared
			FROM links l
			JOIN links o ON o.via_uuid = l.via_uuid AND o.via_kind = l.via_kind
			WHERE l.asset_uuid = ? AND o.asset_uuid <> l.asset_uuid%s
			GROUP BY o.asset_uuid
			ORDER BY shared DESC, o.asset_uuid
			LIMIT ?
			""".formatted(typeFilter);

		List<SqlRelatedAsset> result = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			int index = 1;
			statement.setObject(index++, assetUuid);
			if (viaTypes != null) {
				java.sql.Array types = connection.createArrayOf("varchar", viaTypes.toArray());
				statement.setArray(index++, types);
				statement.setArray(index++, types);
			}
			statement.setInt(index, limit);
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					result.add(new SqlRelatedAsset((UUID) rows.getObject(1), rows.getInt(2)));
				}
			}
		}
		return result;
	}

	/**
	 * The same question, written the way someone who cared about it would write it.
	 *
	 * <p>
	 * The version above materialises a four-way union of <b>every</b> link row and self-joins it, because a CTE
	 * referenced twice is materialised. That is a fair rendering of the obvious query and an unfair opponent for a
	 * benchmark. This one pushes the asset filter into each branch first - an index scan over a handful of rows - and
	 * only then looks up the other assets on those intermediates, which is precisely what the graph traversal does.
	 * Every join here rides the leading column of a primary key.
	 * </p>
	 *
	 * <p>
	 * Reporting only the first version would have made the index look an order of magnitude better than it is. Both
	 * are measured, and both are asserted to agree with it.
	 * </p>
	 */
	public List<SqlRelatedAsset> relatedAssetsOptimised(UUID assetUuid, Set<String> viaTypes, int limit)
		throws SQLException {
		record Branch(String type, String table, String viaColumn, String kind) {
		}
		List<Branch> branches = List.of(
			new Branch(GraphEdge.TYPE_TAGGED, "tag_asset", "tag_uuid", "tag"),
			new Branch(GraphEdge.TYPE_IN_COLLECTION, "collection_asset", "collection_uuid", "collection"),
			new Branch(GraphEdge.TYPE_IN_REMIX, "remix_member", "remix_uuid", "remix"),
			new Branch(GraphEdge.TYPE_DEPICTS, "asset_person", "person_uuid", "person"));
		List<Branch> active = branches.stream().filter(b -> viaTypes == null || viaTypes.contains(b.type())).toList();
		if (active.isEmpty()) {
			return List.of();
		}

		StringBuilder mine = new StringBuilder();
		StringBuilder others = new StringBuilder();
		for (Branch branch : active) {
			if (!mine.isEmpty()) {
				mine.append("\n  UNION ALL ");
				others.append("\n  UNION ALL ");
			}
			mine.append("SELECT ").append(branch.viaColumn()).append(" AS via_uuid, '").append(branch.kind())
				.append("' AS via_kind FROM ").append(branch.table()).append(" WHERE asset_uuid = ?");
			others.append("SELECT t.asset_uuid, m.via_uuid, m.via_kind FROM mine m JOIN ").append(branch.table())
				.append(" t ON t.").append(branch.viaColumn()).append(" = m.via_uuid WHERE m.via_kind = '")
				.append(branch.kind()).append("'");
		}

		String sql = "WITH mine AS (\n  " + mine + "\n), others AS (\n  " + others + "\n)\n"
			+ "SELECT asset_uuid, count(DISTINCT (via_kind, via_uuid)) AS shared FROM others\n"
			+ "WHERE asset_uuid <> ? GROUP BY asset_uuid ORDER BY shared DESC, asset_uuid LIMIT ?";

		List<SqlRelatedAsset> result = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			int index = 1;
			for (int i = 0; i < active.size(); i++) {
				statement.setObject(index++, assetUuid);
			}
			statement.setObject(index++, assetUuid);
			statement.setInt(index, limit);
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					result.add(new SqlRelatedAsset((UUID) rows.getObject(1), rows.getInt(2)));
				}
			}
		}
		return result;
	}

	/** The immediate neighbours of a node, the way a caller would assemble them from the link tables. */
	public Set<GraphNodeRef> neighbours(GraphNodeRef node, Set<String> types) throws SQLException {
		Set<GraphNodeRef> result = new LinkedHashSet<>();
		record Relation(String type, String table, String otherColumn, String otherKind, String ownColumn,
			String ownKind) {
		}
		List<Relation> relations = List.of(
			new Relation(GraphEdge.TYPE_TAGGED, "tag_asset", "tag_uuid", GraphNodeRef.KIND_TAG, "asset_uuid",
				GraphNodeRef.KIND_ASSET),
			new Relation(GraphEdge.TYPE_IN_COLLECTION, "collection_asset", "collection_uuid",
				GraphNodeRef.KIND_COLLECTION, "asset_uuid", GraphNodeRef.KIND_ASSET),
			new Relation(GraphEdge.TYPE_IN_REMIX, "remix_member", "remix_uuid", GraphNodeRef.KIND_REMIX, "asset_uuid",
				GraphNodeRef.KIND_ASSET),
			new Relation(GraphEdge.TYPE_DEPICTS, "asset_person", "person_uuid", GraphNodeRef.KIND_PERSON, "asset_uuid",
				GraphNodeRef.KIND_ASSET));

		for (Relation relation : relations) {
			if (types != null && !types.contains(relation.type())) {
				continue;
			}
			String select;
			String where;
			String otherKind;
			if (node.kind().equals(relation.ownKind())) {
				select = relation.otherColumn();
				where = relation.ownColumn();
				otherKind = relation.otherKind();
			} else if (node.kind().equals(relation.otherKind())) {
				select = relation.ownColumn();
				where = relation.otherColumn();
				otherKind = relation.ownKind();
			} else {
				continue;
			}
			try (PreparedStatement statement = connection.prepareStatement(
				"SELECT " + select + " FROM " + relation.table() + " WHERE " + where + " = ?")) {
				statement.setObject(1, node.uuid());
				try (ResultSet rows = statement.executeQuery()) {
					while (rows.next()) {
						result.add(new GraphNodeRef(otherKind, (UUID) rows.getObject(1)));
					}
				}
			}
		}
		return result;
	}

	/** Refreshes the planner statistics. A benchmark against a table Postgres has never analysed is not a benchmark. */
	public void analyze() throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("ANALYZE tag_asset, collection_asset, remix_member, asset_person");
		}
	}

	public record SqlRelatedAsset(UUID assetUuid, int sharedConnections) {
	}

	@Override
	public void close() throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		} finally {
			connection.close();
		}
	}
}
