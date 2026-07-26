window.onload = function() {
  // This script is loaded on every page (params.plugins.js in config.toml), but only
  // the REST API page provides a mount point. Without this guard SwaggerUIBundle tries
  // to render into `null` and throws React error #200 on every page of the site.
  var mount = document.getElementById('swagger-ui');
  if (!mount) {
    return;
  }

  // Must stay site-relative. An absolute http://localhost:... URL makes the published
  // site fetch the visitor's own machine, which trips the browser Local Network Access
  // prompt ("metaloom.io wants to access other apps and services on this device").
  // Override per page with <div id="swagger-ui" data-openapi-url="...">.
  var url = mount.getAttribute('data-openapi-url') || '/docs/examples/openapi.json';

  window.ui = SwaggerUIBundle({
    url: url,
    dom_id: '#swagger-ui',
    presets: [
      SwaggerUIBundle.presets.apis,
      SwaggerUIStandalonePreset
    ],
    layout: 'BaseLayout',
    // The spec covers ~130 paths, so the explorer opens as a browsable list of resource
    // groups rather than a wall of expanded operations. Deep links let a docs page - or a
    // colleague - link straight to a single operation.
    docExpansion: 'none',
    deepLinking: true,
    filter: true,
    tagsSorter: 'alpha',
    operationsSorter: 'alpha',
    defaultModelsExpandDepth: 0,
    displayRequestDuration: true,
    persistAuthorization: true,
    tryItOutEnabled: true,
    // Never call out to validator.swagger.io - the published site must not hand the spec
    // to a third party.
    validatorUrl: null
  })
}
