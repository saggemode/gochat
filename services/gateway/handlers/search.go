package handlers

import (
	"net/http"
	"net/http/httputil"
	"net/url"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

type SearchHandler struct {
	searchServiceURL *url.URL
	log              *zap.Logger
}

func NewSearchHandler(serviceURL string, log *zap.Logger) *SearchHandler {
	u, _ := url.Parse(serviceURL)
	return &SearchHandler{searchServiceURL: u, log: log}
}

func (h *SearchHandler) ProxySearch(c *gin.Context) {
	proxy := httputil.NewSingleHostReverseProxy(h.searchServiceURL)
	proxy.Director = func(req *http.Request) {
		req.Header = c.Request.Header
		req.Host = h.searchServiceURL.Host
		req.URL.Scheme = h.searchServiceURL.Scheme
		req.URL.Host = h.searchServiceURL.Host
		req.URL.Path = c.Request.URL.Path
	}
	proxy.ServeHTTP(c.Writer, c.Request)
}
