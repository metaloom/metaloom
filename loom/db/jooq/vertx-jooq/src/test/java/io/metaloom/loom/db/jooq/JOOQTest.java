package io.metaloom.loom.db.jooq;

import java.util.Optional;
import java.util.UUID;

import org.jooq.Configuration;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;

import io.github.jklingsporn.vertx.jooq.rx.reactivepg.ReactiveRXGenericQueryExecutor;
import io.metaloom.loom.db.jooq.tables.daos.UserDao;
import io.metaloom.loom.db.jooq.tables.pojos.User;
import io.reactivex.Single;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.reactivex.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;

public class JOOQTest {

	public static void main(String[] args) {

		// Setup your jOOQ configuration
		Configuration configuration = new DefaultConfiguration();
		configuration.set(SQLDialect.POSTGRES);
		// no other DB-Configuration necessary because jOOQ is only used to render our statements - not for execution

		// setup Vertx
		Vertx vertx = Vertx.vertx();
		io.vertx.reactivex.core.Vertx rxVertx = io.vertx.reactivex.core.Vertx.newInstance(vertx);

		// setup the client
		PgConnectOptions config = new PgConnectOptions().setHost("127.0.0.1").setPort(5432).setUser("postgres").setDatabase("loom")
			.setPassword("finger");
		PgPool client = PgPool.pool(vertx, config, new PoolOptions().setMaxSize(32));
		Pool rxPgClient = new io.vertx.reactivex.sqlclient.Pool(client);
		// instantiate a DAO (which is generated for you)
		UserDao dao = new UserDao(configuration, rxPgClient);

		User newUser = new User();
		newUser.setUsername("ABCD" + System.currentTimeMillis());
		dao.insert(newUser).blockingGet();

		UUID userUUID = UUID.fromString("9d4d61e7-44bd-4de7-ab66-04914cdcfce6");
		Single<Optional<User>> locate = dao.findOneById(userUUID)
			.doOnEvent((opt, x) -> {
				if (x == null) {
					opt.ifPresent(foundUser -> {
						System.out.println("Record found!" + foundUser.getUsername());
						vertx.eventBus().send("sendSomething", foundUser.toJson());
					});
				} else {
					System.err.println("Something failed badly: " + x.getMessage());
				}
			});

		// maybe consume it in another verticle

		rxVertx.eventBus().<JsonObject>consumer("sendSomething", jsonEvent -> {
			JsonObject message = jsonEvent.body();
			System.out.println("Got Event:\n" + message.encodePrettily());
			// Convert it back into a POJO...
			// Something something = new Something(message);
			// ... change some values
			// something.setSomeregularnumber(456);
			// ... and update it into the DB
			User user = new User();
			String uuid = jsonEvent.body().getString("uuid");
			System.out.println("UUID: " + uuid);
			user.setUuid(userUUID);
			user.setEnabled(true);
			user.setUsername("hallo");
			Single<Integer> updatedFuture = dao.update(user);
			updatedFuture.subscribe(e -> {
				System.out.println("Done event: " + e);
			}, err -> {
				err.printStackTrace();
			});

		});

		UUID userUuid = UUID.randomUUID();

		// or do you prefer writing your own type-safe SQL?
		ReactiveRXGenericQueryExecutor queryExecutor = new ReactiveRXGenericQueryExecutor(configuration, rxPgClient);
		Single<Integer> updatedCustomFuture = queryExecutor.execute(dslContext -> dslContext
			.update(Tables.USER)
			.set(Tables.USER.USERNAME, "ABC")
			.where(Tables.USER.UUID.eq(userUuid)));

		// check for completion
		updatedCustomFuture.doOnEvent((updated, x) -> {
			if (x == null) {
				System.out.println("Rows updated: " + updated);
			} else {
				System.err.println("Something failed badly: " + x.getMessage());
			}
		});

		locate.ignoreElement().andThen(updatedCustomFuture.ignoreElement()).subscribe(() -> {
			System.out.println("Done");
		}, err -> {
			err.printStackTrace();
		});
	}

}
