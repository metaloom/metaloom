package io.metaloom.loom.rest.openapi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.metaloom.loom.api.LoomVersion;
import io.metaloom.loom.api.options.AuthenticationOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.RESTService;
import io.metaloom.loom.rest.ServerFailureHandler;
import io.metaloom.loom.rest.TraceIdHandler;
import io.metaloom.loom.rest.endpoint.RESTEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AnnotationEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AssetBinaryEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AssetComponentEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AssetEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AssetPoolEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AttachmentEndpoint;
import io.metaloom.loom.rest.endpoint.impl.BlacklistEndpoint;
import io.metaloom.loom.rest.endpoint.impl.ChatEndpoint;
import io.metaloom.loom.rest.endpoint.impl.ClusterEndpoint;
import io.metaloom.loom.rest.endpoint.impl.CollectionEndpoint;
import io.metaloom.loom.rest.endpoint.impl.RemixEndpoint;
import io.metaloom.loom.rest.endpoint.impl.PublicShareEndpoint;
import io.metaloom.loom.rest.endpoint.impl.FailureReportEndpoint;
import io.metaloom.loom.rest.endpoint.impl.ShareLinkEndpoint;
import io.metaloom.loom.rest.endpoint.impl.CommentEndpoint;
import io.metaloom.loom.rest.endpoint.impl.DedupGroupEndpoint;
import io.metaloom.loom.rest.endpoint.impl.DetectionEndpoint;
import io.metaloom.loom.rest.endpoint.impl.EmbeddingEndpoint;
import io.metaloom.loom.rest.endpoint.impl.GraphQLEndpoint;
import io.metaloom.loom.rest.endpoint.impl.GroupEndpoint;
import io.metaloom.loom.rest.endpoint.impl.HealthEndpoint;
import io.metaloom.loom.rest.endpoint.impl.LibraryEndpoint;
import io.metaloom.loom.rest.endpoint.impl.LoginEndpoint;
import io.metaloom.loom.rest.endpoint.impl.MeEndpoint;
import io.metaloom.loom.rest.endpoint.impl.DbIntegrityEndpoint;
import io.metaloom.loom.rest.endpoint.impl.StorageEndpoint;
import io.metaloom.loom.rest.endpoint.impl.MetricsEndpoint;
import io.metaloom.loom.rest.endpoint.impl.NodeDescriptorEndpoint;
import io.metaloom.loom.rest.endpoint.impl.OAuth2Endpoint;
import io.metaloom.loom.rest.endpoint.impl.PersonEndpoint;
import io.metaloom.loom.rest.endpoint.impl.PipelineEndpoint;
import io.metaloom.loom.rest.endpoint.impl.PipelineEventEndpoint;
import io.metaloom.loom.rest.endpoint.impl.ProcessorEndpoint;
import io.metaloom.loom.rest.endpoint.impl.RESTInfoEndpoint;
import io.metaloom.loom.rest.endpoint.impl.ReactionEndpoint;
import io.metaloom.loom.rest.endpoint.impl.RoleEndpoint;
import io.metaloom.loom.rest.endpoint.impl.NodeRunEndpoint;
import io.metaloom.loom.rest.endpoint.impl.NotificationEndpoint;
import io.metaloom.loom.rest.endpoint.impl.SkillEndpoint;
import io.metaloom.loom.rest.endpoint.impl.SpaceEndpoint;
import io.metaloom.loom.rest.endpoint.impl.SearchEndpoint;
import io.metaloom.loom.rest.endpoint.impl.SearchIndexEndpoint;
import io.metaloom.loom.rest.endpoint.impl.SimilarityIndexEndpoint;
import io.metaloom.loom.rest.endpoint.impl.VectorIndexEndpoint;
import io.metaloom.loom.rest.endpoint.impl.TagEndpoint;
import io.metaloom.loom.rest.endpoint.impl.TaskEndpoint;
import io.metaloom.loom.rest.endpoint.impl.TokenEndpoint;
import io.metaloom.loom.rest.endpoint.impl.UserEndpoint;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.vertx.openapi.OpenAPIGenerator;
import io.metaloom.vertx.openapi.OpenAPIGenerator.Builder;
import io.metaloom.vertx.router.ApiRouter;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityScheme.In;
import io.swagger.v3.oas.models.security.SecurityScheme.Type;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;

/**
 * Generator for the OpenAPI document of the Loom REST API.
 *
 * <p>
 * The document is derived from the routes the endpoints register on the {@link ApiRouter} - it is never written by hand. Two entry points exist:
 *
 * <ul>
 * <li>{@link #generateJson()} / {@link #generateYaml()} build a throw-away router from all endpoints of this module (plus any handed in via the
 * extra endpoint factory) so the spec can be generated offline, without a database. This is what the {@code loom-doc} generator and the website
 * use.</li>
 * <li>{@link #describe(ApiRouter, String)} describes an already wired router - used by {@code RESTInfoEndpoint} to serve the spec of the running
 * server.</li>
 * </ul>
 *
 * <p>
 * The raw output of the external {@code OpenAPIGenerator} is only a route dump: it uses the Vert.x {@code :param} path syntax, has no path parameters,
 * no tags, and no security schemes. {@link #polish(OpenAPI, String)} turns that into a document a client generator or Swagger UI can actually work
 * with.
 */
public class LoomOpenAPI {

	public static final String TITLE = "MetaLoom // Loom REST API";

	public static final String DESCRIPTION = """
		REST API of the MetaLoom Loom server - the asset library, its metadata model and the \
		pipeline engine that drives the Cortex processing nodes.

		All routes are mounted under `/api/v1`. Requests are authenticated with a JWT which is \
		obtained from `POST /api/v1/login` and is sent either as an `Authorization: Bearer <token>` \
		header or as the `__Host-loom_token` cookie the login route sets.

		This document is generated from the endpoint registry of the server - see the REST API \
		documentation on https://metaloom.io/docs/loom/rest-api/.""";

	/**
	 * Fallback server URL used when the spec is generated offline (no running server to ask for its own address).
	 */
	public static final String DEFAULT_BASE_URL = "http://localhost:8092";

	private static final String BEARER_AUTH = "bearerAuth";

	private static final String COOKIE_AUTH = "cookieAuth";

	/**
	 * Route prefixes which are reachable without a token. Everything else is documented as requiring authentication.
	 */
	private static final List<String> PUBLIC_PATHS = List.of(
		"/api/v1/login",
		"/api/v1/auth/oauth2",
		"/api/v1/health",
		"/api/v1/openapi",
		// The customer-facing share area. Unauthenticated by design - a share link is opened by somebody with no
		// Loom account - so declaring bearerAuth on these operations would make a spec viewer demand a token for
		// routes that must never see one. They are not unprotected: every one of them is authorized by
		// ShareAccessService against the share row, using the session token issued by POST /shares/{slug}/sessions.
		// Note this covers /shares and NOT /share-links, which is the owner-facing CRUD and is fully secured.
		"/api/v1/shares");

	/**
	 * Human readable descriptions for the generated tags. A tag which is not listed here is still emitted - it just carries no description.
	 */
	private static final Map<String, String> TAG_DESCRIPTIONS = tagDescriptions();

	private final Function<EndpointDependencies, Collection<RESTEndpoint>> extraEndpoints;

	public LoomOpenAPI() {
		this(deps -> Collections.emptyList());
	}

	/**
	 * @param extraEndpoints
	 *            factory for endpoints which live outside of the rest module (e.g. the chat and memory endpoints of the agent modules) and can
	 *            therefore not be referenced from here. It is handed the same {@link EndpointDependencies} - and thus the same router - the endpoints
	 *            of this module are registered on.
	 */
	public LoomOpenAPI(Function<EndpointDependencies, Collection<RESTEndpoint>> extraEndpoints) {
		this.extraEndpoints = extraEndpoints;
	}

	public OpenAPI generate() {
		Vertx vertx = Vertx.vertx();
		try {
			HttpServer server = vertx.createHttpServer();
			LoomOptions options = new LoomOptions();
			ApiRouter router = ApiRouter.create(vertx);
			EndpointDependencies deps = new EndpointDependencies(vertx, router, null, null);
			Set<RESTEndpoint> endpoints = endpoints(deps);
			endpoints.addAll(extraEndpoints.apply(deps));
			ServerFailureHandler failureHandler = null;
			// The trace handler is real rather than null, unlike the rest: setupRouter() installs it as a route
			// handler, and Vert.x rejects a null handler outright. It is also cheap and stateless.
			TraceIdHandler traceIdHandler = new TraceIdHandler();
			// Only the router is needed to describe the API; start() - which is what would
			// use the reapers - is never called here.
			RESTService rest = new RESTService(vertx, options, server, router, endpoints, failureHandler, traceIdHandler, null, null, null, null);
			rest.setupRouter();
			return describe(router, DEFAULT_BASE_URL);
		} finally {
			vertx.close();
		}
	}

	public String generateJson() throws JsonProcessingException {
		return Json.pretty(generate());
	}

	public String generateYaml() throws JsonProcessingException {
		return Yaml.pretty(generate());
	}

	/**
	 * Describe the routes of the given (already registered) router.
	 *
	 * @param router
	 *            router carrying the registered API routes
	 * @param baseUrl
	 *            server URL to advertise in the spec
	 * @return the polished OpenAPI document
	 */
	public static OpenAPI describe(ApiRouter router, String baseUrl) {
		Builder builder = OpenAPIGenerator.builder();
		builder.title(TITLE);
		builder.baseUrl(baseUrl);
		builder.version(version());
		builder.description(DESCRIPTION);
		builder.apiRouter(router);
		try {
			return polish(builder.generate(), baseUrl);
		} catch (JsonProcessingException e) {
			throw new RuntimeException("Could not generate the OpenAPI document", e);
		}
	}

	/**
	 * All endpoints of the rest module, constructed with null services. Only {@code register()} is invoked on them and that never touches a service -
	 * the services are only dereferenced from within the request handlers.
	 *
	 * @param deps
	 * @return
	 */
	private static Set<RESTEndpoint> endpoints(EndpointDependencies deps) {
		ModelExamples examples = new ModelExamples();
		Set<RESTEndpoint> endpoints = new LinkedHashSet<>();
		endpoints.add(new AnnotationEndpoint(null, null, null, null, deps, examples));
		endpoints.add(new AssetBinaryEndpoint(null, deps, examples));
		endpoints.add(new AssetComponentEndpoint(null, deps, examples));
		endpoints.add(
			new AssetEndpoint(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, deps,
				examples));
		endpoints.add(new AssetPoolEndpoint(null, deps, examples));
		endpoints.add(new AttachmentEndpoint(null, deps, examples));
		endpoints.add(new BlacklistEndpoint(null, deps, examples));
		endpoints.add(new ChatEndpoint(null, deps, examples));
		endpoints.add(new ClusterEndpoint(null, deps, examples));
		endpoints.add(new CollectionEndpoint(null, null, deps, examples));
		endpoints.add(new RemixEndpoint(null, deps, examples));
		endpoints.add(new FailureReportEndpoint(null, deps, examples));
		endpoints.add(new ShareLinkEndpoint(null, deps, examples));
		endpoints.add(new PublicShareEndpoint(null, deps, examples));
		endpoints.add(new CommentEndpoint(null, null, deps, examples));
		endpoints.add(new DedupGroupEndpoint(null, deps));
		endpoints.add(new DetectionEndpoint(null, deps, examples));
		endpoints.add(new EmbeddingEndpoint(null, deps, examples));
		endpoints.add(new GraphQLEndpoint(deps, null));
		endpoints.add(new GroupEndpoint(null, deps, examples));
		endpoints.add(new HealthEndpoint(deps, null));
		endpoints.add(new LibraryEndpoint(null, deps, examples));
		endpoints.add(new LoginEndpoint(deps, null));
		endpoints.add(new MeEndpoint(null, deps, examples));
		endpoints.add(new MetricsEndpoint(null, examples, deps));
		endpoints.add(new DbIntegrityEndpoint(null, examples, deps));
		endpoints.add(new StorageEndpoint(null, examples, deps));
		endpoints.add(new NodeDescriptorEndpoint(null, null, deps));
		endpoints.add(new OAuth2Endpoint(deps, null));
		endpoints.add(new PersonEndpoint(null, deps, examples));
		endpoints.add(new PipelineEndpoint(null, deps, examples));
		endpoints.add(new PipelineEventEndpoint(null, null, deps));
		endpoints.add(new ProcessorEndpoint(null, null, null, null, deps, examples, null, null, null));
		endpoints.add(new ReactionEndpoint(null, deps, examples));
		endpoints.add(new RESTInfoEndpoint(deps, null));
		endpoints.add(new RoleEndpoint(null, deps, examples));
		endpoints.add(new NodeRunEndpoint(null, deps, examples));
		endpoints.add(new NotificationEndpoint(null, deps, examples));
		endpoints.add(new SkillEndpoint(null, deps, examples));
		endpoints.add(new SpaceEndpoint(null, deps, examples));
		// Search, similarity-index and search-indices were absent here while being wired in
		// EndpointModule, so openapi.json silently omitted five live routes and the Python client
		// parity test carried them as known exceptions. They are documented like every other route.
		endpoints.add(new SearchEndpoint(null, deps, examples));
		endpoints.add(new SearchIndexEndpoint(null, deps, examples));
		endpoints.add(new SimilarityIndexEndpoint(null, deps));
		endpoints.add(new VectorIndexEndpoint(null, deps));
		endpoints.add(new TagEndpoint(null, deps, examples));
		endpoints.add(new TaskEndpoint(null, null, null, deps, examples));
		endpoints.add(new TokenEndpoint(null, deps, examples));
		endpoints.add(new UserEndpoint(null, deps, examples));
		return endpoints;
	}

	/**
	 * Turn the raw route dump of the external generator into a usable OpenAPI document.
	 *
	 * @param api
	 * @param baseUrl
	 * @return the same instance, modified in place
	 */
	static OpenAPI polish(OpenAPI api, String baseUrl) {
		api.setServers(servers(baseUrl));
		api.setComponents(components(api));
		api.setPaths(templatedPaths(api.getPaths()));
		api.setTags(new ArrayList<>());

		Set<String> tagNames = new TreeSet<>();
		for (Map.Entry<String, PathItem> entry : api.getPaths().entrySet()) {
			String path = entry.getKey();
			PathItem item = entry.getValue();
			item.setParameters(pathParameters(path));
			String tag = tagOf(path);
			tagNames.add(tag);
			for (Map.Entry<PathItem.HttpMethod, Operation> op : item.readOperationsMap().entrySet()) {
				polishOperation(path, op.getKey(), op.getValue(), tag);
			}
		}
		for (String tag : tagNames) {
			api.addTagsItem(new Tag().name(tag).description(TAG_DESCRIPTIONS.get(tag)));
		}

		// Secured by default - the public routes opt out individually below.
		api.setSecurity(List.of(
			new SecurityRequirement().addList(BEARER_AUTH),
			new SecurityRequirement().addList(COOKIE_AUTH)));
		return api;
	}

	private static void polishOperation(String path, PathItem.HttpMethod method, Operation op, String tag) {
		op.addTagsItem(tag);
		op.setOperationId(operationId(method, path));
		if (op.getSummary() == null && op.getDescription() != null) {
			op.setSummary(op.getDescription());
		}
		if (isPublic(path)) {
			// An empty list is how OpenAPI expresses "no authentication needed here".
			op.setSecurity(new ArrayList<>());
		}
		addErrorResponses(path, op);
		if (op.getRequestBody() != null) {
			inlineJsonExamples(op.getRequestBody().getContent());
		}
		if (op.getResponses() != null) {
			op.getResponses().values().forEach(response -> inlineJsonExamples(response.getContent()));
		}
	}

	/**
	 * The route examples reach the generator as encoded JSON strings. Left as they are, a viewer renders them as one long quoted blob instead of a
	 * browsable object, so parse them back into real JSON.
	 *
	 * @param content
	 */
	private static void inlineJsonExamples(Content content) {
		if (content == null) {
			return;
		}
		for (MediaType mediaType : content.values()) {
			if (!(mediaType.getExample() instanceof String example)) {
				continue;
			}
			String trimmed = example.trim();
			if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
				continue;
			}
			try {
				mediaType.setExample(Json.mapper().readTree(trimmed));
			} catch (JsonProcessingException e) {
				// Not JSON after all - keep the string, it is still better than nothing.
			}
		}
	}

	/**
	 * Document the error codes every endpoint can produce (see {@code ServerFailureHandler}). Only codes which the route does not already describe via
	 * an example are added.
	 *
	 * @param path
	 * @param op
	 */
	private static void addErrorResponses(String path, Operation op) {
		ApiResponses responses = op.getResponses();
		if (responses == null) {
			responses = new ApiResponses();
			op.setResponses(responses);
		}
		addResponse(responses, "400", "Bad request - validation error or malformed path/query parameter");
		if (!isPublic(path)) {
			addResponse(responses, "401", "Unauthorized - the token is missing, expired or invalid");
			addResponse(responses, "403", "Forbidden - the user lacks the required permission");
		}
		if (path.indexOf('{') != -1) {
			addResponse(responses, "404", "Not found - no such resource");
		}
		addResponse(responses, "500", "Internal server error");
	}

	private static void addResponse(ApiResponses responses, String code, String description) {
		if (responses.containsKey(code)) {
			return;
		}
		responses.addApiResponse(code, new ApiResponse()
			.description(description)
			.content(new Content().addMediaType("application/json",
				new MediaType().schema(new Schema<>().$ref("#/components/schemas/GenericMessageResponse")))));
	}

	private static List<Server> servers(String baseUrl) {
		List<Server> servers = new ArrayList<>();
		servers.add(new Server().url(baseUrl).description("Loom server"));
		if (!DEFAULT_BASE_URL.equals(baseUrl)) {
			servers.add(new Server().url(DEFAULT_BASE_URL).description("Local demo container"));
		}
		return servers;
	}

	private static Components components(OpenAPI api) {
		Components components = api.getComponents();
		if (components == null) {
			components = new Components();
		}
		components.addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
			.type(Type.HTTP)
			.scheme("bearer")
			.bearerFormat("JWT")
			.description("JWT from `POST /api/v1/login` or an API token from `/api/v1/tokens`."));
		components.addSecuritySchemes(COOKIE_AUTH, new SecurityScheme()
			.type(Type.APIKEY)
			.in(In.COOKIE)
			.name(AuthenticationOptions.TOKEN_COOKIE_KEY)
			.description("HttpOnly session cookie set by the login and OAuth2 callback routes."));
		components.addSchemas("GenericMessageResponse", new ObjectSchema()
			.description("Error and status message envelope.")
			.addProperty("message", new StringSchema().description("Human readable message")));
		return components;
	}

	/**
	 * Rewrite the Vert.x {@code /:uuid} path syntax into the OpenAPI {@code /{uuid}} template syntax and sort the paths so the generated document is
	 * stable across runs (the endpoint set is a hash set).
	 *
	 * @param paths
	 * @return
	 */
	private static Paths templatedPaths(Paths paths) {
		Map<String, PathItem> sorted = new LinkedHashMap<>();
		new TreeSet<>(paths.keySet()).forEach(path -> sorted.put(template(path), paths.get(path)));
		Paths result = new Paths();
		result.putAll(sorted);
		return result;
	}

	private static String template(String path) {
		String[] segments = path.split("/", -1);
		StringBuilder builder = new StringBuilder(path.length());
		for (int i = 0; i < segments.length; i++) {
			if (i > 0) {
				builder.append('/');
			}
			String segment = segments[i];
			if (segment.startsWith(":")) {
				builder.append('{').append(segment.substring(1)).append('}');
			} else {
				builder.append(segment);
			}
		}
		return builder.toString();
	}

	private static List<Parameter> pathParameters(String path) {
		List<Parameter> parameters = new ArrayList<>();
		for (String segment : path.split("/")) {
			if (!(segment.startsWith("{") && segment.endsWith("}"))) {
				continue;
			}
			String name = segment.substring(1, segment.length() - 1);
			parameters.add(new Parameter()
				.name(name)
				.in("path")
				.required(true)
				.description(parameterDescription(name))
				.schema(parameterSchema(name)));
		}
		return parameters.isEmpty() ? null : parameters;
	}

	private static Schema<?> parameterSchema(String name) {
		if (name.toLowerCase().endsWith("uuid")) {
			return new StringSchema().format("uuid");
		}
		if ("sha512".equals(name)) {
			return new StringSchema().pattern("^[a-fA-F0-9]{128}$");
		}
		if ("version".equals(name)) {
			return new IntegerSchema();
		}
		return new StringSchema();
	}

	private static String parameterDescription(String name) {
		return switch (name) {
			case "uuid" -> "UUID of the resource";
			case "sha512" -> "SHA-512 hash of the asset binary";
			case "version" -> "Sequential pipeline version number";
			case "kind" -> "Pipeline node kind";
			default -> "UUID of the referenced " + name.replaceAll("[Uu]uid$", "");
		};
	}

	/**
	 * Derive the tag from the first path segment below {@code /api/v1}. Deriving instead of maintaining a table keeps new endpoints grouped without
	 * anyone having to remember to register them here.
	 *
	 * @param path
	 * @return
	 */
	private static String tagOf(String path) {
		String rest = path.startsWith("/api/v1") ? path.substring("/api/v1".length()) : path;
		while (rest.startsWith("/")) {
			rest = rest.substring(1);
		}
		int slash = rest.indexOf('/');
		String segment = slash == -1 ? rest : rest.substring(0, slash);
		if (segment.isEmpty() || segment.startsWith("openapi")) {
			return "info";
		}
		return segment;
	}

	private static String operationId(PathItem.HttpMethod method, String path) {
		StringBuilder builder = new StringBuilder(method.name().toLowerCase());
		for (String segment : path.substring("/api/v1".length()).split("/")) {
			if (segment.isEmpty()) {
				continue;
			}
			String part = segment;
			if (segment.startsWith("{")) {
				String name = segment.substring(1, segment.length() - 1);
				part = "By" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
			}
			part = part.replaceAll("[^A-Za-z0-9]", "");
			if (part.isEmpty()) {
				continue;
			}
			builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return builder.toString();
	}

	private static boolean isPublic(String path) {
		return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
	}

	private static String version() {
		return LoomVersion.VERSION.replace('_', '.');
	}

	private static Map<String, String> tagDescriptions() {
		Map<String, String> map = new LinkedHashMap<>();
		map.put("annotations", "Annotations on assets, plus their reactions and tasks");
		map.put("share-links", "Share links: create, change, revoke, and read what a customer said through one");
		map.put("shares", "The customer-facing area. Unauthenticated - opened with a share link, and authorized by "
			+ "the link itself rather than by a Loom account");
		map.put("notifications", "The caller's notification inbox: list, mark read, dismiss and clear");
		map.put("assets", "Assets and their sub-resources: tags, tasks, reactions, detections, transcripts, binaries and components");
		map.put("attachments", "Binary file attachments (multipart upload and download)");
		map.put("auth", "OAuth2 (BFF) login, callback and logout");
		map.put("binaries", "Standalone binaries");
		map.put("blacklists", "Blacklisted content");
		map.put("chat-sessions", "Chat sessions - publishable snapshots of a chat's working state");
		map.put("chats", "AI agent chats and the SSE run stream");
		map.put("clusters", "Face/embedding clusters");
		map.put("remixes", "Remixes: named groups of assets that are versions of one another, and their members");
		map.put("collections", "Asset collections");
		map.put("comments", "Comments and their reactions");
		map.put("detections", "The cross-asset detection review queue. Detections themselves are created and reviewed under their asset.");
		map.put("embeddings", "Vector embeddings and their attachments");
		map.put("graphql", "GraphQL query endpoint");
		map.put("groups", "Groups of the RBAC model");
		map.put("health", "Liveness and readiness information");
		map.put("info", "API information and this OpenAPI document");
		map.put("libraries", "Asset libraries");
		map.put("login", "Username and password login");
		map.put("me", "The currently authenticated user");
		map.put("memory", "Agent memory - scopes, notes and entries");
		map.put("memory-deny-rules", "Administration of the agent memory denylist");
		map.put("persons", "Persons known to the face recognition model");
		map.put("pipeline", "Pipeline node descriptors and content types");
		map.put("pipelines", "Pipeline definitions, versions, runs and the event stream");
		map.put("pools", "Asset pools");
		map.put("processors", "Registered Cortex processor nodes");
		map.put("reactions", "Reactions");
		map.put("roles", "Roles of the RBAC model");
		map.put("sessions", "Files of a chat's coding sandbox session");
		map.put("skills", "Agent skills, the published skill library and installs");
		map.put("spaces", "Spaces");
		map.put("tags", "Tags");
		map.put("tasks", "Tasks and their reactions and comments");
		map.put("tokens", "API tokens for programmatic access");
		map.put("users", "Users of the RBAC model");
		return map;
	}
}
