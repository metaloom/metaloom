package io.metaloom.cli.dagger;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;
import io.metaloom.cli.MetaLoomCLI;
import io.metaloom.cli.cmd.auth.LoginCommand;
import io.metaloom.cli.cmd.auth.LogoutCommand;
import io.metaloom.cli.cmd.auth.WhoamiCommand;
import io.metaloom.cli.cmd.config.ConfigCommand;
import io.metaloom.cli.cmd.infra.HealthCommand;
import io.metaloom.cli.cmd.infra.VersionCommand;
import io.metaloom.cli.cmd.org.ListCommands;
import io.metaloom.cli.cmd.pipeline.PipelineCommand;
import io.metaloom.cli.cmd.pipeline.PipelineRunCommand;
import io.metaloom.cli.cmd.run.RunCommand;

/**
 * Maps each command class to its Dagger provider, for {@link DaggerCliFactory}.
 *
 * <p>Every command picocli may instantiate has to appear here. A missing entry is not a
 * compile error - the factory silently falls back to picocli's reflective default, which
 * produces a command with null dependencies and an NPE at run time. {@code CliCommandTreeTest}
 * exists to catch that.</p>
 */
@Module
public abstract class CommandModule {

	@Binds
	@IntoMap
	@ClassKey(MetaLoomCLI.class)
	abstract Object root(MetaLoomCLI command);

	// auth
	@Binds
	@IntoMap
	@ClassKey(LoginCommand.class)
	abstract Object login(LoginCommand command);

	@Binds
	@IntoMap
	@ClassKey(LogoutCommand.class)
	abstract Object logout(LogoutCommand command);

	@Binds
	@IntoMap
	@ClassKey(WhoamiCommand.class)
	abstract Object whoami(WhoamiCommand command);

	// config
	@Binds
	@IntoMap
	@ClassKey(ConfigCommand.class)
	abstract Object config(ConfigCommand command);

	@Binds
	@IntoMap
	@ClassKey(ConfigCommand.ListCommand.class)
	abstract Object configList(ConfigCommand.ListCommand command);

	@Binds
	@IntoMap
	@ClassKey(ConfigCommand.GetCommand.class)
	abstract Object configGet(ConfigCommand.GetCommand command);

	@Binds
	@IntoMap
	@ClassKey(ConfigCommand.SetCommand.class)
	abstract Object configSet(ConfigCommand.SetCommand command);

	@Binds
	@IntoMap
	@ClassKey(ConfigCommand.UseProfileCommand.class)
	abstract Object configUseProfile(ConfigCommand.UseProfileCommand command);

	@Binds
	@IntoMap
	@ClassKey(ConfigCommand.PathCommand.class)
	abstract Object configPath(ConfigCommand.PathCommand command);

	// infra
	@Binds
	@IntoMap
	@ClassKey(HealthCommand.class)
	abstract Object health(HealthCommand command);

	@Binds
	@IntoMap
	@ClassKey(VersionCommand.class)
	abstract Object version(VersionCommand command);

	// pipeline
	@Binds
	@IntoMap
	@ClassKey(PipelineCommand.class)
	abstract Object pipeline(PipelineCommand command);

	@Binds
	@IntoMap
	@ClassKey(PipelineCommand.ListCommand.class)
	abstract Object pipelineList(PipelineCommand.ListCommand command);

	@Binds
	@IntoMap
	@ClassKey(PipelineCommand.GetCommand.class)
	abstract Object pipelineGet(PipelineCommand.GetCommand command);

	@Binds
	@IntoMap
	@ClassKey(PipelineCommand.DeleteCommand.class)
	abstract Object pipelineDelete(PipelineCommand.DeleteCommand command);

	@Binds
	@IntoMap
	@ClassKey(PipelineRunCommand.class)
	abstract Object pipelineRun(PipelineRunCommand command);

	// run
	@Binds
	@IntoMap
	@ClassKey(RunCommand.class)
	abstract Object run(RunCommand command);

	@Binds
	@IntoMap
	@ClassKey(RunCommand.ListCommand.class)
	abstract Object runList(RunCommand.ListCommand command);

	@Binds
	@IntoMap
	@ClassKey(RunCommand.GetCommand.class)
	abstract Object runGet(RunCommand.GetCommand command);

	@Binds
	@IntoMap
	@ClassKey(RunCommand.ItemsCommand.class)
	abstract Object runItems(RunCommand.ItemsCommand command);

	@Binds
	@IntoMap
	@ClassKey(RunCommand.FollowCommand.class)
	abstract Object runFollow(RunCommand.FollowCommand command);

	@Binds
	@IntoMap
	@ClassKey(RunCommand.PauseCommand.class)
	abstract Object runPause(RunCommand.PauseCommand command);

	@Binds
	@IntoMap
	@ClassKey(RunCommand.ResumeCommand.class)
	abstract Object runResume(RunCommand.ResumeCommand command);

	@Binds
	@IntoMap
	@ClassKey(RunCommand.CancelCommand.class)
	abstract Object runCancel(RunCommand.CancelCommand command);

	@Binds
	@IntoMap
	@ClassKey(RunCommand.StatsCommand.class)
	abstract Object runStats(RunCommand.StatsCommand command);

	// org / iam
	@Binds
	@IntoMap
	@ClassKey(ListCommands.SpaceGroup.class)
	abstract Object space(ListCommands.SpaceGroup command);

	@Binds
	@IntoMap
	@ClassKey(ListCommands.SpaceList.class)
	abstract Object spaceList(ListCommands.SpaceList command);

	@Binds
	@IntoMap
	@ClassKey(ListCommands.LibraryGroup.class)
	abstract Object library(ListCommands.LibraryGroup command);

	@Binds
	@IntoMap
	@ClassKey(ListCommands.LibraryList.class)
	abstract Object libraryList(ListCommands.LibraryList command);

	@Binds
	@IntoMap
	@ClassKey(ListCommands.PoolGroup.class)
	abstract Object pool(ListCommands.PoolGroup command);

	@Binds
	@IntoMap
	@ClassKey(ListCommands.PoolList.class)
	abstract Object poolList(ListCommands.PoolList command);

	@Binds
	@IntoMap
	@ClassKey(ListCommands.UserGroup.class)
	abstract Object user(ListCommands.UserGroup command);

	@Binds
	@IntoMap
	@ClassKey(ListCommands.UserList.class)
	abstract Object userList(ListCommands.UserList command);

	@Binds
	@IntoMap
	@ClassKey(ListCommands.GroupGroup.class)
	abstract Object group(ListCommands.GroupGroup command);

	@Binds
	@IntoMap
	@ClassKey(ListCommands.GroupList.class)
	abstract Object groupList(ListCommands.GroupList command);

	@Binds
	@IntoMap
	@ClassKey(ListCommands.RoleGroup.class)
	abstract Object role(ListCommands.RoleGroup command);

	@Binds
	@IntoMap
	@ClassKey(ListCommands.RoleList.class)
	abstract Object roleList(ListCommands.RoleList command);
}
