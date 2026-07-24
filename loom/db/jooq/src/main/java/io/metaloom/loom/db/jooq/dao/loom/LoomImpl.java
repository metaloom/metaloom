package io.metaloom.loom.db.jooq.dao.loom;

import java.time.Instant;

import io.metaloom.loom.db.model.loom.Loom;

public class LoomImpl implements Loom {

	private String databaseRevision;
	private Instant lastUsedTimestamp;

	@Override
	public String getDatabaseRevision() {
		return databaseRevision;
	}

	@Override
	public Loom setDatabaseRevision(String databaseRevision) {
		this.databaseRevision = databaseRevision;
		return this;
	}

	@Override
	public Instant getLastUsedTimestamp() {
		return lastUsedTimestamp;
	}

	@Override
	public Loom setLastUsedTimestamp(Instant lastUsedTimestamp) {
		this.lastUsedTimestamp = lastUsedTimestamp;
		return this;
	}

	@Override
	public String toString() {
		return "[Loom] dbRev: " + databaseRevision + ", lastUsed: " + lastUsedTimestamp;
	}
}
