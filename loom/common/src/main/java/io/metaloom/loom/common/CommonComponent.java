package io.metaloom.loom.common;

import javax.inject.Singleton;

import dagger.Component;
import io.metaloom.loom.common.dagger.LoomModule;
import io.metaloom.loom.common.dagger.VertxModule;

@Singleton
@Component(modules = {
	LoomModule.class,
	VertxModule.class
})
public interface CommonComponent {

}
