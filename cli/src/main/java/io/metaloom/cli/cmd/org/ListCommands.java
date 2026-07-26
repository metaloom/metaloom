package io.metaloom.cli.cmd.org;

import java.util.List;
import java.util.function.Function;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cli.ExitCode;
import io.metaloom.cli.cmd.AbstractCliCommand;
import io.metaloom.cli.output.Table;
import io.metaloom.loom.rest.model.group.GroupResponse;
import io.metaloom.loom.rest.model.library.LibraryResponse;
import io.metaloom.loom.rest.model.pool.AssetPoolResponse;
import io.metaloom.loom.rest.model.role.RoleResponse;
import io.metaloom.loom.rest.model.space.SpaceResponse;
import io.metaloom.loom.rest.model.user.UserResponse;
import picocli.CommandLine.Command;

/**
 * The simple listing commands.
 *
 * <p>Grouped in one file because each is a single call plus a two-column table; splitting
 * them across six files would be more ceremony than content. They are separate top-level
 * command groups so that {@code metaloom space ls} reads naturally.</p>
 *
 * <p>Note there is no {@code project} command: Loom has no project entity. The hierarchy is
 * spaces &rarr; libraries &rarr; pools &rarr; collections &rarr; assets, and pretending
 * otherwise would invent a concept the server cannot honour.</p>
 */
public final class ListCommands {

	private ListCommands() {
	}

	/** A group whose only job is to hold a {@code list} subcommand. */
	abstract static class AbstractGroup extends AbstractCliCommand {

		@Override
		protected Integer execute() {
			return usage();
		}
	}

	/** Renders a name/uuid listing. */
	static <T> Integer printNamed(AbstractCliCommand command, List<T> items,
		Function<T, String> uuid, Function<T, String> name) {

		command.printer().printList(items,
			list -> {
				Table table = new Table("UUID", "NAME");
				for (T item : list) {
					table.row(uuid.apply(item), name.apply(item));
				}
				return table;
			},
			uuid);
		return ExitCode.OK;
	}

	// ── space ────────────────────────────────────────────────────────────

	@Singleton
	@Command(name = "space", description = "Spaces - the top of the content hierarchy.",
		subcommands = SpaceList.class)
	public static class SpaceGroup extends AbstractGroup {

		@Inject
		public SpaceGroup() {
		}
	}

	@Singleton
	@Command(name = "list", aliases = "ls", description = "List spaces.")
	public static class SpaceList extends AbstractCliCommand {

		@Inject
		public SpaceList() {
		}

		@Override
		protected Integer execute() {
			List<SpaceResponse> items = api().listSpaces();
			return printNamed(this, items, s -> String.valueOf(s.getUuid()), SpaceResponse::getName);
		}
	}

	// ── library ──────────────────────────────────────────────────────────

	@Singleton
	@Command(name = "library", description = "Libraries within a space.", subcommands = LibraryList.class)
	public static class LibraryGroup extends AbstractGroup {

		@Inject
		public LibraryGroup() {
		}
	}

	@Singleton
	@Command(name = "list", aliases = "ls", description = "List libraries.")
	public static class LibraryList extends AbstractCliCommand {

		@Inject
		public LibraryList() {
		}

		@Override
		protected Integer execute() {
			List<LibraryResponse> items = api().listLibraries();
			return printNamed(this, items, l -> String.valueOf(l.getUuid()), LibraryResponse::getName);
		}
	}

	// ── pool ─────────────────────────────────────────────────────────────

	@Singleton
	@Command(name = "pool", description = "Asset pools - where binaries live on disk.",
		subcommands = PoolList.class)
	public static class PoolGroup extends AbstractGroup {

		@Inject
		public PoolGroup() {
		}
	}

	@Singleton
	@Command(name = "list", aliases = "ls", description = "List asset pools.")
	public static class PoolList extends AbstractCliCommand {

		@Inject
		public PoolList() {
		}

		@Override
		protected Integer execute() {
			List<AssetPoolResponse> items = api().listPools();
			printer().printList(items,
				list -> {
					Table table = new Table("UUID", "NAME", "PATH");
					for (AssetPoolResponse pool : list) {
						table.row(String.valueOf(pool.getUuid()), pool.getName(),
							pool.getFsPath() == null ? "" : pool.getFsPath());
					}
					return table;
				},
				pool -> String.valueOf(pool.getUuid()));
			return ExitCode.OK;
		}
	}

	// ── user ─────────────────────────────────────────────────────────────

	@Singleton
	@Command(name = "user", description = "Users.", subcommands = UserList.class)
	public static class UserGroup extends AbstractGroup {

		@Inject
		public UserGroup() {
		}
	}

	@Singleton
	@Command(name = "list", aliases = "ls", description = "List users.")
	public static class UserList extends AbstractCliCommand {

		@Inject
		public UserList() {
		}

		@Override
		protected Integer execute() {
			List<UserResponse> items = api().listUsers();
			printer().printList(items,
				list -> {
					Table table = new Table("UUID", "USERNAME", "EMAIL");
					for (UserResponse user : list) {
						table.row(String.valueOf(user.getUuid()), user.getUsername(),
							user.getEmail() == null ? "" : user.getEmail());
					}
					return table;
				},
				user -> String.valueOf(user.getUuid()));
			return ExitCode.OK;
		}
	}

	// ── group ────────────────────────────────────────────────────────────

	@Singleton
	@Command(name = "group", description = "Groups.", subcommands = GroupList.class)
	public static class GroupGroup extends AbstractGroup {

		@Inject
		public GroupGroup() {
		}
	}

	@Singleton
	@Command(name = "list", aliases = "ls", description = "List groups.")
	public static class GroupList extends AbstractCliCommand {

		@Inject
		public GroupList() {
		}

		@Override
		protected Integer execute() {
			List<GroupResponse> items = api().listGroups();
			return printNamed(this, items, g -> String.valueOf(g.getUuid()), GroupResponse::getName);
		}
	}

	// ── role ─────────────────────────────────────────────────────────────

	@Singleton
	@Command(name = "role", description = "Roles.", subcommands = RoleList.class)
	public static class RoleGroup extends AbstractGroup {

		@Inject
		public RoleGroup() {
		}
	}

	@Singleton
	@Command(name = "list", aliases = "ls", description = "List roles.")
	public static class RoleList extends AbstractCliCommand {

		@Inject
		public RoleList() {
		}

		@Override
		protected Integer execute() {
			List<RoleResponse> items = api().listRoles();
			return printNamed(this, items, r -> String.valueOf(r.getUuid()), RoleResponse::getName);
		}
	}
}
