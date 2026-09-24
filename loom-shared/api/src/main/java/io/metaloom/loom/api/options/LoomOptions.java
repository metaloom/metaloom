package io.metaloom.loom.api.options;

import io.metaloom.loom.api.error.ConfigurationValidationException;

public class LoomOptions implements Option {

	private DatabaseOptions database = new DatabaseOptions();

	private ServerOptions server = new ServerOptions();

	private AuthenticationOptions auth = new AuthenticationOptions();

	private StorageOptions storage = new StorageOptions();

	private S3Options s3 = new S3Options();

	private AiOptions ai = new AiOptions();

	private SandboxOptions sandbox = new SandboxOptions();

	private MemoryOptions memory = new MemoryOptions();

	private NodeExecOptions nodeExec = new NodeExecOptions();

	private ImageGenToolOptions imageGenTool = new ImageGenToolOptions();

	private ChatAttachmentOptions chatAttachment = new ChatAttachmentOptions();

	private SearchOptions search = new SearchOptions();

	private SimilarityOptions similarity = new SimilarityOptions();

	private MediaOptions media = new MediaOptions();

	private VectorIndexOptions vectorIndex = new VectorIndexOptions();

	private AssetGraphOptions assetGraph = new AssetGraphOptions();

	private DemoOptions demo = new DemoOptions();

	@Override
	public void overrideWithEnv() {
		database.overrideWithEnv();
		server.overrideWithEnv();
		auth.overrideWithEnv();
		storage.overrideWithEnv();
		s3.overrideWithEnv();
		ai.overrideWithEnv();
		sandbox.overrideWithEnv();
		memory.overrideWithEnv();
		nodeExec.overrideWithEnv();
		imageGenTool.overrideWithEnv();
		chatAttachment.overrideWithEnv();
		search.overrideWithEnv();
		similarity.overrideWithEnv();
		vectorIndex.overrideWithEnv();
		assetGraph.overrideWithEnv();
		demo.overrideWithEnv();
	}

	public DatabaseOptions getDatabase() {
		return database;
	}

	public LoomOptions setDatabase(DatabaseOptions database) {
		this.database = database;
		return this;
	}

	public ServerOptions getServer() {
		return server;
	}

	public LoomOptions setServer(ServerOptions server) {
		this.server = server;
		return this;
	}

	public AuthenticationOptions getAuth() {
		return auth;
	}

	public void setAuth(AuthenticationOptions auth) {
		this.auth = auth;
	}

	public StorageOptions getStorage() {
		return storage;
	}

	public LoomOptions setStorage(StorageOptions storage) {
		this.storage = storage;
		return this;
	}

	public S3Options getS3() {
		return s3;
	}

	public LoomOptions setS3(S3Options s3) {
		this.s3 = s3;
		return this;
	}

	public AiOptions getAi() {
		return ai;
	}

	public LoomOptions setAi(AiOptions ai) {
		this.ai = ai;
		return this;
	}

	public SandboxOptions getSandbox() {
		return sandbox;
	}

	public LoomOptions setSandbox(SandboxOptions sandbox) {
		this.sandbox = sandbox;
		return this;
	}

	public MemoryOptions getMemory() {
		return memory;
	}

	public LoomOptions setMemory(MemoryOptions memory) {
		this.memory = memory;
		return this;
	}

	public NodeExecOptions getNodeExec() {
		return nodeExec;
	}

	public LoomOptions setNodeExec(NodeExecOptions nodeExec) {
		this.nodeExec = nodeExec;
		return this;
	}

	public ImageGenToolOptions getImageGenTool() {
		return imageGenTool;
	}

	public LoomOptions setImageGenTool(ImageGenToolOptions imageGenTool) {
		this.imageGenTool = imageGenTool;
		return this;
	}

	public ChatAttachmentOptions getChatAttachment() {
		return chatAttachment;
	}

	public LoomOptions setChatAttachment(ChatAttachmentOptions chatAttachment) {
		this.chatAttachment = chatAttachment;
		return this;
	}

	public SearchOptions getSearch() {
		return search;
	}

	public LoomOptions setSearch(SearchOptions search) {
		this.search = search;
		return this;
	}

	public SimilarityOptions getSimilarity() {
		return similarity;
	}

	public LoomOptions setSimilarity(SimilarityOptions similarity) {
		this.similarity = similarity;
		return this;
	}

	public MediaOptions getMedia() {
		return media;
	}

	public LoomOptions setMedia(MediaOptions media) {
		this.media = media;
		return this;
	}

	public VectorIndexOptions getVectorIndex() {
		return vectorIndex;
	}

	public AssetGraphOptions getAssetGraph() {
		return assetGraph;
	}

	public LoomOptions setAssetGraph(AssetGraphOptions assetGraph) {
		this.assetGraph = assetGraph;
		return this;
	}

	public LoomOptions setVectorIndex(VectorIndexOptions vectorIndex) {
		this.vectorIndex = vectorIndex;
		return this;
	}

	public DemoOptions getDemo() {
		return demo;
	}

	public LoomOptions setDemo(DemoOptions demo) {
		this.demo = demo;
		return this;
	}

	@Override
	public void validate(OptionErrors errors) {
		errors.nested("database", database)
			.nested("server", server)
			.nested("auth", auth)
			.nested("storage", storage)
			.nested("s3", s3)
			.nested("ai", ai)
			.nested("sandbox", sandbox)
			.nested("memory", memory)
			.nested("nodeExec", nodeExec)
			.nested("imageGenTool", imageGenTool)
			.nested("chatAttachment", chatAttachment)
			.nested("search", search)
			.nested("similarity", similarity)
			.nested("media", media)
			.nested("vectorIndex", vectorIndex)
			.nested("assetGraph", assetGraph)
			.nested("demo", demo);
	}

	/**
	 * Validate the whole option tree and fail with a single exception listing every detected problem.
	 *
	 * @throws ConfigurationValidationException
	 *             when at least one setting is missing or invalid
	 */
	public void validate() {
		OptionErrors errors = new OptionErrors();
		validate(errors);
		errors.throwOnError();
	}

}
