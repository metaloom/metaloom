package io.metaloom.loom.test.data;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.utils.hash.ChunkHash;
import io.metaloom.utils.hash.MD5;
import io.metaloom.utils.hash.SHA256;
import io.metaloom.utils.hash.SHA512;

public interface TestValues {

	public static final SHA512 SHA512SUM = SHA512.fromString(
		"dbf5dae6e3825ac5b2b595a37b3945a91bb67e9dad85c1e4ff5c3fdb2948b1100d71dc4e1ebd9a854928c34c5f71a81cd0f258f4652aee3aa2345742177ba3e7");

	public static final SHA512 SHA512SUM_2 = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	public static final SHA512 SHA512SUM_3 = SHA512.fromString(
		"0e3e75234abc68f4378a86b3f4b32a198ba301845b0cd6e50106e874345700cc6663a86c1ea125dc5e92be17c98f9a0f85ca9d5f595db2012f7cc3571945c123");

	public static final SHA256 SHA256SUM = SHA256.fromString("d7e2763589f1c2f393cc44414c5c7595cd711ba9c3284894227b0304c8c29e57");

	public static final MD5 MD5SUM = MD5.fromString("f29192026939e824b7763507af02c06f");

	public static final MD5 MD5SUM_2 = MD5.fromString("f29192026939e824b7763507af02c06g");

	public static final Instant DATE_OLD = Instant.parse("2018-10-12T14:15:06Z");

	public static final Instant DATE_NEW = Instant.parse("2023-04-20T20:12:01Z");

	public static final Instant DATE2_NEW = Instant.parse("2023-04-20T20:12:01Z");

	public static final String INITIAL_ORIGIN = "/tank/images/blume.jpg";

	public static final String IMAGE_MIMETYPE = "image/jpeg";

	public static final String VIDEO_MIMETYPE = "video/mp4";

	public static final String COLLECTION_NAME = "dummy-collection";

	public static final String COLLECTION_NAME_2 = "dummy-collection-2";

	public static final String AUDIO_FINGERPRINT_STR = "0002000100ffdfffdfdfdfffdfdf9ffd9fff9f193f007800780078807810b806e83e8718017d";

	public static final Float[] AUDIO_FINGERPRINT = { 0.42f, 0.24f, 0.44f, 21.5f };

	public static final String VIDEO_FINGERPRINT_STR = "0002000100ffdfffdfdfdfffdfdf9ffd9fff9f193f007800780078807810b806e83e8718017d";

	public static final Float[] VIDEO_FINGERPRINT = { 0.42f, 0.24f, 0.44f, 21.5f };

	public static final String DOMINANT_COLOR = "#fff";

	public static final ChunkHash VIDEO_CHUNK_HASH = ChunkHash.fromString("41e705d6dc411b7444d760ceba3765d3d47397dbef3deb3260a86f0eecb41e94");

	public static final String LIBRARY_NAME = "test-library";

	public static final String LIBRARY_NAME_2 = "test-library-2";

	public static final String DUMMY_IMAGE_FILENAME = "blume.jpg";

	public static final String DUMMY_VIDEO_FILENAME = "video.mp4";

	public static final String DUMMY_IMAGE_ORIGIN = "/tank/images/blume.jpg";

	public static final Float[] VECTOR_DATA = { 0.42f, 0.24f, 0.44f, 21.5f };

	public static final long VECTOR_ID = 1;

	public static final UUID ADMIN_UUID = UUID.fromString("f91e1dbf-4077-4091-828e-7f0547f08cfc");

	public static final UUID ATTACHMENT_UUID = UUID.fromString("34191313-92b8-43b2-9ede-155cb68a6c02");

	public static final UUID USER_UUID = UUID.fromString("6793ad7e-200c-432b-8e64-62b9f9544ece");

	public static final UUID GROUP_UUID = UUID.fromString("7a50a663-492e-44f7-a62b-b6c87f36046f");

	public static final UUID ROLE_UUID = UUID.fromString("d11cf021-d06c-4327-9f85-024f5fa3811d");

	public static final UUID WEBHOOK_UUID = UUID.fromString("6635ac54-5a27-4d15-904e-8a74c7ac5bb0");

	public static final UUID TOKEN_UUID = UUID.fromString("d56b09a5-70a8-442a-9a28-58595a8ef6cd");

	public static final UUID LIBRARY_UUID = UUID.fromString("ef5702dd-0dcb-404b-86d7-9982ad28d6c1");

	public static final UUID PROJECT_UUID = UUID.fromString("7d784065-7953-4702-813f-36e231b67283");

	public static final UUID TASK_UUID = UUID.fromString("c3005050-e386-4228-90be-cab874a46fd9");

	public static final UUID TAG_UUID = UUID.fromString("f179ea69-a2ef-43d9-99d8-1028756c88e2");

	public static final UUID COLLECTION_UUID = UUID.fromString("5293b186-64e9-4713-8565-97d6a5ac22a6");

	public static final UUID REACTION_1_UUID = UUID.fromString("b24b03d3-67e3-482a-8bcc-fbf3eb6fefd1");

	public static final UUID REACTION_2_UUID = UUID.fromString("213295ec-a851-4257-b687-1a358498a642");

	public static final UUID REACTION_3_UUID = UUID.fromString("110ed402-ffca-490d-b9c6-426e96defc4f");

	public static final UUID REACTION_4_UUID = UUID.fromString("daacdacb-4a3d-45eb-81a1-2c578c329cf6");

	public static final UUID ASSET_LOCATION_UUID = UUID.fromString("941dd6a5-07c3-432d-b54e-aab546a74dcf");

	public static final UUID ASSET_UUID = UUID.fromString("56ac17f2-a003-413a-ad38-303a05808eb7");

	public static final SHA512 ASSET_SHA512SUM = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	public static final UUID EMBEDDING_UUID = UUID.fromString("daf9c614-524d-4215-a9da-6ca7451f342a");

	public static final UUID CLUSTER_UUID = UUID.fromString("82e53069-e0e3-4576-852c-26ccaa77dfd1");

	public static final UUID ANNOTATION_UUID = UUID.fromString("fa086950-2857-496d-b414-d07acac48ea2");

	public static final UUID BLACKLIST_UUID = UUID.fromString("471be5bd-d7e6-4719-b43c-57d6b30e7c2f");

}
