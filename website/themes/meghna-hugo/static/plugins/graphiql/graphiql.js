// Mounts a GraphiQL explorer into #graphiql, if present on the page.
//
// This mirrors plugins/swagger/swagger.js: the script is loaded on every page
// (declared globally in config.toml) so it MUST bail out when its mount point is
// absent, otherwise it would render into null and throw site-wide.
//
// On the static website there is no backend, so the schema is built in-browser
// from the staged SDL (`GraphiQL.GraphQL.buildSchema`) and query execution is
// disabled by default. A running Loom server offers a live GraphiQL at /graphiql.
//
// Overrides (mirroring swagger.js `data-openapi-url`):
//   data-schema-url   site-relative SDL to explore (default /docs/examples/schema.graphql)
//   data-graphql-url  optional live endpoint; when set, queries execute against it
window.addEventListener('load', function () {
	var mount = document.getElementById('graphiql');
	if (!mount) {
		return;
	}

	var schemaUrl = mount.getAttribute('data-schema-url') || '/docs/examples/schema.graphql';
	var graphqlUrl = mount.getAttribute('data-graphql-url');

	// graphql-js is re-exported by the GraphiQL UMD bundle as GraphiQL.GraphQL.
	var GraphQL = window.GraphiQL.GraphQL;

	fetch(schemaUrl)
		.then(function (response) {
			return response.text();
		})
		.then(function (sdl) {
			var schema = GraphQL.buildSchema(sdl);

			var fetcher = graphqlUrl
				? function (graphQLParams) {
						return fetch(graphqlUrl, {
							method: 'POST',
							headers: { 'Content-Type': 'application/json' },
							credentials: 'include',
							body: JSON.stringify(graphQLParams)
						}).then(function (response) {
							return response.json();
						});
					}
				: function () {
						return Promise.resolve({
							data: null,
							errors: [{
								message: 'Live query execution is disabled in these static docs. ' +
									'Run a Loom server and open its built-in GraphiQL at /graphiql to execute queries.'
							}]
						});
					};

			var defaultQuery = [
				'# Explore the Loom GraphQL schema using the Docs panel (top-left).',
				'# Autocomplete and validation work offline against the staged schema.',
				'query ListAssets {',
				'  assets {',
				'    uuid',
				'    filename',
				'    mimeType',
				'    size',
				'  }',
				'}'
			].join('\n');

			var root = window.ReactDOM.createRoot(mount);
			root.render(
				window.React.createElement(window.GraphiQL, {
					fetcher: fetcher,
					schema: schema,
					defaultQuery: defaultQuery
				})
			);
		})
		.catch(function (err) {
			mount.textContent = 'Failed to load GraphQL schema: ' + err;
		});
});
