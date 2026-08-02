package io.metaloom.cortex.cli.dagger;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dagger.Module;
import dagger.Provides;
import io.metaloom.cortex.cloud.CloudMediaReferenceResolver;
import io.metaloom.cortex.cloud.CloudSupportRegistry;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.common.media.MediaReferenceResolver;
import io.metaloom.cortex.common.media.SchemeMediaReferenceResolver;
import io.metaloom.cortex.common.media.SchemeMediaReferenceResolver.SchemeResolver;
import io.metaloom.cortex.s3.S3MediaReferenceResolver;
import io.metaloom.cortex.s3.S3Support;

/**
 * Provides the one {@link MediaReferenceResolver} the worker resolves every media reference
 * through.
 *
 * <p>This binding used to live in {@code S3Module} as an {@code if/else}, which was honest while
 * {@code s3://} was the only remote scheme. With cloud drives added it would have made the S3
 * module depend on cloud support, so it moved here instead - the module that owns "how a reference
 * becomes media", regardless of where the bytes are.</p>
 *
 * <p>Note what a worker with nothing remote configured gets: literally
 * {@code new MediaReferenceResolver(mediaLoader)}, the same object as before any of this existed.
 * The composite only appears when there is something to compose.</p>
 */
@Module
public abstract class MediaResolverModule {

	private static final Logger log = LoggerFactory.getLogger(MediaResolverModule.class);

	@Provides
	@Singleton
	static MediaReferenceResolver provideMediaReferenceResolver(LoomMediaLoader mediaLoader, S3Support s3,
		CloudSupportRegistry cloud) {

		List<SchemeResolver> branches = new ArrayList<>();
		if (s3 != null && s3.isActive()) {
			branches.add(new S3MediaReferenceResolver(mediaLoader, s3.materializer()));
		}
		if (cloud != null && cloud.isAnyActive()) {
			branches.add(new CloudMediaReferenceResolver(mediaLoader, cloud));
			log.debug("Cloud media resolution is active for {}", cloud.activeProviders());
		}

		if (branches.isEmpty()) {
			return new MediaReferenceResolver(mediaLoader);
		}
		return new SchemeMediaReferenceResolver(mediaLoader, branches);
	}
}
