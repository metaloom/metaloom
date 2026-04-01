package io.metaloom.loom.test.integration;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.cortex.cli.CortexCLIMain;
import io.metaloom.loom.api.Loom;
import io.metaloom.loom.api.options.DatabaseOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.LoomOptionsLookup;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.test.LoomProviderExtension;

public abstract class AbstractIntegrationTest {

	@RegisterExtension
	public static LoomProviderExtension provider = LoomProviderExtension.create();

	public void loginAdmin(LoomHttpClient client) throws LoomClientException {
		AuthLoginResponse loginResponse = client.login("admin", "finger").sync();
		client.setToken(loginResponse.getToken());
	}

	protected LoomHttpClient httpClient(Loom server) {
		return LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofHours(2))
			.setPort(server.actualRestPort()).build();
	}

	protected LoomOptions loomOptions() {
		LoomOptions options = new LoomOptions();
		options.getAuth().setKeystorePassword("finger");
		DatabaseOptions dbOptions = LoomExtensionHelper.toOptions(provider.db());
		options.setDatabase(dbOptions);
		return options;
	}

	protected int cortex(String... args) {
		List<String> list = new ArrayList<>();
		list.addAll(Arrays.asList("--hostname", "localhost", "--port", String.valueOf(loomOptions().getServer().getGrpcPort())));
		list.addAll(Arrays.asList(args));
		String[] array = list.toArray(new String[0]);
		return CortexCLIMain.execute(null, array);
	}

	protected Loom loomServer() {
		// TODO randomize config folder to avoid collisions
		File baseFolder = new File("target", "test-config");
		LoomOptionsLookup lookup = new LoomOptionsLookup(baseFolder, loomOptions());
		Loom loom = Loom.create(lookup);
		return loom;
	}

}
