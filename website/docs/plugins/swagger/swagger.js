window.onload = function() {
    const ui = SwaggerUIBundle({
      url: "http://localhost:1313/docs/examples/openapi.json",
      dom_id: '#swagger-ui',
      presets: [
        SwaggerUIBundle.presets.apis,
        SwaggerUIStandalonePreset
      ]
    })
  
    window.ui = ui
  }