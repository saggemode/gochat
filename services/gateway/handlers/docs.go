package handlers

import (
	_ "embed"
	"net/http"

	"github.com/gin-gonic/gin"
)

//go:embed openapi.json
var openAPISpec []byte

// DocsHandler serves interactive OpenAPI/Swagger and Redoc documentation.
type DocsHandler struct{}

// NewDocsHandler creates a new DocsHandler instance.
func NewDocsHandler() *DocsHandler {
	return &DocsHandler{}
}

// OpenAPIJSON serves the raw OpenAPI 3.0 specification.
func (h *DocsHandler) OpenAPIJSON(c *gin.Context) {
	c.Data(http.StatusOK, "application/json; charset=utf-8", openAPISpec)
}

// SwaggerUI serves an interactive Swagger UI with dark theme and JWT auth support.
func (h *DocsHandler) SwaggerUI(c *gin.Context) {
	html := `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>GoChat API Documentation</title>
  <link rel="stylesheet" type="text/css" href="https://unpkg.com/swagger-ui-dist@5.11.0/swagger-ui.css" />
  <link rel="icon" type="image/png" href="https://unpkg.com/swagger-ui-dist@5.11.0/favicon-32x32.png" />
  <style>
    body {
      margin: 0;
      background: #0b0f17;
      color: #e2e8f0;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    }
    .topbar {
      display: none !important;
    }
    .swagger-ui {
      filter: invert(88%) hue-rotate(180deg);
    }
    .swagger-ui .topbar,
    .swagger-ui img,
    .swagger-ui svg,
    .swagger-ui .model-box {
      filter: invert(100%) hue-rotate(180deg);
    }
    .swagger-ui .info .title {
      color: #059669 !important;
    }
    .swagger-ui .scheme-container {
      background: #f8fafc !important;
      border-radius: 8px;
      margin-bottom: 20px;
    }
    .header-banner {
      background: #0f172a;
      border-bottom: 1px solid #1e293b;
      padding: 16px 24px;
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    .header-banner h1 {
      margin: 0;
      font-size: 1.25rem;
      font-weight: 700;
      color: #10b981;
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .header-banner .nav-links a {
      color: #94a3b8;
      text-decoration: none;
      font-size: 0.875rem;
      margin-left: 16px;
      font-weight: 500;
      transition: color 0.2s;
    }
    .header-banner .nav-links a:hover {
      color: #34d399;
    }
  </style>
</head>
<body>
  <div class="header-banner">
    <h1>🚀 GoChat Microservices API Gateway</h1>
    <div class="nav-links">
      <a href="/openapi.json" target="_blank">Raw OpenAPI JSON</a>
      <a href="/redoc">Redoc View</a>
      <a href="http://localhost:3000" target="_blank">Frontend App &rarr;</a>
    </div>
  </div>
  <div id="swagger-ui"></div>
  <script src="https://unpkg.com/swagger-ui-dist@5.11.0/swagger-ui-bundle.js"></script>
  <script src="https://unpkg.com/swagger-ui-dist@5.11.0/swagger-ui-standalone-preset.js"></script>
  <script>
    window.onload = function() {
      const ui = SwaggerUIBundle({
        url: "/openapi.json",
        dom_id: '#swagger-ui',
        deepLinking: true,
        presets: [
          SwaggerUIBundle.presets.apis,
          SwaggerUIStandalonePreset
        ],
        plugins: [
          SwaggerUIBundle.plugins.DownloadUrl
        ],
        layout: "BaseLayout",
        persistAuthorization: true,
        displayRequestDuration: true,
        filter: true,
        tryItOutEnabled: true
      });
      window.ui = ui;
    };
  </script>
</body>
</html>`
	c.Data(http.StatusOK, "text/html; charset=utf-8", []byte(html))
}

// Redoc serves clean, readable API reference documentation.
func (h *DocsHandler) Redoc(c *gin.Context) {
	html := `<!DOCTYPE html>
<html>
  <head>
    <title>GoChat API Reference (Redoc)</title>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <link href="https://fonts.googleapis.com/css?family=Montserrat:300,400,700|Roboto:300,400,700" rel="stylesheet">
    <style>
      body {
        margin: 0;
        padding: 0;
      }
    </style>
  </head>
  <body>
    <redoc spec-url='/openapi.json' theme='{"colors":{"primary":{"main":"#10b981"}}}'></redoc>
    <script src="https://cdn.redoc.ly/redoc/latest/bundles/redoc.standalone.js"></script>
  </body>
</html>`
	c.Data(http.StatusOK, "text/html; charset=utf-8", []byte(html))
}
